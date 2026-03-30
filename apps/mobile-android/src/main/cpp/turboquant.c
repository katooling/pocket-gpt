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
#define TQ_Q3_LM_BLOCK_SIZE 32
#define TQ_Q2_LM_BLOCK_SIZE 32
#define TQ_Q8_0_BYTES_PER_BLOCK  (2 + TQ_Q8_0_BLOCK_SIZE)  // 34
#define TQ_Q4_0_BYTES_PER_BLOCK  (2 + TQ_Q4_0_BLOCK_SIZE / 2)  // 18
#define TQ_Q3_LM_BYTES_PER_BLOCK (2 + 12)  // fp16 scale + 32x3-bit payload
#define TQ_Q2_LM_BYTES_PER_BLOCK (2 + 8)   // fp16 scale + 32x2-bit payload

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

static float block_sigma(const float * src, int n) {
    float sum_sq = 0.0f;
    for (int i = 0; i < n; ++i) {
        sum_sq += src[i] * src[i];
    }
    return sqrtf(sum_sq / (float)n);
}

static uint64_t hash64(uint64_t x) {
    x ^= x >> 33;
    x *= 0xff51afd7ed558ccdULL;
    x ^= x >> 33;
    x *= 0xc4ceb9fe1a85ec53ULL;
    x ^= x >> 33;
    return x;
}

static float uniform01_from_u64(uint64_t value) {
    const uint64_t mantissa = (value >> 11) & ((1ULL << 53) - 1);
    return (float)((double)mantissa / (double)(1ULL << 53));
}

