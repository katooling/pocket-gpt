// apps/mobile-android/src/main/cpp/turboquant.c
//
// TurboQuant core: WHT-based rotation, Q8_0/Q4_0 quantization, session mgmt.
// See turboquant.h for API documentation.

#include "turboquant.h"
#include "turboquant_codebooks.h"

#include <assert.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

#define TQ_Q8_0_BLOCK_SIZE  32
#define TQ_Q4_0_BLOCK_SIZE  32
#define TQ_Q8_0_BYTES_PER_BLOCK  (2 + TQ_Q8_0_BLOCK_SIZE)  // 34
#define TQ_Q4_0_BYTES_PER_BLOCK  (2 + TQ_Q4_0_BLOCK_SIZE / 2)  // 18

// Stack buffer threshold: vectors up to this size use stack scratch space.
#define TQ_STACK_MAX  1024

// Golden ratio fractional part as uint64 -- for layer seed derivation.
#define TQ_GOLDEN  0x9E3779B97F4A7C15ULL

// ---------------------------------------------------------------------------
// PRNG: xoshiro256** with SplitMix64 seeding
// ---------------------------------------------------------------------------

typedef struct {
    uint64_t s[4];
} tq_rng;

static uint64_t splitmix64(uint64_t * state) {
    uint64_t z = (*state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

static void tq_rng_seed(tq_rng * rng, uint64_t seed) {
    rng->s[0] = splitmix64(&seed);
    rng->s[1] = splitmix64(&seed);
    rng->s[2] = splitmix64(&seed);
    rng->s[3] = splitmix64(&seed);
}

static inline uint64_t rotl64(uint64_t x, int k) {
    return (x << k) | (x >> (64 - k));
}

static uint64_t tq_rng_next(tq_rng * rng) {
    const uint64_t result = rotl64(rng->s[1] * 5, 7) * 9;
    const uint64_t t = rng->s[1] << 17;
    rng->s[2] ^= rng->s[0];
    rng->s[3] ^= rng->s[1];
    rng->s[1] ^= rng->s[2];
    rng->s[0] ^= rng->s[3];
    rng->s[2] ^= t;
    rng->s[3] = rotl64(rng->s[3], 45);
    return result;
}

// ---------------------------------------------------------------------------
// fp16 <-> fp32 conversion (manual bit manipulation, no <float.h> dep)
// ---------------------------------------------------------------------------

static inline uint16_t fp32_to_fp16(float f) {
    uint32_t x;
    memcpy(&x, &f, sizeof(x));
    uint16_t sign = (uint16_t)((x >> 16) & 0x8000);
    int      exp  = (int)((x >> 23) & 0xFF) - 127;
    uint32_t mant = x & 0x007FFFFF;

    if (exp > 15) {
        // overflow -> inf
        return sign | 0x7C00;
    } else if (exp < -14) {
        // underflow -> zero (skip denorms for simplicity)
        return sign;
    }
    uint16_t hexp  = (uint16_t)((exp + 15) << 10);
    uint16_t hmant = (uint16_t)(mant >> 13);
    return sign | hexp | hmant;
}

static inline float fp16_to_fp32(uint16_t h) {
    uint32_t sign = ((uint32_t)h & 0x8000) << 16;
    uint32_t exp  = ((uint32_t)h >> 10) & 0x1F;
    uint32_t mant = (uint32_t)h & 0x03FF;

    if (exp == 0) {
        // zero / denorm -> zero
        float zero = 0.0f;
        uint32_t z;
        memcpy(&z, &zero, sizeof(z));
        z |= sign;
        float result;
        memcpy(&result, &z, sizeof(result));
        return result;
    } else if (exp == 31) {
        // inf/nan
        uint32_t bits = sign | 0x7F800000 | (mant << 13);
        float result;
        memcpy(&result, &bits, sizeof(result));
        return result;
    }
    uint32_t bits = sign | ((exp + 112) << 23) | (mant << 13);
    float result;
    memcpy(&result, &bits, sizeof(result));
    return result;
}

// ---------------------------------------------------------------------------
// Fast Walsh-Hadamard Transform (in-place, iterative butterfly)
// ---------------------------------------------------------------------------

static void wht_inplace(float * data, int n) {
    for (int stride = 1; stride < n; stride <<= 1) {
        for (int i = 0; i < n; i += stride << 1) {
            for (int j = i; j < i + stride; j++) {
                float a = data[j];
                float b = data[j + stride];
                data[j]          = a + b;
                data[j + stride] = a - b;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Layer context
// ---------------------------------------------------------------------------

struct tq_layer_ctx {
    int     head_dim;
    float   inv_sqrt_dim;   // 1.0f / sqrtf(head_dim)
    float * sign;           // +1.0f / -1.0f array, length = head_dim
};

static bool is_power_of_two(int n) {
    return n > 0 && (n & (n - 1)) == 0;
}

tq_layer_ctx * tq_layer_ctx_create(int head_dim, uint64_t seed) {
    if (!is_power_of_two(head_dim)) {
        return NULL;
    }

    tq_layer_ctx * ctx = (tq_layer_ctx *)calloc(1, sizeof(tq_layer_ctx));
    if (!ctx) return NULL;

    ctx->head_dim     = head_dim;
    ctx->inv_sqrt_dim = 1.0f / sqrtf((float)head_dim);

    ctx->sign = (float *)malloc((size_t)head_dim * sizeof(float));
    if (!ctx->sign) {
        free(ctx);
        return NULL;
    }

    // Generate deterministic random sign vector from seed
    tq_rng rng;
    tq_rng_seed(&rng, seed);
    for (int i = 0; i < head_dim; i++) {
        ctx->sign[i] = (tq_rng_next(&rng) & 1) ? 1.0f : -1.0f;
    }

    return ctx;
}

void tq_layer_ctx_free(tq_layer_ctx * ctx) {
    if (!ctx) return;
    free(ctx->sign);
    free(ctx);
}

// ---------------------------------------------------------------------------
// Single-vector rotation
// ---------------------------------------------------------------------------

// Forward: dst = (1/sqrt(n)) * WHT(sign .* src)
void tq_rotate_forward(const tq_layer_ctx * ctx,
                        const float * src, float * dst, int n)
{
    assert(ctx && n == ctx->head_dim);

    // Use stack buffer for small dims to handle src==dst aliasing
    float stack_buf[TQ_STACK_MAX];
    float * buf = (n <= TQ_STACK_MAX) ? stack_buf : (float *)malloc((size_t)n * sizeof(float));
    if (!buf) return;  // OOM fallback -- should not happen in practice

    // Element-wise sign flip
    const float * s = ctx->sign;
    for (int i = 0; i < n; i++) {
        buf[i] = s[i] * src[i];
    }

    // WHT in place
    wht_inplace(buf, n);

    // Scale and copy to dst
    float sc = ctx->inv_sqrt_dim;
    for (int i = 0; i < n; i++) {
        dst[i] = buf[i] * sc;
    }

    if (buf != stack_buf) free(buf);
}

// Inverse: dst = sign .* ((1/sqrt(n)) * WHT(src))
void tq_rotate_inverse(const tq_layer_ctx * ctx,
                        const float * src, float * dst, int n)
{
    assert(ctx && n == ctx->head_dim);

    float stack_buf[TQ_STACK_MAX];
    float * buf = (n <= TQ_STACK_MAX) ? stack_buf : (float *)malloc((size_t)n * sizeof(float));
    if (!buf) return;

    // Copy src into buf (handles aliasing)
    memcpy(buf, src, (size_t)n * sizeof(float));

    // WHT in place
    wht_inplace(buf, n);

    // Scale then sign-flip, write to dst
    float sc = ctx->inv_sqrt_dim;
    const float * s = ctx->sign;
    for (int i = 0; i < n; i++) {
        dst[i] = s[i] * (buf[i] * sc);
    }

    if (buf != stack_buf) free(buf);
}

// ---------------------------------------------------------------------------
// Batch rotate + quantize: Q8_0
// ---------------------------------------------------------------------------

// Q8_0 block layout (34 bytes): fp16 scale (2 bytes) + 32 int8 quants
static void quantize_q8_0_block(const float * src, uint8_t * dst) {
    // Find absmax for scaling
    float amax = 0.0f;
    for (int i = 0; i < TQ_Q8_0_BLOCK_SIZE; i++) {
        float av = fabsf(src[i]);
        if (av > amax) amax = av;
    }

    float scale     = amax / 127.0f;
    float inv_scale = (amax > 0.0f) ? 127.0f / amax : 0.0f;

    // Write fp16 scale
    uint16_t h = fp32_to_fp16(scale);
    memcpy(dst, &h, 2);

    // Write quantized int8 values
    int8_t * q = (int8_t *)(dst + 2);
    for (int i = 0; i < TQ_Q8_0_BLOCK_SIZE; i++) {
        float v = src[i] * inv_scale;
        int   r = (int)roundf(v);
        if (r > 127)  r = 127;
        if (r < -128) r = -128;
        q[i] = (int8_t)r;
    }
}

void tq_rotate_quantize_q8_0(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd)
{
    assert(ctx);
    int head_dim   = ctx->head_dim;
    int n_blocks   = n_embd / TQ_Q8_0_BLOCK_SIZE;
    uint8_t * out  = (uint8_t *)dst;

    // Scratch for one rotated vector
    float * rot = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!rot) return;

    for (int t = 0; t < n_tokens; t++) {
        const float * vec = src + (size_t)t * n_embd;

        // Rotate each head independently
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_forward(ctx, vec + off, rot + off, head_dim);
        }

        // Quantize rotated vector into Q8_0 blocks
        for (int b = 0; b < n_blocks; b++) {
            quantize_q8_0_block(
                rot + b * TQ_Q8_0_BLOCK_SIZE,
                out + ((size_t)t * n_blocks + b) * TQ_Q8_0_BYTES_PER_BLOCK);
        }
    }

    free(rot);
}

// ---------------------------------------------------------------------------
// Batch rotate + quantize: Q4_0 with Lloyd-Max codebook
// ---------------------------------------------------------------------------

// Q4_0 block layout (18 bytes): fp16 scale (2 bytes) + 16 bytes nibbles (32 values)
static void quantize_q4_0_block(const float * src, uint8_t * dst) {
    // Find absmax for block scaling
    float amax = 0.0f;
    for (int i = 0; i < TQ_Q4_0_BLOCK_SIZE; i++) {
        float av = fabsf(src[i]);
        if (av > amax) amax = av;
    }

    float scale     = amax;
    float inv_scale = (amax > 0.0f) ? 1.0f / amax : 0.0f;

    // Write fp16 scale
    uint16_t h = fp32_to_fp16(scale);
    memcpy(dst, &h, 2);

    // Quantize each value using Lloyd-Max codebook, pack as nibbles
    uint8_t * nibbles = dst + 2;
    memset(nibbles, 0, TQ_Q4_0_BLOCK_SIZE / 2);

    for (int i = 0; i < TQ_Q4_0_BLOCK_SIZE; i += 2) {
        uint8_t lo = tq_quantize_4bit(src[i],     inv_scale);
        uint8_t hi = tq_quantize_4bit(src[i + 1], inv_scale);
        nibbles[i / 2] = (hi << 4) | (lo & 0x0F);
    }
}

void tq_rotate_quantize_q4_0(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd)
{
    assert(ctx);
    int head_dim   = ctx->head_dim;
    int n_blocks   = n_embd / TQ_Q4_0_BLOCK_SIZE;
    uint8_t * out  = (uint8_t *)dst;

    float * rot = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!rot) return;

    for (int t = 0; t < n_tokens; t++) {
        const float * vec = src + (size_t)t * n_embd;

        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_forward(ctx, vec + off, rot + off, head_dim);
        }

        for (int b = 0; b < n_blocks; b++) {
            quantize_q4_0_block(
                rot + b * TQ_Q4_0_BLOCK_SIZE,
                out + ((size_t)t * n_blocks + b) * TQ_Q4_0_BYTES_PER_BLOCK);
        }
    }

    free(rot);
}

// ---------------------------------------------------------------------------
// Batch dequantize + inverse rotate: Q8_0
// ---------------------------------------------------------------------------

static void dequantize_q8_0_block(const uint8_t * src, float * dst) {
    uint16_t h;
    memcpy(&h, src, 2);
    float scale = fp16_to_fp32(h);

    const int8_t * q = (const int8_t *)(src + 2);
    for (int i = 0; i < TQ_Q8_0_BLOCK_SIZE; i++) {
        dst[i] = (float)q[i] * scale;
    }
}

void tq_dequantize_rotate_q8_0(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd)
{
    assert(ctx);
    int head_dim       = ctx->head_dim;
    int n_blocks       = n_embd / TQ_Q8_0_BLOCK_SIZE;
    const uint8_t * in = (const uint8_t *)src;

    float * tmp = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!tmp) return;

    for (int t = 0; t < n_tokens; t++) {
        float * vec_dst = dst + (size_t)t * n_embd;

        // Dequantize all blocks into tmp
        for (int b = 0; b < n_blocks; b++) {
            dequantize_q8_0_block(
                in + ((size_t)t * n_blocks + b) * TQ_Q8_0_BYTES_PER_BLOCK,
                tmp + b * TQ_Q8_0_BLOCK_SIZE);
        }

        // Inverse-rotate each head
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_inverse(ctx, tmp + off, vec_dst + off, head_dim);
        }
    }

    free(tmp);
}

