package com.pocketagent.runtime

import kotlin.math.max

/**
 * Tracks device memory budget to enable intelligent model compatibility checks
 * and prevent OOM crashes on lower-end devices.
 *
 * Two key metrics:
 * - [availableMemoryCeilingMb]: highest observed free memory after a model release,
 *   representing the realistic upper bound of memory available for model loading.
 * - [largestSuccessfulLoadMb]: peak RSS observed during a successful model load/generation,
 *   used to estimate whether a given model will fit in memory.
 */
class MemoryBudgetTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    @Volatile
    var availableMemoryCeilingMb: Double = 0.0
        private set

    @Volatile
    var largestSuccessfulLoadMb: Double = 0.0
        private set

    @Volatile
    var lastUpdatedAtEpochMs: Long = 0L
        private set

    private var successfulLoadHistory: MutableList<LoadRecord> = mutableListOf()

    /**
     * Record available device memory after a model has been released.
     * The highest value observed becomes the ceiling.
     */
    fun recordAvailableMemoryAfterRelease(availableMb: Double) {
        if (availableMb <= 0.0) return
        synchronized(lock) {
            availableMemoryCeilingMb = max(availableMemoryCeilingMb, availableMb)
            lastUpdatedAtEpochMs = nowMs()
        }
    }

    /**
     * Record peak RSS from a successful model load/generation run.
     */
    fun recordSuccessfulLoad(modelId: String, peakRssMb: Double) {
        if (peakRssMb <= 0.0) return
        synchronized(lock) {
            largestSuccessfulLoadMb = max(largestSuccessfulLoadMb, peakRssMb)
            successfulLoadHistory.add(
                LoadRecord(
                    modelId = modelId,
                    peakRssMb = peakRssMb,
                    timestampMs = nowMs(),
                ),
            )
            if (successfulLoadHistory.size > MAX_HISTORY) {
                successfulLoadHistory = successfulLoadHistory
                    .takeLast(MAX_HISTORY)
                    .toMutableList()
            }
            lastUpdatedAtEpochMs = nowMs()
        }
    }

    /**
     * Estimate whether a model with the given file size can fit in available memory.
     * Uses a multiplier to account for runtime overhead (KV cache, scratch buffers, etc.)
     * beyond the raw model file size.
     *
     * @param modelFileSizeMb size of the GGUF model file in MB
     * @param overheadMultiplier estimated ratio of runtime memory to file size (default 1.2)
     * @return true if the model is likely to fit, false if it would likely OOM,
     *         null if there's insufficient data to make a determination
     */
    fun canFitModel(modelFileSizeMb: Double, overheadMultiplier: Double = DEFAULT_OVERHEAD_MULTIPLIER): Boolean? {
        val ceiling = availableMemoryCeilingMb
        if (ceiling <= 0.0) return null
        val estimatedRuntimeMb = modelFileSizeMb * overheadMultiplier
        return estimatedRuntimeMb <= ceiling * SAFETY_MARGIN
    }

    /**
     * Get peak RSS for a specific model from recent load history.
     */
    fun peakRssForModel(modelId: String): Double? {
        synchronized(lock) {
            return successfulLoadHistory
                .filter { it.modelId == modelId }
                .maxByOrNull { it.timestampMs }
                ?.peakRssMb
        }
    }

    fun diagnosticsLine(): String {
        return buildString {
            append("MEMORY_BUDGET")
            append("|ceiling_mb=")
            append(if (availableMemoryCeilingMb > 0) "%.0f".format(availableMemoryCeilingMb) else "unknown")
            append("|largest_load_mb=")
            append(if (largestSuccessfulLoadMb > 0) "%.0f".format(largestSuccessfulLoadMb) else "unknown")
            append("|history_count=")
            synchronized(lock) { append(successfulLoadHistory.size) }
        }
    }

    private data class LoadRecord(
        val modelId: String,
        val peakRssMb: Double,
        val timestampMs: Long,
    )

    private companion object {
        const val MAX_HISTORY = 32
        const val DEFAULT_OVERHEAD_MULTIPLIER = 1.2
        const val SAFETY_MARGIN = 0.85
    }
}
