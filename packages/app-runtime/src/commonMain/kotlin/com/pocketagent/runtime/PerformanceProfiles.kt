package com.pocketagent.runtime

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
    val gpuEnabled: Boolean,
    val gpuLayers: Int,
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
                )

                RuntimePerformanceProfile.BALANCED -> Preset(
                    timeoutMs = 300_000L,
                    maxTokensDefault = 96,
                    threads = cpu.coerceAtMost(6),
                    batch = 512,
                    ubatch = 512,
                )

                RuntimePerformanceProfile.FAST -> Preset(
                    timeoutMs = 360_000L,
                    maxTokensDefault = 128,
                    threads = cpu.coerceAtMost(8),
                    batch = 768,
                    ubatch = 768,
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
                gpuEnabled = gpuEnabled,
                gpuLayers = gpuLayers.coerceAtLeast(0),
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
    )
}

data class ModelResidencyPolicy(
    val keepLoadedWhileAppForeground: Boolean = true,
    val idleUnloadTtlMs: Long = 10 * 60 * 1000L,
    val warmupOnStartup: Boolean = true,
)
