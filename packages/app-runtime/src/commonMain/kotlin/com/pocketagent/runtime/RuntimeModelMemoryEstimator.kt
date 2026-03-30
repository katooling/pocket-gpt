package com.pocketagent.runtime

import com.pocketagent.nativebridge.KvCacheMethod
import com.pocketagent.nativebridge.KvCacheMethodPreset
import com.pocketagent.nativebridge.ModelRuntimeMetadata

data class RuntimeMemoryEstimate(
    val estimatedBytes: Long,
    val modelWeightsBytes: Long,
    val kvCacheBytes: Long,
    val computeBufferBytes: Long,
    val usedModelMetadata: Boolean,
    val fitsInMemory: Boolean? = null,
) {
    val estimatedMb: Double get() = estimatedBytes.toDouble() / 1_048_576.0
}

object RuntimeModelMemoryEstimator {
    fun estimate(
        modelFileSizeBytes: Long,
        metadata: ModelRuntimeMetadata?,
        nCtx: Int,
        kvCacheMethod: KvCacheMethod,
        kvCacheMethodPreset: KvCacheMethodPreset,
        nUbatch: Int,
        availableMemoryMb: Double? = null,
    ): RuntimeMemoryEstimate {
        val validMetadata: ModelRuntimeMetadata = metadata?.takeIf { candidate ->
            (candidate.layerCount ?: 0) > 0 &&
                (candidate.embeddingSize ?: 0) > 0 &&
                (candidate.headCountKv ?: 0) > 0 &&
                (candidate.keyLength ?: 0) > 0 &&
                (candidate.valueLength ?: 0) > 0 &&
                (candidate.vocabSize ?: 0) > 0
        } ?: return fallbackEstimate(
            modelFileSizeBytes = modelFileSizeBytes,
            availableMemoryMb = availableMemoryMb,
        )

        val effectiveCtx = minOf(
            nCtx.coerceAtLeast(1),
            validMetadata.slidingWindow?.coerceAtLeast(1) ?: nCtx.coerceAtLeast(1),
        )
        val layerCount = validMetadata.layerCount ?: 0
        val headCountKv = validMetadata.headCountKv ?: 0
        val keyLength = validMetadata.keyLength ?: 0
        val valueLength = validMetadata.valueLength ?: 0
        val vocabSize = validMetadata.vocabSize ?: 0
        val embeddingSize = validMetadata.embeddingSize ?: 0

        // Asymmetric K/V: keys get more precision than values (KIVI principle).
        // AGGRESSIVE: keys Q8_0, values Q4_0. BALANCED: both Q8_0. SAFE: both F16.
        // Small models (<2GB) get clamped: ULTRA->BALANCED, EXTREME->AGGRESSIVE (mirrors C++).
        val (effectivePresetK, effectivePresetV) = effectiveKvPresets(kvCacheMethodPreset, modelFileSizeBytes)
        val kvCacheBytes = (
            layerCount.toLong() *
                effectiveCtx.toLong() *
                headCountKv.toLong() *
                (
                    keyLength.toDouble() * bytesPerElementK(kvCacheMethod, effectivePresetK) +
                        valueLength.toDouble() * bytesPerElementV(kvCacheMethod, effectivePresetV)
                    )
            ).toLong()
        val computeBufferBytes = (vocabSize.toLong() + embeddingSize.toLong()) * nUbatch.coerceAtLeast(1).toLong() * 4L
        // TurboQuant WHT rotation overhead: one sign vector (float[head_dim]) per layer.
        // Only allocated when quantized KV is active (BALANCED or AGGRESSIVE preset).
        val rotationOverheadBytes = when (kvCacheMethodPreset) {
            KvCacheMethodPreset.SAFE -> 0L
            else -> layerCount.toLong() * keyLength.toLong() * 4L
        }
        val estimatedBytes = ((modelFileSizeBytes + kvCacheBytes + computeBufferBytes + rotationOverheadBytes).toDouble() * METADATA_OVERHEAD_MULTIPLIER)
            .toLong()
        return RuntimeMemoryEstimate(
            estimatedBytes = estimatedBytes,
            modelWeightsBytes = modelFileSizeBytes,
            kvCacheBytes = kvCacheBytes,
            computeBufferBytes = computeBufferBytes,
            usedModelMetadata = true,
            fitsInMemory = availableMemoryMb?.let { estimatedBytes.toDouble() / BYTES_PER_MB <= it * SAFETY_MARGIN },
        )
    }

