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
import com.pocketagent.runtime.StreamUserMessageRequest
import com.pocketagent.runtime.InteractionMessage

data class ToolIntent(
    val name: String,
    val jsonArgs: String,
)

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
    private val legacyToolIntentParserEnabled: Boolean = false,
) {
    fun parseToolIntent(prompt: String): ToolIntent? {
        if (!legacyToolIntentParserEnabled) {
            return null
        }
        val normalized = prompt.trim()
        val lowercase = normalized.lowercase()
        val calcPrefix = listOf("calculate ", "calc ", "what is ")
            .firstOrNull { lowercase.startsWith(it) }
        if (calcPrefix != null) {
            val expression = normalized.drop(calcPrefix.length).trim()
            if (expression.matches(Regex("[0-9+\\-*/().\\s]{1,64}")) && expression.any { it.isDigit() }) {
                return ToolIntent(name = "calculator", jsonArgs = """{"expression":"$expression"}""")
            }
        }
        if (lowercase == "time" || lowercase == "date" || lowercase.contains("what time")) {
            return ToolIntent(name = "date_time", jsonArgs = "{}")
        }
        if (lowercase.startsWith("search ")) {
            val query = normalized.drop("search ".length).trim()
            if (query.isNotEmpty()) {
                return ToolIntent(name = "local_search", jsonArgs = """{"query":"${escapeJson(query)}"}""")
            }
        }
        if (lowercase.startsWith("find notes ")) {
            val query = normalized.drop("find notes ".length).trim()
            if (query.isNotEmpty()) {
                return ToolIntent(name = "notes_lookup", jsonArgs = """{"query":"${escapeJson(query)}"}""")
            }
        }
        if (lowercase.startsWith("remind me to ")) {
            val title = normalized.drop("remind me to ".length).trim()
            if (title.isNotEmpty()) {
                return ToolIntent(name = "reminder_create", jsonArgs = """{"title":"${escapeJson(title)}"}""")
            }
        }
        return null
    }

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

    fun buildStreamRequest(
        sessionId: String,
        requestId: String,
        prompt: String,
        performanceConfig: PerformanceRuntimeConfig,
        requestTimeoutMs: Long,
    ): StreamUserMessageRequest {
        return StreamUserMessageRequest(
            sessionId = SessionId(sessionId),
            requestId = requestId,
            userText = prompt,
            taskType = resolveTaskType(prompt),
            maxTokens = resolveMaxTokens(prompt, performanceConfig),
            deviceState = deviceStateProvider.current(),
            requestTimeoutMs = requestTimeoutMs,
            performanceConfig = performanceConfig,
            residencyPolicy = ModelResidencyPolicy(
                keepLoadedWhileAppForeground = true,
                idleUnloadTtlMs = IDLE_MODEL_UNLOAD_TTL_MS,
                warmupOnStartup = true,
            ),
        )
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
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