static float qjl_gaussian(uint64_t seed, int row, int col) {
    const uint64_t mixed_a = hash64(seed ^ ((uint64_t)row * TQ_GOLDEN) ^ ((uint64_t)col << 1));
    const uint64_t mixed_b = hash64(seed ^ ((uint64_t)col * TQ_GOLDEN) ^ ((uint64_t)row << 1) ^ 0xA5A5A5A5A5A5A5A5ULL);
    float u1 = uniform01_from_u64(mixed_a);
    float u2 = uniform01_from_u64(mixed_b);
    if (u1 < 1e-7f) u1 = 1e-7f;
    const float radius = sqrtf(-2.0f * logf(u1));
    const float theta = 6.2831853071795864769f * u2;
    return radius * cosf(theta);
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
    uint64_t seed;
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
    ctx->seed         = seed;
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

static float * tq_acquire_batch_scratch(float * scratch, int n_embd, bool * owned) {
    if (scratch) {
        *owned = false;
        return scratch;
    }
    *owned = true;
    return (float *)malloc((size_t)n_embd * sizeof(float));
}

static void tq_release_batch_scratch(float * scratch, bool owned) {
    if (owned) {
        free(scratch);
    }
}

static void tq_rotate_quantize_q8_0_impl(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd, float * scratch)
{
    assert(ctx);
    int head_dim   = ctx->head_dim;
    int n_blocks   = n_embd / TQ_Q8_0_BLOCK_SIZE;
    uint8_t * out  = (uint8_t *)dst;

    bool owned = false;
    float * rot = tq_acquire_batch_scratch(scratch, n_embd, &owned);
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

    tq_release_batch_scratch(rot, owned);
}

void tq_rotate_quantize_q8_0(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd)
{
    tq_rotate_quantize_q8_0_impl(ctx, src, dst, n_tokens, n_embd, NULL);
}

// ---------------------------------------------------------------------------
// Batch rotate + quantize: Q4_0 with Lloyd-Max codebook
// ---------------------------------------------------------------------------

// Q4_0 block layout (18 bytes): fp16 scale (2 bytes) + 16 bytes nibbles (32 values)
static void quantize_q4_0_block(const float * src, uint8_t * dst) {
    // Compute standard deviation as scale factor for Lloyd-Max codebook.
    // After WHT rotation, coordinates are ~N(0, sigma) where sigma = ||x||/sqrt(d).
    // The codebook is optimized for N(0,1), so dividing by sigma normalizes correctly.
    // Outliers beyond the codebook range (~2.73 sigma) map to the outermost centroid.
    float sum_sq = 0.0f;
    for (int i = 0; i < TQ_Q4_0_BLOCK_SIZE; i++) {
        sum_sq += src[i] * src[i];
    }
    float sigma = sqrtf(sum_sq / TQ_Q4_0_BLOCK_SIZE);

    float scale     = sigma;
    float inv_scale = (sigma > 1e-10f) ? 1.0f / sigma : 0.0f;

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

static void tq_rotate_quantize_q4_0_impl(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd, float * scratch)
{
    assert(ctx);
    int head_dim   = ctx->head_dim;
    int n_blocks   = n_embd / TQ_Q4_0_BLOCK_SIZE;
    uint8_t * out  = (uint8_t *)dst;

    bool owned = false;
    float * rot = tq_acquire_batch_scratch(scratch, n_embd, &owned);
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

    tq_release_batch_scratch(rot, owned);
}

void tq_rotate_quantize_q4_0(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd)
{
    tq_rotate_quantize_q4_0_impl(ctx, src, dst, n_tokens, n_embd, NULL);
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

static void tq_dequantize_rotate_q8_0_impl(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd, float * scratch)
{
    assert(ctx);
    int head_dim       = ctx->head_dim;
    int n_blocks       = n_embd / TQ_Q8_0_BLOCK_SIZE;
    const uint8_t * in = (const uint8_t *)src;

    bool owned = false;
    float * tmp = tq_acquire_batch_scratch(scratch, n_embd, &owned);
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

    tq_release_batch_scratch(tmp, owned);
}

void tq_dequantize_rotate_q8_0(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd)
{
    tq_dequantize_rotate_q8_0_impl(ctx, src, dst, n_tokens, n_embd, NULL);
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

static void tq_dequantize_rotate_q4_0_impl(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd, float * scratch)
{
    assert(ctx);
    int head_dim       = ctx->head_dim;
    int n_blocks       = n_embd / TQ_Q4_0_BLOCK_SIZE;
    const uint8_t * in = (const uint8_t *)src;

    bool owned = false;
    float * tmp = tq_acquire_batch_scratch(scratch, n_embd, &owned);
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

    tq_release_batch_scratch(tmp, owned);
}

void tq_dequantize_rotate_q4_0(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd)
{
    tq_dequantize_rotate_q4_0_impl(ctx, src, dst, n_tokens, n_embd, NULL);
}

// ---------------------------------------------------------------------------
// Experimental paper-faithful helpers: custom low-bit Lloyd-Max + QJL
// ---------------------------------------------------------------------------

size_t tq_q3_lm_row_bytes(int n_embd) {
    return (size_t)(n_embd / TQ_Q3_LM_BLOCK_SIZE) * TQ_Q3_LM_BYTES_PER_BLOCK;
}

size_t tq_q2_lm_row_bytes(int n_embd) {
    return (size_t)(n_embd / TQ_Q2_LM_BLOCK_SIZE) * TQ_Q2_LM_BYTES_PER_BLOCK;
}

size_t tq_q3_qjl_row_bytes(int n_embd) {
    return tq_q3_lm_row_bytes(n_embd) + (size_t)n_embd / 8;
}

size_t tq_q2_qjl_row_bytes(int n_embd) {
    return tq_q2_lm_row_bytes(n_embd) + (size_t)n_embd / 8;
}

static void pack_bits(uint8_t * dst, int payload_bytes, int index, int bits, uint8_t value) {
    const uint32_t mask = ((uint32_t)1 << bits) - 1U;
    const int bit_offset = index * bits;
    const int byte_offset = bit_offset / 8;
    const int shift = bit_offset % 8;
    uint32_t raw = 0;
    for (int i = 0; i < 3 && byte_offset + i < payload_bytes; ++i) {
        raw |= (uint32_t)dst[byte_offset + i] << (i * 8);
    }
    raw &= ~(mask << shift);
    raw |= ((uint32_t)value & mask) << shift;
    for (int i = 0; i < 3 && byte_offset + i < payload_bytes; ++i) {
        dst[byte_offset + i] = (uint8_t)((raw >> (i * 8)) & 0xFF);
    }
}

static uint8_t unpack_bits(const uint8_t * src, int payload_bytes, int index, int bits) {
    const uint32_t mask = ((uint32_t)1 << bits) - 1U;
    const int bit_offset = index * bits;
    const int byte_offset = bit_offset / 8;
    const int shift = bit_offset % 8;
    uint32_t raw = 0;
    for (int i = 0; i < 3 && byte_offset + i < payload_bytes; ++i) {
        raw |= (uint32_t)src[byte_offset + i] << (i * 8);
    }
    return (uint8_t)((raw >> shift) & mask);
}

static void quantize_q3_lm_block(const float * src, uint8_t * dst) {
    const float sigma = block_sigma(src, TQ_Q3_LM_BLOCK_SIZE);
    const float scale = sigma;
    const float inv_scale = (sigma > 1e-10f) ? 1.0f / sigma : 0.0f;
    const uint16_t h = fp32_to_fp16(scale);
    memcpy(dst, &h, 2);
    uint8_t * payload = dst + 2;
    memset(payload, 0, 12);
    for (int i = 0; i < TQ_Q3_LM_BLOCK_SIZE; ++i) {
        pack_bits(payload, 12, i, 3, tq_quantize_3bit(src[i], inv_scale));
    }
}

static void dequantize_q3_lm_block(const uint8_t * src, float * dst) {
    uint16_t h;
    memcpy(&h, src, 2);
    const float scale = fp16_to_fp32(h);
    const uint8_t * payload = src + 2;
    for (int i = 0; i < TQ_Q3_LM_BLOCK_SIZE; ++i) {
        dst[i] = tq_dequantize_3bit(unpack_bits(payload, 12, i, 3), scale);
    }
}

static void quantize_q2_lm_block(const float * src, uint8_t * dst) {
    const float sigma = block_sigma(src, TQ_Q2_LM_BLOCK_SIZE);
    const float scale = sigma;
    const float inv_scale = (sigma > 1e-10f) ? 1.0f / sigma : 0.0f;
    const uint16_t h = fp32_to_fp16(scale);
    memcpy(dst, &h, 2);
    uint8_t * payload = dst + 2;
    memset(payload, 0, 8);
    for (int i = 0; i < TQ_Q2_LM_BLOCK_SIZE; ++i) {
        pack_bits(payload, 8, i, 2, tq_quantize_2bit(src[i], inv_scale));
    }
}

static void dequantize_q2_lm_block(const uint8_t * src, float * dst) {
    uint16_t h;
    memcpy(&h, src, 2);
    const float scale = fp16_to_fp32(h);
    const uint8_t * payload = src + 2;
    for (int i = 0; i < TQ_Q2_LM_BLOCK_SIZE; ++i) {
        dst[i] = tq_dequantize_2bit(unpack_bits(payload, 8, i, 2), scale);
    }
}

static void qjl_encode_vector(const tq_layer_ctx * ctx, const float * residual, int n_embd, uint8_t * dst) {
    memset(dst, 0, (size_t)n_embd / 8);
    const uint64_t qjl_seed = ctx->seed ^ 0xC0DEC0DEC0DEC0DEULL;
    for (int row = 0; row < n_embd; ++row) {
        float projection = 0.0f;
        for (int col = 0; col < n_embd; ++col) {
            projection += qjl_gaussian(qjl_seed, row, col) * residual[col];
        }
        if (projection >= 0.0f) {
            dst[row / 8] |= (uint8_t)(1u << (row % 8));
        }
    }
}

static void qjl_decode_vector(const tq_layer_ctx * ctx, const uint8_t * src, int n_embd, float * dst) {
    const uint64_t qjl_seed = ctx->seed ^ 0xC0DEC0DEC0DEC0DEULL;
    const float scale = sqrtf(1.5707963267948966192f) / (float)n_embd;
    for (int col = 0; col < n_embd; ++col) {
        float accum = 0.0f;
        for (int row = 0; row < n_embd; ++row) {
            const float sign = ((src[row / 8] >> (row % 8)) & 1u) ? 1.0f : -1.0f;
            accum += qjl_gaussian(qjl_seed, row, col) * sign;
        }
        dst[col] = accum * scale;
    }
}

static void tq_rotate_quantize_q3_lm_impl(
    const tq_layer_ctx * ctx, const float * src, void * dst, int n_tokens, int n_embd)
{
    assert(ctx);
    const int head_dim = ctx->head_dim;
    const int n_blocks = n_embd / TQ_Q3_LM_BLOCK_SIZE;
    const size_t row_bytes = tq_q3_lm_row_bytes(n_embd);
    uint8_t * out = (uint8_t *)dst;
    float * rot = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!rot) return;
    for (int t = 0; t < n_tokens; ++t) {
        const float * vec = src + (size_t)t * n_embd;
        uint8_t * row_out = out + (size_t)t * row_bytes;
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_forward(ctx, vec + off, rot + off, head_dim);
        }
        for (int b = 0; b < n_blocks; ++b) {
            quantize_q3_lm_block(rot + b * TQ_Q3_LM_BLOCK_SIZE, row_out + (size_t)b * TQ_Q3_LM_BYTES_PER_BLOCK);
        }
    }
    free(rot);
}

static void tq_dequantize_rotate_q3_lm_impl(
    const tq_layer_ctx * ctx, const void * src, float * dst, int n_tokens, int n_embd)
{
    assert(ctx);
    const int head_dim = ctx->head_dim;
    const int n_blocks = n_embd / TQ_Q3_LM_BLOCK_SIZE;
    const size_t row_bytes = tq_q3_lm_row_bytes(n_embd);
    const uint8_t * in = (const uint8_t *)src;
    float * tmp = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!tmp) return;
    for (int t = 0; t < n_tokens; ++t) {
        const uint8_t * row_in = in + (size_t)t * row_bytes;
        float * row_dst = dst + (size_t)t * n_embd;
        for (int b = 0; b < n_blocks; ++b) {
            dequantize_q3_lm_block(row_in + (size_t)b * TQ_Q3_LM_BYTES_PER_BLOCK, tmp + b * TQ_Q3_LM_BLOCK_SIZE);
        }
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_inverse(ctx, tmp + off, row_dst + off, head_dim);
        }
    }
    free(tmp);
}

