// apps/mobile-android/src/main/cpp/turboquant.h
#pragma once

// TurboQuant: Walsh-Hadamard Transform (WHT) based rotation for KV cache
// quantization. Rotates KV vectors to make coordinates approximately i.i.d.
// Gaussian, so standard quantizers become near-optimal.
//
// WHT rotation is O(d log d) compute, O(d) storage per layer -- dramatically
// cheaper than full QR rotation (O(d^2) compute/storage).

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- Layer Context (stores random sign vector for WHT-based rotation) ---
typedef struct tq_layer_ctx tq_layer_ctx;

// Create a rotation context for a single layer.
// head_dim: dimension per KV head (e.g., 128). Must be a power of 2.
// seed: deterministic seed for reproducibility across load/unload cycles.
// Returns NULL if head_dim is not a power of 2 or allocation fails.
tq_layer_ctx * tq_layer_ctx_create(int head_dim, uint64_t seed);
void            tq_layer_ctx_free(tq_layer_ctx * ctx);

// --- Single-Vector Rotation ---
// Forward: sign-flip + WHT + scale by 1/sqrt(n) (before quantization)
// Inverse: scale by 1/sqrt(n) + WHT + sign-flip (after dequantization)
// n must equal head_dim. src and dst may alias (src == dst is allowed).
void tq_rotate_forward(const tq_layer_ctx * ctx,
                        const float * src, float * dst, int n);
void tq_rotate_inverse(const tq_layer_ctx * ctx,
                        const float * src, float * dst, int n);

// --- Batch Rotate + Quantize ---
// Q8_0 path (BALANCED preset): rotate then standard absmax Q8_0
// dst must hold n_tokens * (n_embd / 32) * 34 bytes (Q8_0 block size).
void tq_rotate_quantize_q8_0(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd);

// Q4_0 path (AGGRESSIVE preset): rotate then Lloyd-Max 4-bit codebook
// dst must hold n_tokens * (n_embd / 32) * 18 bytes (Q4_0 block size).
void tq_rotate_quantize_q4_0(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd);

// --- Batch Dequantize + Inverse Rotate ---
void tq_dequantize_rotate_q8_0(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd);

void tq_dequantize_rotate_q4_0(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd);

// --- Session (manages all layers) ---
typedef struct tq_session tq_session;

// Create a session managing n_layers rotation contexts.
// Each layer gets a unique, deterministic seed derived from base_seed.
tq_session *          tq_session_create(int n_layers, int head_dim,
                                        uint64_t base_seed);
void                  tq_session_free(tq_session * session);
const tq_layer_ctx *  tq_session_get_layer(const tq_session * session,
                                            int layer_idx);
size_t                tq_session_memory_bytes(const tq_session * session);

// Pre-allocate scratch buffer for batch rotate+quantize/dequantize functions.
// Call once after session creation with the model's n_embd.
// Returns false on allocation failure.
bool tq_session_set_max_embd(tq_session * session, int max_n_embd);

// Get pre-allocated scratch buffer (NULL if not set).
float * tq_session_get_scratch(const tq_session * session);

// Session-backed batch helpers that reuse the pre-allocated scratch buffer
// when available. These are intended for tests and future runtime paths that
// need batched rotate+quantize/dequantize operations without per-call malloc.
void tq_session_rotate_quantize_q8_0(
    const tq_session * session, int layer_idx, const float * src, void * dst,
    int n_tokens, int n_embd);

void tq_session_rotate_quantize_q4_0(
    const tq_session * session, int layer_idx, const float * src, void * dst,
    int n_tokens, int n_embd);

void tq_session_dequantize_rotate_q8_0(
    const tq_session * session, int layer_idx, const void * src, float * dst,
    int n_tokens, int n_embd);

void tq_session_dequantize_rotate_q4_0(
    const tq_session * session, int layer_idx, const void * src, float * dst,
    int n_tokens, int n_embd);

// Experimental paper-faithful helpers. These are not wired into the shipping
// ggml KV cache path yet, but they provide the custom low-bit Lloyd-Max and
// QJL building blocks needed for runtime integration and benchmarking.
size_t tq_q3_lm_row_bytes(int n_embd);
size_t tq_q2_lm_row_bytes(int n_embd);
size_t tq_q3_qjl_row_bytes(int n_embd);
size_t tq_q2_qjl_row_bytes(int n_embd);

void tq_rotate_quantize_q3_lm(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd);
void tq_dequantize_rotate_q3_lm(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd);

void tq_rotate_quantize_q2_lm(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd);
void tq_dequantize_rotate_q2_lm(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd);

void tq_rotate_quantize_q3_qjl(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd);
void tq_dequantize_rotate_q3_qjl(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd);

void tq_rotate_quantize_q2_qjl(
    const tq_layer_ctx * ctx, const float * src, void * dst,
    int n_tokens, int n_embd);
void tq_dequantize_rotate_q2_qjl(
    const tq_layer_ctx * ctx, const void * src, float * dst,
    int n_tokens, int n_embd);

#ifdef __cplusplus
}
#endif
