package com.pocketagent.android.runtime

import android.util.Log
import com.pocketagent.nativebridge.KvCacheType
import org.json.JSONObject
import java.io.File

/**
 * Pre-flight memory estimation for model loading.
 *
 * When GGUF metadata is available (from the `.meta.json` sidecar):
 *   Total = (modelFileSize + kvCache + computeBuffer) × 1.1
 *
 * When GGUF metadata is NOT available:
 *   Total = modelFileSize × 1.2
 *
 * Formulas follow PocketPal's memoryEstimator.ts.
 */
object ModelMemoryEstimator {

    private const val LOG_TAG = "ModelMemoryEstimator"
    private const val METADATA_OVERHEAD_MULTIPLIER = 1.1
    private const val FALLBACK_OVERHEAD_MULTIPLIER = 1.2
    private const val AVAILABLE_MEMORY_SAFETY_RATIO = 0.80

    data class EstimationResult(
        val estimatedBytes: Long,
        val modelFileSizeBytes: Long,
        val kvCacheBytes: Long,
        val computeBufferBytes: Long,
        val usedGgufMetadata: Boolean,
        val availableMemoryBytes: Long?,
        val fitsInMemory: Boolean?,
    ) {
        val estimatedMb: Double get() = estimatedBytes.toDouble() / (1024.0 * 1024.0)
        val availableMemoryMb: Double? get() = availableMemoryBytes?.toDouble()?.div(1024.0 * 1024.0)
    }

    /**
     * Estimate memory required to load a model.
     *
     * @param modelFilePath absolute path to the .gguf file
     * @param nCtx context length to use (from RuntimeGenerationConfig)
     * @param kvCacheType KV cache quantization type
     * @param nUbatch micro-batch size for compute buffer estimation
     * @param availableMemoryBytes optional current available device memory in bytes
     */
    fun estimate(
        modelFilePath: String,
        nCtx: Int = 2048,
        kvCacheType: KvCacheType = KvCacheType.Q8_0,
        nUbatch: Int = 512,
        availableMemoryBytes: Long? = null,
    ): EstimationResult {
        val modelFile = File(modelFilePath)
        val modelFileSizeBytes = if (modelFile.exists()) modelFile.length() else 0L

        val metadataFile = File("$modelFilePath.meta.json")
        val gguf = readGgufMetadata(metadataFile)

        return if (gguf != null) {
            estimateWithMetadata(
                modelFileSizeBytes = modelFileSizeBytes,
                gguf = gguf,
                nCtx = nCtx,
                kvCacheType = kvCacheType,
                nUbatch = nUbatch,
                availableMemoryBytes = availableMemoryBytes,
            )
        } else {
            estimateFallback(
                modelFileSizeBytes = modelFileSizeBytes,
                availableMemoryBytes = availableMemoryBytes,
            )
        }
    }

    private fun estimateWithMetadata(
        modelFileSizeBytes: Long,
        gguf: GgufFields,
        nCtx: Int,
        kvCacheType: KvCacheType,
        nUbatch: Int,
        availableMemoryBytes: Long?,
    ): EstimationResult {
        val kvCacheBytes = calculateKvCacheBytes(gguf, nCtx, kvCacheType)
        val computeBufferBytes = calculateComputeBufferBytes(gguf, nUbatch)
        val baseMemory = modelFileSizeBytes + kvCacheBytes + computeBufferBytes
        val estimatedBytes = (baseMemory * METADATA_OVERHEAD_MULTIPLIER).toLong()

        val fits = availableMemoryBytes?.let { available ->
            estimatedBytes <= (available * AVAILABLE_MEMORY_SAFETY_RATIO).toLong()
        }

        Log.d(LOG_TAG, "ESTIMATE|model_mb=%.0f|kv_mb=%.0f|compute_mb=%.0f|total_mb=%.0f|avail_mb=%s|fits=%s".format(
            modelFileSizeBytes / MB,
            kvCacheBytes / MB,
            computeBufferBytes / MB,
            estimatedBytes / MB,
            availableMemoryBytes?.let { "%.0f".format(it / MB) } ?: "unknown",
            fits?.toString() ?: "unknown",
        ))

        return EstimationResult(
            estimatedBytes = estimatedBytes,
            modelFileSizeBytes = modelFileSizeBytes,
            kvCacheBytes = kvCacheBytes,
            computeBufferBytes = computeBufferBytes,
            usedGgufMetadata = true,
            availableMemoryBytes = availableMemoryBytes,
            fitsInMemory = fits,
        )
    }

