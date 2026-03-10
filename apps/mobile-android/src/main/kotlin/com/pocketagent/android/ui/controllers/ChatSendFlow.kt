package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.StreamChatRequestV2

fun interface DeviceStateProvider {
    fun current(): DeviceState

    companion object {
        val DEFAULT = DeviceStateProvider {
            DeviceState(
                batteryPercent = 85,
                thermalLevel = 3,
                ramClassGb = 8,
            )
        }
    }
}

data class ResolvedPerformancePlan(
    val baseConfig: PerformanceRuntimeConfig,
    val effectiveConfig: PerformanceRuntimeConfig,
)

class ChatSendFlow(
    private val runtimeGenerationTimeoutMs: Long,
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning.DISABLED,
) {
    fun isRuntimeReadyForSend(runtime: RuntimeUiState): Boolean {
        return runtime.startupProbeState == StartupProbeState.READY &&
            runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
    }

    fun resolveRequestTimeoutMs(performanceConfig: PerformanceRuntimeConfig): Long {
        if (runtimeGenerationTimeoutMs > 0L) {
            return runtimeGenerationTimeoutMs
        }
        return performanceConfig.requestTimeoutMs
    }

    fun resolvePerformancePlan(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): ResolvedPerformancePlan {
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val safeBaseConfig = enforceGpuSafeBatch(
            PerformanceRuntimeConfig.forProfile(
                profile = profile,
                availableCpuThreads = cpuThreads,
                gpuEnabled = gpuEnabled,
                gpuLayers = gpuLayers.coerceAtLeast(0),
            ),
        )
        val tunedConfig = enforceGpuSafeBatch(
            runtimeTuning.applyRecommendedConfig(
                modelIdHint = modelIdHint,
                baseConfig = safeBaseConfig,
                gpuQualifiedLayers = gpuLayers.coerceAtLeast(0),
            ),
        )
        return ResolvedPerformancePlan(
            baseConfig = safeBaseConfig,
            effectiveConfig = tunedConfig,
        )
    }

    fun resolvePerformanceConfig(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): PerformanceRuntimeConfig {
        return resolvePerformancePlan(
            profile = profile,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            modelIdHint = modelIdHint,
        ).effectiveConfig
    }

    private fun enforceGpuSafeBatch(config: PerformanceRuntimeConfig): PerformanceRuntimeConfig {
        if (!config.gpuEnabled || config.gpuLayers <= 0) {
            return config
        }
        return config.copy(
            nBatch = minOf(config.nBatch, GPU_SAFE_BATCH),
            nUbatch = minOf(config.nUbatch, GPU_SAFE_BATCH),
        )
    }

    fun buildStreamChatRequest(
        sessionId: String,
        requestId: String,
        messages: List<InteractionMessage>,
        taskTypeHint: String,
        performanceConfig: PerformanceRuntimeConfig,
        requestTimeoutMs: Long,
        keepAlivePreference: RuntimeKeepAlivePreference,
        previousResponseId: String? = null,
    ): StreamChatRequestV2 {
        return StreamChatRequestV2(
            sessionId = SessionId(sessionId),
            requestId = requestId,
            messages = messages,
            taskType = resolveTaskType(taskTypeHint),
            maxTokens = resolveMaxTokens(taskTypeHint, performanceConfig),
            deviceState = deviceStateProvider.current(),
            requestTimeoutMs = requestTimeoutMs,
            previousResponseId = previousResponseId,
            performanceConfig = performanceConfig,
            residencyPolicy = resolveResidencyPolicy(keepAlivePreference),
        )
    }

    private fun resolveResidencyPolicy(preference: RuntimeKeepAlivePreference): ModelResidencyPolicy {
        return when (preference) {
            RuntimeKeepAlivePreference.AUTO -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS,
                warmupOnStartup = true,
                adaptiveIdleTtl = true,
            )
            RuntimeKeepAlivePreference.ALWAYS -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = ALWAYS_KEEP_ALIVE_TTL_MS,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            RuntimeKeepAlivePreference.ONE_MINUTE -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = 60_000L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            RuntimeKeepAlivePreference.FIVE_MINUTES -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = 5 * 60_000L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            RuntimeKeepAlivePreference.FIFTEEN_MINUTES -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = 15 * 60_000L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
            RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY -> ModelResidencyPolicy(
                keepLoadedWhileAppForeground = false,
                idleUnloadTtlMs = 1L,
                warmupOnStartup = true,
                adaptiveIdleTtl = false,
            )
        }
    }

    private fun resolveTaskType(prompt: String): String {
        return if (prompt.length >= LONG_PROMPT_LENGTH) "long_text" else "short_text"
    }

    private fun resolveMaxTokens(prompt: String, performanceConfig: PerformanceRuntimeConfig): Int {
        val promptBudget = if (prompt.length >= LONG_PROMPT_LENGTH) {
            LONG_PROMPT_MAX_TOKENS
        } else {
            SHORT_PROMPT_MAX_TOKENS
        }
        return minOf(promptBudget, performanceConfig.maxTokensDefault.coerceAtLeast(16))
    }

    private companion object {
        private const val LONG_PROMPT_LENGTH = 160
        private const val SHORT_PROMPT_MAX_TOKENS = 32
        private const val LONG_PROMPT_MAX_TOKENS = 96
        private const val GPU_SAFE_BATCH = 256
        private const val ALWAYS_KEEP_ALIVE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