static void tq_rotate_quantize_q2_lm_impl(
    const tq_layer_ctx * ctx, const float * src, void * dst, int n_tokens, int n_embd)
{
    assert(ctx);
    const int head_dim = ctx->head_dim;
    const int n_blocks = n_embd / TQ_Q2_LM_BLOCK_SIZE;
    const size_t row_bytes = tq_q2_lm_row_bytes(n_embd);
    uint8_t * out = (uint8_t *)dst;
    float * rot = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!rot) return;
    for (int t = 0; t < n_tokens; ++t) {
        const float * vec = src + (size_t)t * n_embd;
        uint8_t * row_out = out + (size_t)t * row_bytes;
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_forward(ctx, vec + off, rot + off, head_dim);
        }
        for (int b = 0; b < n_blocks; ++b) {
            quantize_q2_lm_block(rot + b * TQ_Q2_LM_BLOCK_SIZE, row_out + (size_t)b * TQ_Q2_LM_BYTES_PER_BLOCK);
        }
    }
    free(rot);
}

static void tq_dequantize_rotate_q2_lm_impl(
    const tq_layer_ctx * ctx, const void * src, float * dst, int n_tokens, int n_embd)
{
    assert(ctx);
    const int head_dim = ctx->head_dim;
    const int n_blocks = n_embd / TQ_Q2_LM_BLOCK_SIZE;
    const size_t row_bytes = tq_q2_lm_row_bytes(n_embd);
    const uint8_t * in = (const uint8_t *)src;
    float * tmp = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!tmp) return;
    for (int t = 0; t < n_tokens; ++t) {
        const uint8_t * row_in = in + (size_t)t * row_bytes;
        float * row_dst = dst + (size_t)t * n_embd;
        for (int b = 0; b < n_blocks; ++b) {
            dequantize_q2_lm_block(row_in + (size_t)b * TQ_Q2_LM_BYTES_PER_BLOCK, tmp + b * TQ_Q2_LM_BLOCK_SIZE);
        }
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_inverse(ctx, tmp + off, row_dst + off, head_dim);
        }
    }
    free(tmp);
}