// ---------------------------------------------------------------------------
// Batch dequantize + inverse rotate: Q4_0
// ---------------------------------------------------------------------------

static void dequantize_q4_0_block(const uint8_t * src, float * dst) {
    uint16_t h;
    memcpy(&h, src, 2);
    float scale = fp16_to_fp32(h);

    const uint8_t * nibbles = src + 2;
    for (int i = 0; i < TQ_Q4_0_BLOCK_SIZE; i += 2) {
        uint8_t byte = nibbles[i / 2];
        uint8_t lo = byte & 0x0F;
        uint8_t hi = byte >> 4;
        dst[i]     = tq_dequantize_4bit(lo, scale);
        dst[i + 1] = tq_dequantize_4bit(hi, scale);
    }
}

void tq_dequantize_rotate_q4_0(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd)
{
    assert(ctx);
    int head_dim       = ctx->head_dim;
    int n_blocks       = n_embd / TQ_Q4_0_BLOCK_SIZE;
    const uint8_t * in = (const uint8_t *)src;

    float * tmp = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!tmp) return;

    for (int t = 0; t < n_tokens; t++) {
        float * vec_dst = dst + (size_t)t * n_embd;

        for (int b = 0; b < n_blocks; b++) {
            dequantize_q4_0_block(
                in + ((size_t)t * n_blocks + b) * TQ_Q4_0_BYTES_PER_BLOCK,
                tmp + b * TQ_Q4_0_BLOCK_SIZE);
        }

        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_inverse(ctx, tmp + off, vec_dst + off, head_dim);
        }
    }

    free(tmp);
}

