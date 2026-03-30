// apps/mobile-android/src/main/cpp/turboquant_codebooks.h
#pragma once

// Lloyd-Max optimal quantizer centroids for N(0,1) distribution.
// High-dimensional limit of Beta(d/2, d/2) after rotation.
// 4-bit: 16 levels (for AGGRESSIVE preset mapping to Q4_0-like compression)
// 8-bit: uses standard Q8_0 absmax uniform quantizer (near-optimal for Gaussian)
//
// Boundary[i] = midpoint between centroid[i] and centroid[i+1].
// Quantize: find i such that boundary[i-1] <= x < boundary[i].
// Dequantize: return centroid[i].

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// 4-bit Lloyd-Max centroids for N(0,1), 16 levels, symmetric
// Generated from standard Lloyd-Max algorithm on Gaussian distribution.
// Levels are symmetric: centroid[-i] = -centroid[i].
// We store only the positive half (8 values); negative half is mirrored.
static const float TQ_CENTROID_4BIT_POS[8] = {
    0.1284f, 0.3884f, 0.6568f, 0.9423f,
    1.2562f, 1.6180f, 2.0690f, 2.7326f,
};

// Decision boundaries for 4-bit (positive half, 7 boundaries + implicit 0 and +inf)
static const float TQ_BOUNDARY_4BIT_POS[7] = {
    0.2582f, 0.5224f, 0.7996f, 1.0993f,
    1.4371f, 1.8435f, 2.4008f,
};

// 8-bit Lloyd-Max: for Q8_0 blocks we use the existing Q8_0 uniform quantizer
// after rotation, which is near-optimal because rotated coordinates are
// approximately i.i.d. Gaussian. No custom codebook needed for 8-bit --
// the standard absmax uniform quantizer in Q8_0 achieves within 1.1x of
// Lloyd-Max for Gaussian inputs at 256 levels.
//
// For 4-bit we need the non-uniform codebook because Q4_0's uniform
// quantizer loses ~30% vs Lloyd-Max at only 16 levels.

// Number of positive levels for each bit width
#define TQ_LEVELS_4BIT 8

// Map a float value to a 4-bit quantized index (0-15).
// Input should be pre-normalized (zero mean, known scale).
static inline uint8_t tq_quantize_4bit(float x, float inv_scale) {
    float v = x * inv_scale;
    // Symmetric: index 0-7 = negative, 8-15 = positive
    if (v < 0.0f) {
        float av = -v;
        uint8_t idx = 0;
        for (int i = 0; i < 7; i++) {
            if (av >= TQ_BOUNDARY_4BIT_POS[i]) idx = (uint8_t)(i + 1);
        }
        return 7 - idx; // map: most negative = 0, zero-crossing = 7
    } else {
        uint8_t idx = 0;
        for (int i = 0; i < 7; i++) {
            if (v >= TQ_BOUNDARY_4BIT_POS[i]) idx = (uint8_t)(i + 1);
        }
        return 8 + idx; // map: zero-crossing = 8, most positive = 15
    }
}

// Dequantize a 4-bit index back to float (before inverse rotation).
static inline float tq_dequantize_4bit(uint8_t idx, float scale) {
    if (idx < 8) {
        return -TQ_CENTROID_4BIT_POS[7 - idx] * scale;
    } else {
        return TQ_CENTROID_4BIT_POS[idx - 8] * scale;
    }
}

// 3-bit Lloyd-Max centroids for N(0,1), 8 levels, symmetric
// 4 positive centroids, mirrored for negative.
#define TQ_LEVELS_3BIT 4

static const float TQ_CENTROID_3BIT_POS[4] = {
    0.2451f, 0.7560f, 1.3440f, 2.1520f,
};

static const float TQ_BOUNDARY_3BIT_POS[3] = {
    0.5006f, 1.0500f, 1.7480f,
};

static inline uint8_t tq_quantize_3bit(float x, float inv_scale) {
    float v = x * inv_scale;
    if (v < 0.0f) {
        float av = -v;
        uint8_t idx = 0;
        for (int i = 0; i < 3; i++) {
            if (av >= TQ_BOUNDARY_3BIT_POS[i]) idx = (uint8_t)(i + 1);
        }
        return 3 - idx; // map: most negative = 0, zero-crossing = 3
    } else {
        uint8_t idx = 0;
        for (int i = 0; i < 3; i++) {
            if (v >= TQ_BOUNDARY_3BIT_POS[i]) idx = (uint8_t)(i + 1);
        }
        return 4 + idx; // map: zero-crossing = 4, most positive = 7
    }
}

static inline float tq_dequantize_3bit(uint8_t idx, float scale) {
    if (idx < 4) {
        return -TQ_CENTROID_3BIT_POS[3 - idx] * scale;
    } else {
        return TQ_CENTROID_3BIT_POS[idx - 4] * scale;
    }
}

// 2-bit Lloyd-Max centroids for N(0,1), 4 levels, symmetric
// 2 positive centroids, mirrored for negative.
#define TQ_LEVELS_2BIT 2

static const float TQ_CENTROID_2BIT_POS[2] = {
    0.4528f, 1.5104f,
};

static const float TQ_BOUNDARY_2BIT_POS[1] = {
    0.9816f,
};

static inline uint8_t tq_quantize_2bit(float x, float inv_scale) {
    float v = x * inv_scale;
    if (v < 0.0f) {
        float av = -v;
        uint8_t idx = (av >= TQ_BOUNDARY_2BIT_POS[0]) ? 1 : 0;
        return 1 - idx; // map: most negative = 0, zero-crossing = 1
    } else {
        uint8_t idx = (v >= TQ_BOUNDARY_2BIT_POS[0]) ? 1 : 0;
        return 2 + idx; // map: zero-crossing = 2, most positive = 3
    }
}

static inline float tq_dequantize_2bit(uint8_t idx, float scale) {
    if (idx < 2) {
        return -TQ_CENTROID_2BIT_POS[1 - idx] * scale;
    } else {
        return TQ_CENTROID_2BIT_POS[idx - 2] * scale;
    }
}

#ifdef __cplusplus
}
#endif