static void tq_rotate_quantize_qjl_impl(
    const tq_layer_ctx * ctx,
    const float * src,
    void * dst,
    int n_tokens,
    int n_embd,
    size_t main_row_bytes,
    void (*quantize_main_block)(const float *, uint8_t *),
    void (*dequantize_main_block)(const uint8_t *, float *),
    int block_size,
    size_t block_bytes)
{
    assert(ctx);
    const int head_dim = ctx->head_dim;
    const int n_blocks = n_embd / block_size;
    uint8_t * out = (uint8_t *)dst;
    float * work = (float *)malloc((size_t)n_embd * sizeof(float) * 3);
    if (!work) return;
    float * rot = work;
    float * approx = work + n_embd;
    float * residual = work + n_embd * 2;
    for (int t = 0; t < n_tokens; ++t) {
        const float * vec = src + (size_t)t * n_embd;
        uint8_t * row_out = out + (size_t)t * (main_row_bytes + (size_t)n_embd / 8);
        uint8_t * qjl_out = row_out + main_row_bytes;
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_forward(ctx, vec + off, rot + off, head_dim);
        }
        for (int b = 0; b < n_blocks; ++b) {
            uint8_t * block_ptr = row_out + (size_t)b * block_bytes;
            quantize_main_block(rot + b * block_size, block_ptr);
            dequantize_main_block(block_ptr, approx + b * block_size);
        }
        for (int i = 0; i < n_embd; ++i) {
            residual[i] = rot[i] - approx[i];
        }
        qjl_encode_vector(ctx, residual, n_embd, qjl_out);
    }
    free(work);
}