    private fun fallbackEstimate(
        modelFileSizeBytes: Long,
        availableMemoryMb: Double?,
    ): RuntimeMemoryEstimate {
        val estimatedBytes = (modelFileSizeBytes.toDouble() * FALLBACK_OVERHEAD_MULTIPLIER).toLong()
        return RuntimeMemoryEstimate(
            estimatedBytes = estimatedBytes,
            modelWeightsBytes = modelFileSizeBytes,
            kvCacheBytes = 0L,
            computeBufferBytes = 0L,
            usedModelMetadata = false,
            fitsInMemory = availableMemoryMb?.let { estimatedBytes.toDouble() / BYTES_PER_MB <= it * SAFETY_MARGIN },
        )
    }

    // Asymmetric K/V bytes-per-element following KIVI principle:
    // Keys need more precision than values since they drive attention weights.
    private fun bytesPerElementK(method: KvCacheMethod, preset: KvCacheMethodPreset): Double {
        return when (method) {
            KvCacheMethod.AUTO,
            KvCacheMethod.TURBOQUANT,
            -> when (preset) {
                KvCacheMethodPreset.SAFE -> 2.0          // F16
                KvCacheMethodPreset.BALANCED -> 1.0625   // Q8_0
                KvCacheMethodPreset.AGGRESSIVE -> 1.0625 // Q8_0 (keys get more bits)
                KvCacheMethodPreset.ULTRA -> 1.0625      // Q8_0 (keys still Q8_0)
                KvCacheMethodPreset.EXTREME -> 0.5625    // Q4_0 (keys finally reduced)
            }
        }
    }

    private fun bytesPerElementV(method: KvCacheMethod, preset: KvCacheMethodPreset): Double {
        return when (method) {
            KvCacheMethod.AUTO,
            KvCacheMethod.TURBOQUANT,
            -> when (preset) {
                KvCacheMethodPreset.SAFE -> 2.0          // F16
                KvCacheMethodPreset.BALANCED -> 1.0625   // Q8_0
                KvCacheMethodPreset.AGGRESSIVE -> 0.5625 // Q4_0
                KvCacheMethodPreset.ULTRA -> 0.4297      // Q3_K (~3.4375 bpw)
                KvCacheMethodPreset.EXTREME -> 0.3281    // Q2_K (~2.625 bpw)
            }
        }
    }

    private const val SMALL_MODEL_THRESHOLD_BYTES = 2L * 1024 * 1024 * 1024

    // Mirror the C++ resolve_turboquant_kv_types small-model safety clamp.
    // Returns effective (K preset, V preset) after small-model demotion.
    private fun effectiveKvPresets(
        preset: KvCacheMethodPreset,
        modelFileSizeBytes: Long,
    ): Pair<KvCacheMethodPreset, KvCacheMethodPreset> {
        val smallModel = modelFileSizeBytes in 1 until SMALL_MODEL_THRESHOLD_BYTES
        if (!smallModel) return preset to preset
        return when (preset) {
            KvCacheMethodPreset.EXTREME -> KvCacheMethodPreset.BALANCED to KvCacheMethodPreset.AGGRESSIVE
            KvCacheMethodPreset.ULTRA -> KvCacheMethodPreset.BALANCED to KvCacheMethodPreset.BALANCED
            else -> preset to preset
        }
    }

    private const val METADATA_OVERHEAD_MULTIPLIER = 1.1
    private const val FALLBACK_OVERHEAD_MULTIPLIER = 1.2
    private const val SAFETY_MARGIN = 0.85
    private const val BYTES_PER_MB = 1024.0 * 1024.0
}