// ---------------------------------------------------------------------------
// Session management
// ---------------------------------------------------------------------------

struct tq_session {
    int              n_layers;
    int              head_dim;
    tq_layer_ctx **  layers;
};

tq_session * tq_session_create(int n_layers, int head_dim, uint64_t base_seed) {
    if (!is_power_of_two(head_dim) || n_layers <= 0) {
        return NULL;
    }

    tq_session * s = (tq_session *)calloc(1, sizeof(tq_session));
    if (!s) return NULL;

    s->n_layers = n_layers;
    s->head_dim = head_dim;
    s->layers   = (tq_layer_ctx **)calloc((size_t)n_layers, sizeof(tq_layer_ctx *));
    if (!s->layers) {
        free(s);
        return NULL;
    }

    for (int i = 0; i < n_layers; i++) {
        uint64_t layer_seed = base_seed ^ ((uint64_t)i * TQ_GOLDEN);
        s->layers[i] = tq_layer_ctx_create(head_dim, layer_seed);
        if (!s->layers[i]) {
            // Clean up on failure
            for (int j = 0; j < i; j++) {
                tq_layer_ctx_free(s->layers[j]);
            }
            free(s->layers);
            free(s);
            return NULL;
        }
    }

    return s;
}

void tq_session_free(tq_session * session) {
    if (!session) return;
    for (int i = 0; i < session->n_layers; i++) {
        tq_layer_ctx_free(session->layers[i]);
    }
    free(session->layers);
    free(session);
}

const tq_layer_ctx * tq_session_get_layer(const tq_session * session,
                                            int layer_idx)
{
    if (!session || layer_idx < 0 || layer_idx >= session->n_layers) {
        return NULL;
    }
    return session->layers[layer_idx];
}

size_t tq_session_memory_bytes(const tq_session * session) {
    if (!session) return 0;
    // Each layer: struct + head_dim floats for sign vector
    size_t per_layer = sizeof(tq_layer_ctx) + (size_t)session->head_dim * sizeof(float);
    size_t overhead  = sizeof(tq_session) + (size_t)session->n_layers * sizeof(tq_layer_ctx *);
    return overhead + (size_t)session->n_layers * per_layer;
}