static void tq_dequantize_rotate_qjl_impl(
    const tq_layer_ctx * ctx,
    const void * src,
    float * dst,
    int n_tokens,
    int n_embd,
    size_t main_row_bytes,
    void (*dequantize_main_block)(const uint8_t *, float *),
    int block_size,
    size_t block_bytes)
{
    assert(ctx);
    const int head_dim = ctx->head_dim;
    const int n_blocks = n_embd / block_size;
    const uint8_t * in = (const uint8_t *)src;
    float * work = (float *)malloc((size_t)n_embd * sizeof(float) * 3);
    if (!work) return;
    float * main_rot = work;
    float * qjl_residual = work + n_embd;
    float * combined = work + n_embd * 2;
    for (int t = 0; t < n_tokens; ++t) {
        const uint8_t * row_in = in + (size_t)t * (main_row_bytes + (size_t)n_embd / 8);
        const uint8_t * qjl_in = row_in + main_row_bytes;
        float * row_dst = dst + (size_t)t * n_embd;
        for (int b = 0; b < n_blocks; ++b) {
            dequantize_main_block(row_in + (size_t)b * block_bytes, main_rot + b * block_size);
        }
        qjl_decode_vector(ctx, qjl_in, n_embd, qjl_residual);
        for (int i = 0; i < n_embd; ++i) {
            combined[i] = main_rot[i] + qjl_residual[i];
        }
        for (int off = 0; off < n_embd; off += head_dim) {
            tq_rotate_inverse(ctx, combined + off, row_dst + off, head_dim);
        }
    }
    free(work);
}

void tq_rotate_quantize_q3_lm(
    const tq_layer_ctx * ctx, const float * src, void * dst, int n_tokens, int n_embd)
{
    tq_rotate_quantize_q3_lm_impl(ctx, src, dst, n_tokens, n_embd);
}

void tq_dequantize_rotate_q3_lm(
    const tq_layer_ctx * ctx, const void * src, float * dst, int n_tokens, int n_embd)
{
    tq_dequantize_rotate_q3_lm_impl(ctx, src, dst, n_tokens, n_embd);
}

