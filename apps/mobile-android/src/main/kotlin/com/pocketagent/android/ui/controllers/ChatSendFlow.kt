package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatKeepAlivePreference
import com.pocketagent.runtime.ChatStreamCommand
import com.pocketagent.runtime.ChatStreamRequestPlanner
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.ResolvedPerformancePlan
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

class ChatSendFlow(
    private val runtimeGenerationTimeoutMs: Long,
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
    private val runtimeTuning: RuntimeTuning = RuntimeTuning.DISABLED,
) {
    private val planner = ChatStreamRequestPlanner(
        runtimeGenerationTimeoutMs = runtimeGenerationTimeoutMs,
        recommendedConfig = { modelIdHint, baseConfig, gpuQualifiedLayers ->
            runtimeTuning.applyRecommendedConfig(
                modelIdHint = modelIdHint,
                baseConfig = baseConfig,
                gpuQualifiedLayers = gpuQualifiedLayers,
            )
        },
    )

    fun isRuntimeReadyForSend(runtime: RuntimeUiState): Boolean {
        return runtime.startupProbeState == StartupProbeState.READY &&
            runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
    }

    fun resolveRequestTimeoutMs(performanceConfig: PerformanceRuntimeConfig): Long {
        return planner.resolveRequestTimeoutMs(performanceConfig)
    }

    fun resolvePerformancePlan(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): ResolvedPerformancePlan {
        return planner.resolvePerformancePlan(
            profile = profile,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            modelIdHint = modelIdHint,
        )
    }

    fun resolvePerformanceConfig(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuLayers: Int = 32,
        modelIdHint: String? = null,
    ): PerformanceRuntimeConfig {
        return planner.resolvePerformanceConfig(
            profile = profile,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            modelIdHint = modelIdHint,
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
        return planner.prepare(
            ChatStreamCommand(
                sessionId = SessionId(sessionId),
                requestId = requestId,
                messages = messages,
                promptHint = taskTypeHint,
                deviceState = deviceStateProvider.current(),
                performanceProfile = performanceConfig.profile,
                gpuEnabled = performanceConfig.gpuEnabled,
                gpuQualifiedLayers = performanceConfig.gpuLayers,
                previousResponseId = previousResponseId,
                keepAlivePreference = keepAlivePreference.toRuntimePreference(),
                requestTimeoutOverrideMs = requestTimeoutMs,
            ),
        ).runtimeRequest
    }

    private fun RuntimeKeepAlivePreference.toRuntimePreference(): ChatKeepAlivePreference {
        return when (this) {
            RuntimeKeepAlivePreference.AUTO -> ChatKeepAlivePreference.AUTO
            RuntimeKeepAlivePreference.ALWAYS -> ChatKeepAlivePreference.ALWAYS
            RuntimeKeepAlivePreference.ONE_MINUTE -> ChatKeepAlivePreference.ONE_MINUTE
            RuntimeKeepAlivePreference.FIVE_MINUTES -> ChatKeepAlivePreference.FIVE_MINUTES
            RuntimeKeepAlivePreference.FIFTEEN_MINUTES -> ChatKeepAlivePreference.FIFTEEN_MINUTES
            RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY -> ChatKeepAlivePreference.UNLOAD_IMMEDIATELY
        }
    }

}
