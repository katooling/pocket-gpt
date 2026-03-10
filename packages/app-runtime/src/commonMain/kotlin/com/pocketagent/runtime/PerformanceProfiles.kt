package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog

enum class RuntimePerformanceProfile {
    BATTERY,
    BALANCED,
    FAST,
}

data class PerformanceRuntimeConfig(
    val profile: RuntimePerformanceProfile = RuntimePerformanceProfile.BALANCED,
    val requestTimeoutMs: Long,
    val maxTokensDefault: Int,
    val nThreads: Int,
    val nThreadsBatch: Int,
    val nBatch: Int,
    val nUbatch: Int,
    val nCtx: Int,
    val gpuEnabled: Boolean,
    val gpuLayers: Int,
    val quantizedKvCache: Boolean,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val speculativeEnabled: Boolean,
    val speculativeDraftModelId: String?,
    val speculativeMaxDraftTokens: Int,
    val speculativeMinDraftTokens: Int,
) {
    companion object {
        private const val MIN_THREADS = 1
        private const val MAX_THREADS = 16
        private const val MIN_BATCH = 32
        private const val MAX_BATCH = 2048

        fun default(): PerformanceRuntimeConfig =
            forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 4,
                gpuEnabled = false,
            )

        fun forProfile(
            profile: RuntimePerformanceProfile,
            availableCpuThreads: Int,
            gpuEnabled: Boolean,
            gpuLayers: Int = DEFAULT_GPU_LAYERS,
        ): PerformanceRuntimeConfig {
            val cpu = availableCpuThreads.coerceAtLeast(1)
            val profilePreset = when (profile) {
                RuntimePerformanceProfile.BATTERY -> Preset(
                    timeoutMs = 480_000L,
                    maxTokensDefault = 64,
                    threads = (cpu / 2).coerceAtLeast(2).coerceAtMost(4),
                    batch = 256,
                    ubatch = 256,
                    nCtx = 1024,
                )

                RuntimePerformanceProfile.BALANCED -> Preset(
                    timeoutMs = 300_000L,
                    maxTokensDefault = 96,
                    threads = cpu.coerceAtMost(6),
                    batch = 512,
                    ubatch = 512,
                    nCtx = 2048,
                )

                RuntimePerformanceProfile.FAST -> Preset(
                    timeoutMs = 360_000L,
                    maxTokensDefault = 128,
                    threads = cpu.coerceAtMost(8),
                    batch = 768,
                    ubatch = 768,
                    nCtx = 2048,
                )
            }

            return PerformanceRuntimeConfig(
                profile = profile,
                requestTimeoutMs = profilePreset.timeoutMs,
                maxTokensDefault = profilePreset.maxTokensDefault,
                nThreads = profilePreset.threads.coerceIn(MIN_THREADS, MAX_THREADS),
                nThreadsBatch = profilePreset.threads.coerceIn(MIN_THREADS, MAX_THREADS),
                nBatch = profilePreset.batch.coerceIn(MIN_BATCH, MAX_BATCH),
                nUbatch = profilePreset.ubatch.coerceIn(MIN_BATCH, MAX_BATCH),
                nCtx = profilePreset.nCtx,
                gpuEnabled = gpuEnabled,
                gpuLayers = gpuLayers.coerceAtLeast(0),
                quantizedKvCache = true,
                temperature = if (profile == RuntimePerformanceProfile.BATTERY) 0.6f else 0.7f,
                topK = if (profile == RuntimePerformanceProfile.BATTERY) 24 else 40,
                topP = if (profile == RuntimePerformanceProfile.BATTERY) 0.92f else 0.95f,
                speculativeEnabled = profile != RuntimePerformanceProfile.BATTERY,
                speculativeDraftModelId = ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M,
                speculativeMaxDraftTokens = if (profile == RuntimePerformanceProfile.FAST) 8 else 6,
                speculativeMinDraftTokens = 2,
            )
        }

        private const val DEFAULT_GPU_LAYERS: Int = 32
    }

    private data class Preset(
        val timeoutMs: Long,
        val maxTokensDefault: Int,
        val threads: Int,
        val batch: Int,
        val ubatch: Int,
        val nCtx: Int,
    )

    fun withThermalAdaptiveOverrides(deviceState: DeviceState): PerformanceRuntimeConfig {
        val severePressure = deviceState.thermalLevel >= 7 || deviceState.batteryPercent < 20
        val moderatePressure = deviceState.thermalLevel >= 5 || deviceState.batteryPercent < 35
        return when {
            severePressure -> copy(
                nThreads = (nThreads / 2).coerceAtLeast(2),
                nThreadsBatch = (nThreadsBatch / 2).coerceAtLeast(2),
                nBatch = (nBatch / 2).coerceAtLeast(256),
                nUbatch = (nUbatch / 2).coerceAtLeast(256),
                nCtx = nCtx.coerceAtMost(1024),
                gpuLayers = (gpuLayers / 2).coerceAtLeast(0),
                speculativeEnabled = false,
            )
            moderatePressure -> copy(
                nThreads = (nThreads * 3 / 4).coerceAtLeast(2),
                nThreadsBatch = (nThreadsBatch * 3 / 4).coerceAtLeast(2),
                nBatch = (nBatch * 3 / 4).coerceAtLeast(384),
                nUbatch = (nUbatch * 3 / 4).coerceAtLeast(384),
                nCtx = nCtx.coerceAtMost(1536),
                gpuLayers = (gpuLayers * 3 / 4).coerceAtLeast(0),
                speculativeEnabled = speculativeEnabled && deviceState.thermalLevel <= 5,
            )
            else -> this
        }
    }
}

data class ModelResidencyPolicy(
    val keepLoadedWhileAppForeground: Boolean = true,
    val idleUnloadTtlMs: Long = 10 * 60 * 1000L,
    val warmupOnStartup: Boolean = true,
)