void tq_rotate_quantize_q2_lm(
    const tq_layer_ctx * ctx, const float * src, void * dst, int n_tokens, int n_embd)
{
    tq_rotate_quantize_q2_lm_impl(ctx, src, dst, n_tokens, n_embd);
}

void tq_dequantize_rotate_q2_lm(
    const tq_layer_ctx * ctx, const void * src, float * dst, int n_tokens, int n_embd)
{
    tq_dequantize_rotate_q2_lm_impl(ctx, src, dst, n_tokens, n_embd);
}

void tq_rotate_quantize_q3_qjl(
    const tq_layer_ctx * ctx, const float * src, void * dst, int n_tokens, int n_embd)
{
    tq_rotate_quantize_qjl_impl(
        ctx, src, dst, n_tokens, n_embd, tq_q3_lm_row_bytes(n_embd),
        quantize_q3_lm_block, dequantize_q3_lm_block,
        TQ_Q3_LM_BLOCK_SIZE, TQ_Q3_LM_BYTES_PER_BLOCK);
}

void tq_dequantize_rotate_q3_qjl(
    const tq_layer_ctx * ctx, const void * src, float * dst, int n_tokens, int n_embd)
{
    tq_dequantize_rotate_qjl_impl(
        ctx, src, dst, n_tokens, n_embd, tq_q3_lm_row_bytes(n_embd),
        dequantize_q3_lm_block,
        TQ_Q3_LM_BLOCK_SIZE, TQ_Q3_LM_BYTES_PER_BLOCK);
}

void tq_rotate_quantize_q2_qjl(
    const tq_layer_ctx * ctx, const float * src, void * dst, int n_tokens, int n_embd)
{
    tq_rotate_quantize_qjl_impl(
        ctx, src, dst, n_tokens, n_embd, tq_q2_lm_row_bytes(n_embd),
        quantize_q2_lm_block, dequantize_q2_lm_block,
        TQ_Q2_LM_BLOCK_SIZE, TQ_Q2_LM_BYTES_PER_BLOCK);
}

void tq_dequantize_rotate_q2_qjl(
    const tq_layer_ctx * ctx, const void * src, float * dst, int n_tokens, int n_embd)
{
    tq_dequantize_rotate_qjl_impl(
        ctx, src, dst, n_tokens, n_embd, tq_q2_lm_row_bytes(n_embd),
        dequantize_q2_lm_block,
        TQ_Q2_LM_BLOCK_SIZE, TQ_Q2_LM_BYTES_PER_BLOCK);
}

// ---------------------------------------------------------------------------
// Session management
// ---------------------------------------------------------------------------

struct tq_session {
    int              n_layers;
    int              head_dim;
    tq_layer_ctx **  layers;
    int              max_n_embd;
    float *          scratch;
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
    free(session->scratch);
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
    size_t scratch   = session->scratch ? (size_t)session->max_n_embd * sizeof(float) : 0;
    return overhead + (size_t)session->n_layers * per_layer + scratch;
}

bool tq_session_set_max_embd(tq_session * session, int max_n_embd) {
    if (!session || max_n_embd <= 0) return false;
    free(session->scratch);  // free previous if any
    session->scratch = (float *)malloc((size_t)max_n_embd * sizeof(float));
    if (!session->scratch) {
        session->max_n_embd = 0;
        return false;
    }
    session->max_n_embd = max_n_embd;
    return true;
}

float * tq_session_get_scratch(const tq_session * session) {
    if (!session) return NULL;
    return session->scratch;
}

void tq_session_rotate_quantize_q8_0(
    const tq_session * session, int layer_idx, const float * src, void * dst,
    int n_tokens, int n_embd)
{
    if (!session) return;
    const tq_layer_ctx * layer = tq_session_get_layer(session, layer_idx);
    if (!layer) return;
    float * scratch = session->max_n_embd >= n_embd ? session->scratch : NULL;
    tq_rotate_quantize_q8_0_impl(layer, src, dst, n_tokens, n_embd, scratch);
}

