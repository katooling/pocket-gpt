package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.InteractionMessage

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

class ChatSendFlow(
    private val runtimeGenerationTimeoutMs: Long,
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
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

    fun resolvePerformanceConfig(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
    ): PerformanceRuntimeConfig {
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return PerformanceRuntimeConfig.forProfile(
            profile = profile,
            availableCpuThreads = cpuThreads,
            gpuEnabled = gpuEnabled,
        )
    }

    fun buildStreamChatRequest(
        sessionId: String,
        requestId: String,
        messages: List<InteractionMessage>,
        taskTypeHint: String,
        performanceConfig: PerformanceRuntimeConfig,
        requestTimeoutMs: Long,
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
            residencyPolicy = ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = IDLE_MODEL_UNLOAD_TTL_MS,
                warmupOnStartup = true,
            ),
        )
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
        private const val IDLE_MODEL_UNLOAD_TTL_MS = 10 * 60 * 1000L
    }
}
