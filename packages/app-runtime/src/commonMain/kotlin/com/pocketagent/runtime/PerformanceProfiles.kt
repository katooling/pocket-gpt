package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.KvCacheType

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
    val kvCacheType: KvCacheType,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val speculativeEnabled: Boolean,
    val speculativeDraftModelId: String?,
    val speculativeMaxDraftTokens: Int,
    val speculativeMinDraftTokens: Int,
    val speculativeDraftGpuLayers: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    val nKeep: Int,
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
            val profileAllowsGpu = profile != RuntimePerformanceProfile.BATTERY
            val effectiveGpuEnabled = gpuEnabled && profileAllowsGpu
            val effectiveGpuLayers = if (effectiveGpuEnabled) gpuLayers.coerceAtLeast(0) else 0
            val profilePreset = when (profile) {
                RuntimePerformanceProfile.BATTERY -> Preset(
                    timeoutMs = 900_000L,
                    maxTokensDefault = 128,
                    threads = (cpu / 2).coerceAtLeast(2).coerceAtMost(4),
                    batch = 256,
                    ubatch = 256,
                    nCtx = 1024,
                )

                RuntimePerformanceProfile.BALANCED -> Preset(
                    timeoutMs = 600_000L,
                    maxTokensDefault = 256,
                    threads = cpu.coerceAtMost(6),
                    batch = 512,
                    ubatch = 512,
                    nCtx = 2048,
                )

                RuntimePerformanceProfile.FAST -> Preset(
                    timeoutMs = 600_000L,
                    maxTokensDefault = 384,
                    threads = cpu.coerceAtMost(8),
                    batch = 768,
                    ubatch = 768,
                    nCtx = 4096,
                )
            }

            return PerformanceRuntimeConfig(
                profile = profile,
                requestTimeoutMs = profilePreset.timeoutMs,
                maxTokensDefault = profilePreset.maxTokensDefault,
                nThreads = profilePreset.threads.coerceIn(MIN_THREADS, MAX_THREADS),
                // Prefill is a burst operation that benefits from more parallelism
                // than steady-state generation.  Use 1.5x the generation thread count
                // so prompt processing saturates the available performance cores.
                nThreadsBatch = (profilePreset.threads * 3 / 2).coerceIn(MIN_THREADS, MAX_THREADS),
                nBatch = profilePreset.batch.coerceIn(MIN_BATCH, MAX_BATCH),
                nUbatch = profilePreset.ubatch.coerceIn(MIN_BATCH, MAX_BATCH),
                nCtx = profilePreset.nCtx,
                gpuEnabled = effectiveGpuEnabled,
                gpuLayers = effectiveGpuLayers,
                kvCacheType = KvCacheType.Q8_0,
                temperature = if (profile == RuntimePerformanceProfile.BATTERY) 0.6f else 0.7f,
                topK = if (profile == RuntimePerformanceProfile.BATTERY) 24 else 40,
                topP = if (profile == RuntimePerformanceProfile.BATTERY) 0.92f else 0.95f,
                speculativeEnabled = profile != RuntimePerformanceProfile.BATTERY,
                speculativeDraftModelId = ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M,
                speculativeMaxDraftTokens = if (profile == RuntimePerformanceProfile.FAST) 8 else 6,
                speculativeMinDraftTokens = 2,
                speculativeDraftGpuLayers = defaultDraftGpuLayers(
                    profile = profile,
                    gpuEnabled = effectiveGpuEnabled,
                    gpuLayers = effectiveGpuLayers,
                ),
                useMmap = true,
                useMlock = false,
                nKeep = if (profile == RuntimePerformanceProfile.FAST) 256 else 128,
            )
        }

        private fun defaultDraftGpuLayers(
            profile: RuntimePerformanceProfile,
            gpuEnabled: Boolean,
            gpuLayers: Int,
        ): Int {
            if (!gpuEnabled || profile == RuntimePerformanceProfile.BATTERY || gpuLayers <= 0) {
                return 0
            }
            return when (profile) {
                RuntimePerformanceProfile.BATTERY -> 0
                RuntimePerformanceProfile.BALANCED -> (gpuLayers / 16).coerceIn(0, 2)
                RuntimePerformanceProfile.FAST -> (gpuLayers / 8).coerceIn(0, 4)
            }
        }

        // Conservative starting point for GPU layers. The GPU probe will
        // determine the actual safe maximum and RuntimeTuningStore will clamp
        // to that value. 16 is a safer default for OpenCL (Adreno max allocation
        // is typically 1024 MB) while still providing meaningful acceleration.
        private const val DEFAULT_GPU_LAYERS: Int = 16
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
                speculativeDraftGpuLayers = 0,
            )
            moderatePressure -> copy(
                nThreads = (nThreads * 3 / 4).coerceAtLeast(2),
                nThreadsBatch = (nThreadsBatch * 3 / 4).coerceAtLeast(2),
                nBatch = (nBatch * 3 / 4).coerceAtLeast(384),
                nUbatch = (nUbatch * 3 / 4).coerceAtLeast(384),
                nCtx = nCtx.coerceAtMost(1536),
                gpuLayers = (gpuLayers * 3 / 4).coerceAtLeast(0),
                speculativeEnabled = speculativeEnabled && deviceState.thermalLevel <= 5,
                speculativeDraftGpuLayers = (speculativeDraftGpuLayers * 3 / 4).coerceAtLeast(0),
            )
            else -> this
        }
    }
}

const val DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS: Long = 15 * 60 * 1000L

data class ModelResidencyPolicy(
    val keepLoadedWhileAppForeground: Boolean = true,
    val idleUnloadTtlMs: Long = DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS,
    val warmupOnStartup: Boolean = true,
    val adaptiveIdleTtl: Boolean = true,
)