void tq_session_rotate_quantize_q4_0(
    const tq_session * session, int layer_idx, const float * src, void * dst,
    int n_tokens, int n_embd)
{
    if (!session) return;
    const tq_layer_ctx * layer = tq_session_get_layer(session, layer_idx);
    if (!layer) return;
    float * scratch = session->max_n_embd >= n_embd ? session->scratch : NULL;
    tq_rotate_quantize_q4_0_impl(layer, src, dst, n_tokens, n_embd, scratch);
}

void tq_session_dequantize_rotate_q8_0(
    const tq_session * session, int layer_idx, const void * src, float * dst,
    int n_tokens, int n_embd)
{
    if (!session) return;
    const tq_layer_ctx * layer = tq_session_get_layer(session, layer_idx);
    if (!layer) return;
    float * scratch = session->max_n_embd >= n_embd ? session->scratch : NULL;
    tq_dequantize_rotate_q8_0_impl(layer, src, dst, n_tokens, n_embd, scratch);
}

void tq_session_dequantize_rotate_q4_0(
    const tq_session * session, int layer_idx, const void * src, float * dst,
    int n_tokens, int n_embd)
{
    if (!session) return;
    const tq_layer_ctx * layer = tq_session_get_layer(session, layer_idx);
    if (!layer) return;
    float * scratch = session->max_n_embd >= n_embd ? session->scratch : NULL;
    tq_dequantize_rotate_q4_0_impl(layer, src, dst, n_tokens, n_embd, scratch);
}

// ggml-compatible row-level quantize/dequantize wrappers.
// These operate on raw blocks without rotation — rotation is applied
// separately by the KV cache rotation hooks.

void dequantize_row_tq_q3_lm(const void * x, float * y, int64_t k) {
    assert(k % TQ_Q3_LM_BLOCK_SIZE == 0);
    const int64_t n_blocks = k / TQ_Q3_LM_BLOCK_SIZE;
    const uint8_t * src = (const uint8_t *)x;
    for (int64_t b = 0; b < n_blocks; ++b) {
        dequantize_q3_lm_block(src + b * TQ_Q3_LM_BYTES_PER_BLOCK,
                               y + b * TQ_Q3_LM_BLOCK_SIZE);
    }
}

void quantize_row_tq_q3_lm(const float * x, void * y, int64_t k) {
    assert(k % TQ_Q3_LM_BLOCK_SIZE == 0);
    const int64_t n_blocks = k / TQ_Q3_LM_BLOCK_SIZE;
    uint8_t * dst = (uint8_t *)y;
    for (int64_t b = 0; b < n_blocks; ++b) {
        quantize_q3_lm_block(x + b * TQ_Q3_LM_BLOCK_SIZE,
                             dst + b * TQ_Q3_LM_BYTES_PER_BLOCK);
    }
}

void dequantize_row_tq_q2_lm(const void * x, float * y, int64_t k) {
    assert(k % TQ_Q2_LM_BLOCK_SIZE == 0);
    const int64_t n_blocks = k / TQ_Q2_LM_BLOCK_SIZE;
    const uint8_t * src = (const uint8_t *)x;
    for (int64_t b = 0; b < n_blocks; ++b) {
        dequantize_q2_lm_block(src + b * TQ_Q2_LM_BYTES_PER_BLOCK,
                               y + b * TQ_Q2_LM_BLOCK_SIZE);
    }
}

void quantize_row_tq_q2_lm(const float * x, void * y, int64_t k) {
    assert(k % TQ_Q2_LM_BLOCK_SIZE == 0);
    const int64_t n_blocks = k / TQ_Q2_LM_BLOCK_SIZE;
    uint8_t * dst = (uint8_t *)y;
    for (int64_t b = 0; b < n_blocks; ++b) {
        quantize_q2_lm_block(x + b * TQ_Q2_LM_BLOCK_SIZE,
                             dst + b * TQ_Q2_LM_BYTES_PER_BLOCK);
    }
}
