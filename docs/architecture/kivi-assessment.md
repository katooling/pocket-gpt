# KIVI Assessment: Per-Channel K / Per-Token V After WHT Rotation

## Summary

KIVI (arXiv 2402.02750) recommends quantizing keys per-channel and values per-token
because key channels have heterogeneous variance while value tokens are more uniform.

After WHT rotation, the original motivation for per-channel key scaling is reduced in
our implementation. The Walsh-Hadamard Transform makes coordinates more Gaussian-like
and less axis-dependent, so a simpler blockwise path becomes much more defensible.

## Analysis

### Pre-rotation (KIVI's assumption)
- Key channels have widely varying magnitudes (some channels carry 10-100x more info)
- Per-channel quantization captures these differences with per-channel scale factors
- Value tokens are more uniform in magnitude across the sequence dimension

### Post-rotation (our implementation)
- WHT rotation normalizes all key dimensions to approximately equal variance
- Empirically verified in test suite (test_rotation_gaussianity): kurtosis drops from >50 to <5 after rotation, confirming near-Gaussian coordinates
- Per-channel scale factors are less clearly justified once the rotated coordinates look substantially more homogeneous
- Standard block-based quantization (groups of 32 elements) is already near-optimal

### Note on SRHT vs Haar-random rotation
Our implementation uses a Signed Randomized Hadamard Transform (SRHT): D * H where D
is a random diagonal +/-1 matrix and H is the Hadamard matrix. This is O(d log d) compute
and O(d) storage, versus O(d^2) for a true Haar-random orthogonal matrix. The coordinate
distribution after SRHT is not exactly Beta(d/2, d/2) as the paper's Lemma 1 assumes, but
it is a practical engineering approximation rather than a theorem-equivalent substitute for
Haar-random rotation. Multiple SRHT rounds (D2 * H * D1 * H) could be explored later if
stronger concentration is needed.

### What we implement instead
- **Block-based quantization** (Q8_0, Q4_0, Q3_K, Q2_K) applied uniformly
- **Asymmetric K/V precision** (KIVI's key insight): keys get more bits than values
  because keys drive attention weights and errors compound across all queries
- The asymmetric principle is the important part of KIVI; the axis choice is not

### Preset mapping (asymmetric K/V)
| Preset     | Key Type | Value Type | Effective bpw |
|------------|----------|------------|---------------|
| SAFE       | F16      | F16        | 16.0          |
| BALANCED   | Q8_0     | Q8_0       | 8.5           |
| AGGRESSIVE | Q8_0     | Q4_0       | 6.3           |
| ULTRA      | Q8_0     | Q3_K       | 5.7           |
| EXTREME    | Q4_0     | Q2_K       | 3.5           |

## References
- KIVI: arXiv 2402.02750
- TurboQuant: arXiv 2504.19874
- WHT rotation validation: see turboquant.c test suite (kurtosis measurements)
