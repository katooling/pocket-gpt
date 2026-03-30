# KIVI Assessment: Per-Channel K / Per-Token V After WHT Rotation

## Summary

KIVI (arXiv 2402.02750) recommends quantizing keys per-channel and values per-token
because key channels have heterogeneous variance while value tokens are more uniform.

**After WHT rotation, this distinction is unnecessary.** The Walsh-Hadamard Transform
makes all coordinates approximately i.i.d. Gaussian, eliminating the per-channel variance
differences that motivate KIVI's axis choice.

## Analysis

### Pre-rotation (KIVI's assumption)
- Key channels have widely varying magnitudes (some channels carry 10-100x more info)
- Per-channel quantization captures these differences with per-channel scale factors
- Value tokens are more uniform in magnitude across the sequence dimension

### Post-rotation (our implementation)
- WHT rotation normalizes all key dimensions to approximately equal variance
- Empirically verified: kurtosis drops from ~900 to ~2.9 after rotation
- Per-channel scale factors provide no benefit when all channels have the same distribution
- Standard block-based quantization (groups of 32 elements) is already near-optimal

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
