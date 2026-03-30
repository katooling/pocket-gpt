// apps/mobile-android/src/main/cpp/test_turboquant.cpp
//
// Compile and run on host:
// gcc -std=c11 -O2 -c turboquant.c -o /tmp/turboquant.o -lm
// g++ -std=c++17 -O2 -o /tmp/test_turboquant test_turboquant.cpp /tmp/turboquant.o -lm
// /tmp/test_turboquant

#include <cstdio>
#include <cmath>
#include <cassert>
#include <cstring>
#include <vector>
#include "turboquant.h"
#include "turboquant_codebooks.h"

static bool approx_equal(float a, float b, float tol = 1e-4f) {
    return fabsf(a - b) < tol;
}

// Test 1: WHT rotation preserves vector norm and is invertible
static void test_rotation_roundtrip() {
    printf("test_rotation_roundtrip... ");
    const int n = 128; // power of 2, typical head_dim
    tq_layer_ctx * ctx = tq_layer_ctx_create(n, 42);
    assert(ctx);

    float vec[128], rotated[128], recovered[128];
    // Create a non-trivial test vector
    for (int i = 0; i < n; i++) vec[i] = sinf((float)i * 0.1f);

    tq_rotate_forward(ctx, vec, rotated, n);
    tq_rotate_inverse(ctx, rotated, recovered, n);

    // Check roundtrip: recovered ≈ original
    for (int i = 0; i < n; i++) {
        assert(approx_equal(vec[i], recovered[i], 1e-3f));
    }

    // Check norm preservation
    float norm_orig = 0, norm_rot = 0;
    for (int i = 0; i < n; i++) {
        norm_orig += vec[i] * vec[i];
        norm_rot += rotated[i] * rotated[i];
    }
    assert(approx_equal(sqrtf(norm_orig), sqrtf(norm_rot), 1e-2f));

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Test 2: Q8_0 roundtrip has low error
static void test_q8_0_roundtrip() {
    printf("test_q8_0_roundtrip... ");
    const int n_embd = 128;
    const int n_tokens = 4;
    tq_layer_ctx * ctx = tq_layer_ctx_create(n_embd, 123);
    assert(ctx);

    float src[4 * 128];
    for (int i = 0; i < n_tokens * n_embd; i++) {
        src[i] = sinf((float)i * 0.1f) * 2.0f;
    }

    // Q8_0: 34 bytes per block of 32
    const int blocks_per_row = n_embd / 32;
    unsigned char quantized[4 * 4 * 34]; // n_tokens * blocks_per_row * 34

    tq_rotate_quantize_q8_0(ctx, src, quantized, n_tokens, n_embd);

    float recovered[4 * 128];
    tq_dequantize_rotate_q8_0(ctx, quantized, recovered, n_tokens, n_embd);

    float max_err = 0, max_val = 0;
    for (int i = 0; i < n_tokens * n_embd; i++) {
        float err = fabsf(src[i] - recovered[i]);
        if (err > max_err) max_err = err;
        if (fabsf(src[i]) > max_val) max_val = fabsf(src[i]);
    }
    float relative_err = max_err / (max_val + 1e-8f);
    printf("max_err=%.6f relative=%.4f%% ", max_err, relative_err * 100);
    assert(relative_err < 0.05f); // < 5% relative error for Q8_0 after rotation
    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Test 3: Q4_0 roundtrip (Lloyd-Max codebook)
static void test_q4_0_roundtrip() {
    printf("test_q4_0_roundtrip... ");
    const int n_embd = 128;
    const int n_tokens = 2;
    tq_layer_ctx * ctx = tq_layer_ctx_create(n_embd, 456);
    assert(ctx);

    float src[2 * 128];
    for (int i = 0; i < n_tokens * n_embd; i++) {
        src[i] = sinf((float)i * 0.3f) * 1.5f;
    }

    // Q4_0: 18 bytes per block of 32
    const int blocks_per_row = n_embd / 32;
    unsigned char quantized[2 * 4 * 18];

    tq_rotate_quantize_q4_0(ctx, src, quantized, n_tokens, n_embd);

    float recovered[2 * 128];
    tq_dequantize_rotate_q4_0(ctx, quantized, recovered, n_tokens, n_embd);

    float max_err = 0, max_val = 0, sum_sq = 0;
    for (int i = 0; i < n_tokens * n_embd; i++) {
        float err = fabsf(src[i] - recovered[i]);
        sum_sq += err * err;
        if (err > max_err) max_err = err;
        if (fabsf(src[i]) > max_val) max_val = fabsf(src[i]);
    }
    float rms_err = sqrtf(sum_sq / (n_tokens * n_embd));
    float relative_rms = rms_err / (max_val + 1e-8f);
    printf("max_err=%.6f rms_err=%.6f relative_rms=%.4f%% ", max_err, rms_err, relative_rms * 100);
    // 4-bit quantization: expect RMS error < 15% (worst-case spikes can be higher)
    assert(relative_rms < 0.15f);
    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Test 4: Session lifecycle
static void test_session_lifecycle() {
    printf("test_session_lifecycle... ");
    tq_session * s = tq_session_create(32, 128, 999);
    assert(s);
    assert(tq_session_get_layer(s, 0) != NULL);
    assert(tq_session_get_layer(s, 31) != NULL);
    assert(tq_session_get_layer(s, 32) == NULL); // out of bounds

    // Memory includes sign vectors + struct overhead
    // Primary cost is sign vectors: n_layers * head_dim * sizeof(float)
    size_t sign_memory = 32UL * 128 * sizeof(float);
    size_t actual = tq_session_memory_bytes(s);
    printf("memory=%zu (sign_vectors=%zu) ", actual, sign_memory);

    // Actual includes overhead, so should be >= sign vector memory
    assert(actual >= sign_memory);
    // But should be reasonable (< 2x due to struct overhead)
    assert(actual < sign_memory * 2);

    tq_session_free(s);
    printf("PASS\n");
}

// Test 5: Deterministic rotation (same seed = same result)
static void test_deterministic_rotation() {
    printf("test_deterministic_rotation... ");
    const int n = 64;
    float vec[64];
    for (int i = 0; i < n; i++) vec[i] = (float)i / n;

    float out1[64], out2[64];

    tq_layer_ctx * ctx1 = tq_layer_ctx_create(n, 42);
    tq_rotate_forward(ctx1, vec, out1, n);
    tq_layer_ctx_free(ctx1);

    tq_layer_ctx * ctx2 = tq_layer_ctx_create(n, 42);
    tq_rotate_forward(ctx2, vec, out2, n);
    tq_layer_ctx_free(ctx2);

    for (int i = 0; i < n; i++) {
        assert(out1[i] == out2[i]); // exact match, same seed
    }
    printf("PASS\n");
}

// Test 6: Different seeds produce different rotations
static void test_different_seeds() {
    printf("test_different_seeds... ");
    const int n = 64;
    float vec[64];
    for (int i = 0; i < n; i++) vec[i] = 1.0f;

    float out1[64], out2[64];

    tq_layer_ctx * ctx1 = tq_layer_ctx_create(n, 42);
    tq_rotate_forward(ctx1, vec, out1, n);
    tq_layer_ctx_free(ctx1);

    tq_layer_ctx * ctx2 = tq_layer_ctx_create(n, 99);
    tq_rotate_forward(ctx2, vec, out2, n);
    tq_layer_ctx_free(ctx2);

    int differences = 0;
    for (int i = 0; i < n; i++) {
        if (out1[i] != out2[i]) differences++;
    }
    assert(differences > n / 4); // at least 25% should differ
    printf("PASS (diff=%d/%d)\n", differences, n);
}

// Test 7: In-place rotation (src == dst)
static void test_inplace_rotation() {
    printf("test_inplace_rotation... ");
    const int n = 128;
    tq_layer_ctx * ctx = tq_layer_ctx_create(n, 77);
    assert(ctx);

    float vec[128], out_of_place[128];
    for (int i = 0; i < n; i++) vec[i] = (float)(i - n/2) * 0.01f;

    // Out-of-place
    tq_rotate_forward(ctx, vec, out_of_place, n);

    // In-place
    float inplace[128];
    memcpy(inplace, vec, sizeof(vec));
    tq_rotate_forward(ctx, inplace, inplace, n);

    for (int i = 0; i < n; i++) {
        assert(out_of_place[i] == inplace[i]);
    }

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Test 8: 3-bit codebook roundtrip
void test_3bit_codebook_roundtrip() {
    printf("test_3bit_codebook_roundtrip... ");
    float scale = 1.5f;
    float inv_scale = 1.0f / scale;
    // Test all 8 levels round-trip to expected centroids
    for (int i = 0; i < 8; i++) {
        float expected;
        if (i < 4) {
            expected = -TQ_CENTROID_3BIT_POS[3 - i] * scale;
        } else {
            expected = TQ_CENTROID_3BIT_POS[i - 4] * scale;
        }
        float reconstructed = tq_dequantize_3bit(i, scale);
        float err = fabsf(reconstructed - expected);
        assert(err < 1e-4f);
    }
    // Test quantize + dequantize roundtrip for known values
    float test_vals[] = {-3.0f, -1.0f, -0.3f, 0.0f, 0.3f, 1.0f, 3.0f};
    for (int i = 0; i < 7; i++) {
        uint8_t q = tq_quantize_3bit(test_vals[i], inv_scale);
        assert(q < 8);
        float recon = tq_dequantize_3bit(q, scale);
        assert(fabsf(recon) <= 4.0f * scale);
    }
    printf("PASS\n");
}

// Test 9: 2-bit codebook roundtrip
void test_2bit_codebook_roundtrip() {
    printf("test_2bit_codebook_roundtrip... ");
    float scale = 2.0f;
    float inv_scale = 1.0f / scale;
    // Test all 4 levels
    for (int i = 0; i < 4; i++) {
        float expected;
        if (i < 2) {
            expected = -TQ_CENTROID_2BIT_POS[1 - i] * scale;
        } else {
            expected = TQ_CENTROID_2BIT_POS[i - 2] * scale;
        }
        float reconstructed = tq_dequantize_2bit(i, scale);
        float err = fabsf(reconstructed - expected);
        assert(err < 1e-4f);
    }
    // Test quantize + dequantize roundtrip
    float test_vals[] = {-3.0f, -0.5f, 0.5f, 3.0f};
    for (int i = 0; i < 4; i++) {
        uint8_t q = tq_quantize_2bit(test_vals[i], inv_scale);
        assert(q < 4);
    }
    printf("PASS\n");
}

// Test 10: WHT rotation drives high-kurtosis vectors toward Gaussian (kurtosis ≈ 3)
static void test_rotation_gaussianity() {
    printf("  test_rotation_gaussianity... ");
    const int dim = 128;
    const uint64_t seed = 42;

    tq_layer_ctx * ctx = tq_layer_ctx_create(dim, seed);
    assert(ctx);

    // Create a high-kurtosis input: sparse vector with a few large entries.
    // (Pure one-hot maps to a constant under WHT, giving zero variance.)
    float input[128];
    for (int i = 0; i < dim; i++) input[i] = 0.01f * (i % 3 == 0 ? 1.0f : 0.0f);
    input[0] = 10.0f;
    input[7] = -5.0f;
    input[42] = 7.0f;  // Sparse/peaky: kurtosis >> 3

    // Compute pre-rotation kurtosis = E[x^4] / (E[x^2])^2
    double mean = 0, var = 0, kurt_num = 0;
    for (int i = 0; i < dim; i++) mean += input[i];
    mean /= dim;
    for (int i = 0; i < dim; i++) {
        double d = input[i] - mean;
        var += d * d;
    }
    var /= dim;
    for (int i = 0; i < dim; i++) {
        double d = input[i] - mean;
        kurt_num += d * d * d * d;
    }
    double pre_kurtosis = (kurt_num / dim) / (var * var);

    // Apply forward rotation
    float rotated[128];
    tq_rotate_forward(ctx, input, rotated, dim);

    // Compute post-rotation kurtosis
    mean = 0; var = 0; kurt_num = 0;
    for (int i = 0; i < dim; i++) mean += rotated[i];
    mean /= dim;
    for (int i = 0; i < dim; i++) {
        double d = rotated[i] - mean;
        var += d * d;
    }
    var /= dim;
    for (int i = 0; i < dim; i++) {
        double d = rotated[i] - mean;
        kurt_num += d * d * d * d;
    }
    double post_kurtosis = (kurt_num / dim) / (var * var);

    printf("pre=%.1f post=%.2f ", pre_kurtosis, post_kurtosis);

    // Pre-rotation should have very high kurtosis (one-hot)
    assert(pre_kurtosis > 50.0);
    // Post-rotation should be near Gaussian (kurtosis ~3.0, allow up to 5.0)
    assert(post_kurtosis < 5.0);

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Simple PRNG for test random numbers
static uint32_t test_rand_state = 12345;
static float test_randf() {
    test_rand_state = test_rand_state * 1664525 + 1013904223;
    return ((float)(test_rand_state >> 8) / 16777216.0f) * 2.0f - 1.0f; // uniform(-1, 1)
}

// Helper: compute dot product
static float dot_product(const float* a, const float* b, int n) {
    float sum = 0.0f;
    for (int i = 0; i < n; i++) {
        sum += a[i] * b[i];
    }
    return sum;
}

// Test 11: Inner product preservation through rotation+quantization roundtrip
static void test_inner_product_preservation() {
    printf("test_inner_product_preservation... ");
    const int dim = 128;
    const int n_pairs = 100;
    tq_layer_ctx * ctx = tq_layer_ctx_create(dim, 1337);
    assert(ctx);

    float max_abs_err_q8 = 0.0f;
    float max_abs_err_q4 = 0.0f;
    float sum_abs_err_q8 = 0.0f;
    float sum_abs_err_q4 = 0.0f;

    test_rand_state = 54321; // Reset PRNG state for reproducibility

    for (int pair = 0; pair < n_pairs; pair++) {
        // Generate random query and key vectors, then normalize
        // (Normalized vectors are realistic for attention: Q and K matrices have norm ~1)
        float query[128], key[128];
        float query_norm = 0.0f, key_norm = 0.0f;
        for (int i = 0; i < dim; i++) {
            query[i] = test_randf();
            key[i] = test_randf();
            query_norm += query[i] * query[i];
            key_norm += key[i] * key[i];
        }
        query_norm = sqrtf(query_norm);
        key_norm = sqrtf(key_norm);
        for (int i = 0; i < dim; i++) {
            query[i] /= query_norm;
            key[i] /= key_norm;
        }

        // True inner product (for unit-norm vectors, this is in [-1, 1])
        float true_dot = dot_product(query, key, dim);

        // Q8_0 path: roundtrip key through quantize+dequantize
        unsigned char quantized_q8[4 * 34]; // 4 blocks * 34 bytes
        tq_rotate_quantize_q8_0(ctx, key, quantized_q8, 1, dim);
        float recovered_key_q8[128];
        tq_dequantize_rotate_q8_0(ctx, quantized_q8, recovered_key_q8, 1, dim);
        float approx_dot_q8 = dot_product(query, recovered_key_q8, dim);
        float abs_err_q8 = fabsf(approx_dot_q8 - true_dot);
        if (abs_err_q8 > max_abs_err_q8) max_abs_err_q8 = abs_err_q8;
        sum_abs_err_q8 += abs_err_q8;

        // Q4_0 path: roundtrip key through quantize+dequantize
        unsigned char quantized_q4[4 * 18]; // 4 blocks * 18 bytes
        tq_rotate_quantize_q4_0(ctx, key, quantized_q4, 1, dim);
        float recovered_key_q4[128];
        tq_dequantize_rotate_q4_0(ctx, quantized_q4, recovered_key_q4, 1, dim);
        float approx_dot_q4 = dot_product(query, recovered_key_q4, dim);
        float abs_err_q4 = fabsf(approx_dot_q4 - true_dot);
        if (abs_err_q4 > max_abs_err_q4) max_abs_err_q4 = abs_err_q4;
        sum_abs_err_q4 += abs_err_q4;
    }

    float avg_abs_err_q8 = sum_abs_err_q8 / n_pairs;
    float avg_abs_err_q4 = sum_abs_err_q4 / n_pairs;

    printf("Q8_0: max_abs=%.4f avg_abs=%.4f | Q4_0: max_abs=%.4f avg_abs=%.4f ",
           max_abs_err_q8, avg_abs_err_q8, max_abs_err_q4, avg_abs_err_q4);

    // For normalized vectors, dot products are in [-1, 1]
    // Q8_0 should have max absolute error < 0.05 (5% of range)
    // Q4_0 should have max absolute error < 0.20 (20% of range)
    assert(max_abs_err_q8 < 0.05f);
    assert(max_abs_err_q4 < 0.20f);

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Test 12: Inner product with Q-rotation (validates Task 1 approach)
// Tests that <Rq, Rk'> ≈ <q, k'> where k' = roundtrip(k) and R is orthogonal
// This verifies that rotating the query forward before computing attention
// produces the same result as computing attention in the original domain
static void test_inner_product_with_q_rotation() {
    printf("test_inner_product_with_q_rotation... ");
    const int dim = 128;
    const int n_pairs = 100;
    tq_layer_ctx * ctx = tq_layer_ctx_create(dim, 7777);
    assert(ctx);

    float max_abs_err_q8 = 0.0f;
    float max_abs_err_q4 = 0.0f;

    test_rand_state = 99999; // Reset PRNG state

    for (int pair = 0; pair < n_pairs; pair++) {
        // Generate random query and key vectors, then normalize
        float query[128], key[128];
        float query_norm = 0.0f, key_norm = 0.0f;
        for (int i = 0; i < dim; i++) {
            query[i] = test_randf();
            key[i] = test_randf();
            query_norm += query[i] * query[i];
            key_norm += key[i] * key[i];
        }
        query_norm = sqrtf(query_norm);
        key_norm = sqrtf(key_norm);
        for (int i = 0; i < dim; i++) {
            query[i] /= query_norm;
            key[i] /= key_norm;
        }

        // Rotate query forward to get Rq
        float rotated_query[128];
        tq_rotate_forward(ctx, query, rotated_query, dim);

        // Q8_0 path
        unsigned char quantized_q8[4 * 34];
        tq_rotate_quantize_q8_0(ctx, key, quantized_q8, 1, dim);
        float recovered_key_q8[128];
        tq_dequantize_rotate_q8_0(ctx, quantized_q8, recovered_key_q8, 1, dim);

        // Compute <q, k'> (standard inner product after roundtrip)
        float dot_standard_q8 = dot_product(query, recovered_key_q8, dim);

        // Rotate recovered key forward to get R(k')
        float rotated_recovered_q8[128];
        tq_rotate_forward(ctx, recovered_key_q8, rotated_recovered_q8, dim);

        // Compute <Rq, Rk'> (inner product in rotated domain)
        float dot_rotated_q8 = dot_product(rotated_query, rotated_recovered_q8, dim);

        // By orthogonality of R: <Rq, Rk'> should equal <q, k'> exactly
        // (any difference is due to numerical precision, not quantization)
        float abs_err_q8 = fabsf(dot_rotated_q8 - dot_standard_q8);
        if (abs_err_q8 > max_abs_err_q8) max_abs_err_q8 = abs_err_q8;

        // Q4_0 path
        unsigned char quantized_q4[4 * 18];
        tq_rotate_quantize_q4_0(ctx, key, quantized_q4, 1, dim);
        float recovered_key_q4[128];
        tq_dequantize_rotate_q4_0(ctx, quantized_q4, recovered_key_q4, 1, dim);

        float dot_standard_q4 = dot_product(query, recovered_key_q4, dim);

        float rotated_recovered_q4[128];
        tq_rotate_forward(ctx, recovered_key_q4, rotated_recovered_q4, dim);
        float dot_rotated_q4 = dot_product(rotated_query, rotated_recovered_q4, dim);

        float abs_err_q4 = fabsf(dot_rotated_q4 - dot_standard_q4);
        if (abs_err_q4 > max_abs_err_q4) max_abs_err_q4 = abs_err_q4;
    }

    printf("Q8_0_max_abs_err=%.6f Q4_0_max_abs_err=%.6f ",
           max_abs_err_q8, max_abs_err_q4);

    // Orthogonality should be exact (modulo floating point precision)
    // Allow 1e-5 tolerance for numerical errors in the WHT butterfly ops
    assert(max_abs_err_q8 < 1e-5f);
    assert(max_abs_err_q4 < 1e-5f);

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Test 13: Session scratch buffers are provisioned and used by batch helpers
static void test_session_scratch_batch_helpers() {
    printf("test_session_scratch_batch_helpers... ");
    const int dim = 128;
    const int n_tokens = 3;
    tq_session * session = tq_session_create(4, dim, 2026);
    assert(session);
    assert(tq_session_set_max_embd(session, dim));
    assert(tq_session_get_scratch(session) != nullptr);

    const size_t min_memory = 4UL * dim * sizeof(float) + (size_t)dim * sizeof(float);
    assert(tq_session_memory_bytes(session) >= min_memory);

    float src[n_tokens * dim];
    for (int i = 0; i < n_tokens * dim; ++i) {
        src[i] = cosf((float)i * 0.17f) * 1.25f;
    }

    unsigned char session_q8[n_tokens * (dim / 32) * 34];
    unsigned char direct_q8[n_tokens * (dim / 32) * 34];
    tq_session_rotate_quantize_q8_0(session, 1, src, session_q8, n_tokens, dim);
    tq_rotate_quantize_q8_0(tq_session_get_layer(session, 1), src, direct_q8, n_tokens, dim);
    assert(memcmp(session_q8, direct_q8, sizeof(session_q8)) == 0);

    float recovered_session_q8[n_tokens * dim];
    float recovered_direct_q8[n_tokens * dim];
    tq_session_dequantize_rotate_q8_0(session, 1, session_q8, recovered_session_q8, n_tokens, dim);
    tq_dequantize_rotate_q8_0(tq_session_get_layer(session, 1), direct_q8, recovered_direct_q8, n_tokens, dim);
    for (int i = 0; i < n_tokens * dim; ++i) {
        assert(approx_equal(recovered_session_q8[i], recovered_direct_q8[i], 1e-6f));
    }

    unsigned char session_q4[n_tokens * (dim / 32) * 18];
    unsigned char direct_q4[n_tokens * (dim / 32) * 18];
    tq_session_rotate_quantize_q4_0(session, 2, src, session_q4, n_tokens, dim);
    tq_rotate_quantize_q4_0(tq_session_get_layer(session, 2), src, direct_q4, n_tokens, dim);
    assert(memcmp(session_q4, direct_q4, sizeof(session_q4)) == 0);

    float recovered_session_q4[n_tokens * dim];
    float recovered_direct_q4[n_tokens * dim];
    tq_session_dequantize_rotate_q4_0(session, 2, session_q4, recovered_session_q4, n_tokens, dim);
    tq_dequantize_rotate_q4_0(tq_session_get_layer(session, 2), direct_q4, recovered_direct_q4, n_tokens, dim);
    for (int i = 0; i < n_tokens * dim; ++i) {
        assert(approx_equal(recovered_session_q4[i], recovered_direct_q4[i], 1e-6f));
    }

    tq_session_free(session);
    printf("PASS\n");
}

// Test 14: Experimental low-bit Lloyd-Max helpers roundtrip cleanly
static void test_experimental_lowbit_roundtrip() {
    printf("test_experimental_lowbit_roundtrip... ");
    const int dim = 128;
    const int n_tokens = 2;
    tq_layer_ctx * ctx = tq_layer_ctx_create(dim, 2468);
    assert(ctx);

    std::vector<float> src(n_tokens * dim);
    for (int i = 0; i < n_tokens * dim; ++i) {
        src[i] = sinf((float)i * 0.11f) * 1.1f + cosf((float)i * 0.07f) * 0.35f;
    }

    std::vector<unsigned char> q3(tq_q3_lm_row_bytes(dim) * n_tokens);
    std::vector<unsigned char> q2(tq_q2_lm_row_bytes(dim) * n_tokens);
    std::vector<float> recovered_q3(n_tokens * dim);
    std::vector<float> recovered_q2(n_tokens * dim);

    tq_rotate_quantize_q3_lm(ctx, src.data(), q3.data(), n_tokens, dim);
    tq_dequantize_rotate_q3_lm(ctx, q3.data(), recovered_q3.data(), n_tokens, dim);
    tq_rotate_quantize_q2_lm(ctx, src.data(), q2.data(), n_tokens, dim);
    tq_dequantize_rotate_q2_lm(ctx, q2.data(), recovered_q2.data(), n_tokens, dim);

    float max_val = 0.0f;
    float q3_sum_sq = 0.0f;
    float q2_sum_sq = 0.0f;
    for (int i = 0; i < n_tokens * dim; ++i) {
        max_val = fmaxf(max_val, fabsf(src[i]));
        q3_sum_sq += (src[i] - recovered_q3[i]) * (src[i] - recovered_q3[i]);
        q2_sum_sq += (src[i] - recovered_q2[i]) * (src[i] - recovered_q2[i]);
    }
    const float q3_rms = sqrtf(q3_sum_sq / (n_tokens * dim)) / (max_val + 1e-8f);
    const float q2_rms = sqrtf(q2_sum_sq / (n_tokens * dim)) / (max_val + 1e-8f);
    printf("Q3_rms=%.4f%% Q2_rms=%.4f%% ", q3_rms * 100.0f, q2_rms * 100.0f);
    assert(q3_rms < 0.14f);
    assert(q2_rms < 0.24f);

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

// Test 15: Experimental QJL residual keeps low-bit inner-product distortion bounded
static void test_experimental_qjl_inner_product_distortion() {
    printf("test_experimental_qjl_inner_product_distortion... ");
    const int dim = 128;
    const int n_pairs = 48;
    tq_layer_ctx * ctx = tq_layer_ctx_create(dim, 5150);
    assert(ctx);

    float q3_qjl_signed_err = 0.0f;
    float q2_qjl_signed_err = 0.0f;
    float q3_qjl_abs_err = 0.0f;
    float q2_qjl_abs_err = 0.0f;
    test_rand_state = 8080;

    for (int pair = 0; pair < n_pairs; ++pair) {
        float query[128], key[128];
        float query_norm = 0.0f;
        float key_norm = 0.0f;
        for (int i = 0; i < dim; ++i) {
            query[i] = test_randf();
            key[i] = test_randf();
            query_norm += query[i] * query[i];
            key_norm += key[i] * key[i];
        }
        query_norm = sqrtf(query_norm);
        key_norm = sqrtf(key_norm);
        for (int i = 0; i < dim; ++i) {
            query[i] /= query_norm;
            key[i] /= key_norm;
        }

        const float true_dot = dot_product(query, key, dim);

        std::vector<unsigned char> q3_qjl(tq_q3_qjl_row_bytes(dim));
        std::vector<unsigned char> q2_qjl(tq_q2_qjl_row_bytes(dim));
        float recovered_q3[128];
        float recovered_q2[128];

        tq_rotate_quantize_q3_qjl(ctx, key, q3_qjl.data(), 1, dim);
        tq_dequantize_rotate_q3_qjl(ctx, q3_qjl.data(), recovered_q3, 1, dim);
        tq_rotate_quantize_q2_qjl(ctx, key, q2_qjl.data(), 1, dim);
        tq_dequantize_rotate_q2_qjl(ctx, q2_qjl.data(), recovered_q2, 1, dim);

        const float q3_err = dot_product(query, recovered_q3, dim) - true_dot;
        const float q2_err = dot_product(query, recovered_q2, dim) - true_dot;
        q3_qjl_signed_err += q3_err;
        q2_qjl_signed_err += q2_err;
        q3_qjl_abs_err += fabsf(q3_err);
        q2_qjl_abs_err += fabsf(q2_err);
    }

    const float q3_mean_signed = q3_qjl_signed_err / n_pairs;
    const float q2_mean_signed = q2_qjl_signed_err / n_pairs;
    const float q3_mean_abs = q3_qjl_abs_err / n_pairs;
    const float q2_mean_abs = q2_qjl_abs_err / n_pairs;
    printf(
        "Q3_QJL mean_signed=%.4f mean_abs=%.4f | Q2_QJL mean_signed=%.4f mean_abs=%.4f ",
        q3_mean_signed,
        q3_mean_abs,
        q2_mean_signed,
        q2_mean_abs);
    assert(fabsf(q3_mean_signed) < 0.05f);
    assert(fabsf(q2_mean_signed) < 0.04f);
    assert(q3_mean_abs < 0.11f);
    assert(q2_mean_abs < 0.11f);

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

int main() {
    printf("=== TurboQuant WHT Tests ===\n");
    test_rotation_roundtrip();
    test_q8_0_roundtrip();
    test_q4_0_roundtrip();
    test_session_lifecycle();
    test_deterministic_rotation();
    test_different_seeds();
    test_inplace_rotation();
    test_3bit_codebook_roundtrip();
    test_2bit_codebook_roundtrip();
    test_rotation_gaussianity();
    test_inner_product_preservation();
    test_inner_product_with_q_rotation();
    test_session_scratch_batch_helpers();
    test_experimental_lowbit_roundtrip();
    test_experimental_qjl_inner_product_distortion();
    printf("=== All %d tests passed ===\n", 15);
    return 0;
}