    private fun estimateFallback(
        modelFileSizeBytes: Long,
        availableMemoryBytes: Long?,
    ): EstimationResult {
        val estimatedBytes = (modelFileSizeBytes * FALLBACK_OVERHEAD_MULTIPLIER).toLong()

        val fits = availableMemoryBytes?.let { available ->
            estimatedBytes <= (available * AVAILABLE_MEMORY_SAFETY_RATIO).toLong()
        }

        Log.d(LOG_TAG, "ESTIMATE_FALLBACK|model_mb=%.0f|total_mb=%.0f|avail_mb=%s|fits=%s".format(
            modelFileSizeBytes / MB,
            estimatedBytes / MB,
            availableMemoryBytes?.let { "%.0f".format(it / MB) } ?: "unknown",
            fits?.toString() ?: "unknown",
        ))

        return EstimationResult(
            estimatedBytes = estimatedBytes,
            modelFileSizeBytes = modelFileSizeBytes,
            kvCacheBytes = 0L,
            computeBufferBytes = 0L,
            usedGgufMetadata = false,
            availableMemoryBytes = availableMemoryBytes,
            fitsInMemory = fits,
        )
    }

    /**
     * KV cache: nLayers × nCtx × (embdHeadK × bytesPerK + embdHeadV × bytesPerV) × nHeadKv
     */
    private fun calculateKvCacheBytes(gguf: GgufFields, nCtx: Int, kvCacheType: KvCacheType): Long {
        val bytesPerElement = kvCacheTypeBytesPerElement(kvCacheType)
        val keyCacheSize = gguf.nLayers.toLong() * nCtx * gguf.embdHeadK * gguf.nHeadKv * bytesPerElement
        val valueCacheSize = gguf.nLayers.toLong() * nCtx * gguf.embdHeadV * gguf.nHeadKv * bytesPerElement
        return (keyCacheSize + valueCacheSize).toLong()
    }

    /**
     * Compute buffer: (nVocab + nEmbd) × nUbatch × 4 bytes
     */
    private fun calculateComputeBufferBytes(gguf: GgufFields, nUbatch: Int): Long {
        return (gguf.nVocab.toLong() + gguf.nEmbd.toLong()) * nUbatch * 4L
    }

    private fun kvCacheTypeBytesPerElement(type: KvCacheType): Double {
        return when (type) {
            KvCacheType.F16 -> 2.0
            KvCacheType.Q8_0 -> 1.0625   // 34/32
            KvCacheType.Q4_0 -> 0.5625   // 18/32
            KvCacheType.Q4_1 -> 0.625    // 20/32
            KvCacheType.Q5_0 -> 0.6875   // 22/32
            KvCacheType.Q5_1 -> 0.75     // 24/32
        }
    }

    private fun readGgufMetadata(metadataFile: File): GgufFields? {
        if (!metadataFile.exists()) return null
        return try {
            val root = JSONObject(metadataFile.readText())
            val gguf = root.optJSONObject("gguf") ?: return null
            val dims = gguf.optJSONObject("dimensions") ?: return null
            val attn = gguf.optJSONObject("attention") ?: return null
            val arch = gguf.optJSONObject("architecture")

            val nLayers = dims.optInt("blockCount", 0)
            val nEmbd = dims.optInt("embeddingSize", 0)
            val nHeadKv = attn.optInt("headCountKv", 0)
            val nVocab = arch?.optInt("vocabSize", 0) ?: 0
            // keyLength/valueLength in GGUF = per-head embedding dimensions
            val embdHeadK = attn.optInt("keyLength", 0)
            val embdHeadV = attn.optInt("valueLength", 0)

            val fields = GgufFields(
                nLayers = nLayers,
                nEmbd = nEmbd,
                nHeadKv = nHeadKv,
                nVocab = nVocab,
                embdHeadK = embdHeadK,
                embdHeadV = embdHeadV,
            )
            if (!fields.isValid()) {
                Log.w(LOG_TAG, "GGUF metadata has invalid/zero fields: $fields")
                null
            } else {
                fields
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read GGUF metadata from ${metadataFile.name}: ${e.message}")
            null
        }
    }

    private data class GgufFields(
        val nLayers: Int,
        val nEmbd: Int,
        val nHeadKv: Int,
        val nVocab: Int,
        val embdHeadK: Int,
        val embdHeadV: Int,
    ) {
        fun isValid(): Boolean = nLayers > 0 && nEmbd > 0 && nHeadKv > 0 &&
            nVocab > 0 && embdHeadK > 0 && embdHeadV > 0
    }

    private const val MB = 1024.0 * 1024.0
}
