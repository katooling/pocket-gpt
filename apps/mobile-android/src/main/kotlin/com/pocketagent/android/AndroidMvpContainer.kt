package com.pocketagent.android

import com.pocketagent.core.ConversationModule
import com.pocketagent.core.DefaultPolicyModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.InMemoryObservabilityModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.AdaptiveRoutingPolicy
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelArtifact
import com.pocketagent.inference.ModelArtifactManager
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.inference.SmokeImageInputModule
import com.pocketagent.memory.InMemoryMemoryModule
import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolModule

data class ChatResponse(
    val sessionId: SessionId,
    val modelId: String,
    val text: String,
    val firstTokenLatencyMs: Long,
    val totalLatencyMs: Long,
)

class AndroidMvpContainer(
    private val conversationModule: ConversationModule = InMemoryConversationModule(),
    private val inferenceModule: InferenceModule = AndroidLlamaCppInferenceModule(),
    private val routingModule: RoutingModule = AdaptiveRoutingPolicy(),
    private val policyModule: PolicyModule = DefaultPolicyModule(offlineOnly = true),
    private val observabilityModule: ObservabilityModule = InMemoryObservabilityModule(),
    private val toolModule: ToolModule = SafeLocalToolRuntime(),
    private val memoryModule: MemoryModule = InMemoryMemoryModule(),
    private val artifactSha256ByModelId: Map<String, String> = defaultArtifactSha256ByModelId(),
) {
    private val modelArtifactManager = ModelArtifactManager()
    private val imageInputModule = SmokeImageInputModule()
    private var routingMode: RoutingMode = RoutingMode.AUTO

    init {
        registerArtifact(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            fileName = "qwen3.5-0.8b-q4.gguf",
        )
        registerArtifact(
            modelId = ModelCatalog.QWEN_3_5_2B_Q4,
            fileName = "qwen3.5-2b-q4.gguf",
        )
        modelArtifactManager.setActiveModel(ModelCatalog.QWEN_3_5_0_8B_Q4)
    }

    fun createSession(): SessionId = conversationModule.createSession()

    fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int = 128,
    ): ChatResponse {
        return sendUserMessage(
            sessionId = sessionId,
            userText = userText,
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            onToken = {},
        )
    }

    fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int = 128,
        onToken: (String) -> Unit,
    ): ChatResponse {
        requirePolicyEvent(
            eventType = "routing.model_select",
            failureMessage = "Policy module rejected routing event type.",
        )
        val modelId = selectModelId(taskType = taskType, deviceState = deviceState)
        check(inferenceModule.loadModel(modelId)) {
            "Failed to load runtime model: $modelId"
        }
        check(modelArtifactManager.setActiveModel(modelId)) {
            "Model artifact not registered for runtime model: $modelId"
        }

        conversationModule.appendUserTurn(sessionId, userText)
        requirePolicyEvent(
            eventType = "memory.write_user_turn",
            failureMessage = "Policy module rejected memory write event type.",
        )
        memoryModule.saveMemoryChunk(
            MemoryChunk(
                id = "mem-${System.currentTimeMillis()}",
                content = userText,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val prompt = buildPrompt(userText, sessionId, taskType, deviceState)
        requirePolicyEvent(
            eventType = "inference.generate",
            failureMessage = "Policy module rejected inference event type.",
        )
        val startedMs = System.currentTimeMillis()
        var firstTokenLatencyMs = -1L
        val builder = StringBuilder()
        try {
            inferenceModule.generateStream(InferenceRequest(prompt = prompt, maxTokens = maxTokens)) { token ->
                if (firstTokenLatencyMs < 0) {
                    firstTokenLatencyMs = System.currentTimeMillis() - startedMs
                }
                builder.append(token)
                onToken(token)
            }
        } finally {
            inferenceModule.unloadModel()
        }
        check(firstTokenLatencyMs >= 0) { "Runtime returned no tokens." }
        val totalLatency = System.currentTimeMillis() - startedMs
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.first_token_ms", firstTokenLatencyMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.total_ms", totalLatency.toDouble())
        observabilityModule.recordThermalSnapshot(deviceState.thermalLevel)
        conversationModule.appendAssistantTurn(sessionId, builder.toString().trim())

        return ChatResponse(
            sessionId = sessionId,
            modelId = modelId,
            text = builder.toString().trim(),
            firstTokenLatencyMs = firstTokenLatencyMs,
            totalLatencyMs = totalLatency,
        )
    }

    fun runTool(toolName: String, jsonArgs: String): String {
        if (!policyModule.enforceDataBoundary("tool.execute")) {
            return "Tool error: Policy module rejected tool event type."
        }
        val result = toolModule.executeToolCall(ToolCall(toolName, jsonArgs))
        return if (result.success) result.content else "Tool error: ${result.content}"
    }

    fun analyzeImage(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
    ): String {
        requirePolicyEvent(
            eventType = "routing.image_model_select",
            failureMessage = "Policy module rejected image routing event type.",
        )
        val modelId = selectModelId(taskType = "image", deviceState = deviceState)
        check(inferenceModule.loadModel(modelId)) {
            "Failed to load runtime model for image analysis: $modelId"
        }
        check(modelArtifactManager.setActiveModel(modelId)) {
            "Model artifact not registered for image runtime model: $modelId"
        }

        val startedMs = System.currentTimeMillis()
        val imageResult = try {
            requirePolicyEvent(
                eventType = "inference.image_analyze",
                failureMessage = "Policy module rejected image analysis event type.",
            )
            imageInputModule.analyzeImage(
                com.pocketagent.inference.ImageRequest(
                    imagePath = imagePath,
                    prompt = prompt,
                    maxTokens = 128,
                ),
            )
        } finally {
            inferenceModule.unloadModel()
        }

        val totalLatency = System.currentTimeMillis() - startedMs
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.image.total_ms", totalLatency.toDouble())
        observabilityModule.recordThermalSnapshot(deviceState.thermalLevel)
        return imageResult
    }

    fun runStartupChecks(): List<String> {
        val checks = mutableListOf<String>()
        val manifestIssues = modelArtifactManager.validateManifest()
        if (manifestIssues.isNotEmpty()) {
            checks.add(
                "Artifact manifest invalid: ${manifestIssues.joinToString("; ") { "${it.modelId}@${it.version}:${it.code}" }}. " +
                    "Set ${QWEN_0_8B_SHA256_ENV} and ${QWEN_2B_SHA256_ENV} with SHA-256 values before Stage-2 closure runs.",
            )
            return checks
        }

        val available = inferenceModule.listAvailableModels().toSet()
        val required = setOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN_3_5_2B_Q4)
        val missing = required.minus(available)
        if (missing.isNotEmpty()) {
            checks.add("Missing runtime model(s): ${missing.joinToString(", ")}.")
        } else {
            if (!inferenceModule.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4)) {
                checks.add("Failed to load baseline runtime model: ${ModelCatalog.QWEN_3_5_0_8B_Q4}.")
            } else {
                inferenceModule.unloadModel()
            }
        }
        if (!policyModule.enforceDataBoundary("inference.startup_check")) {
            checks.add("Policy module rejected startup event type.")
        }
        return checks
    }

    fun exportDiagnostics(): String {
        requirePolicyEvent(
            eventType = "observability.export",
            failureMessage = "Policy module rejected diagnostics export event type.",
        )
        return redactDiagnostics(observabilityModule.exportLocalDiagnostics())
    }

    fun listSessions(): List<SessionId> = conversationModule.listSessions()

    fun listTurns(sessionId: SessionId): List<Turn> = conversationModule.listTurns(sessionId)

    fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        conversationModule.restoreSession(sessionId, turns)
        turns.filter { it.role == "user" }.forEach { turn ->
            memoryModule.saveMemoryChunk(
                MemoryChunk(
                    id = "restore-${sessionId.value}-${turn.timestampEpochMs}",
                    content = turn.content,
                    createdAtEpochMs = turn.timestampEpochMs,
                ),
            )
        }
    }

    fun deleteSession(sessionId: SessionId): Boolean = conversationModule.deleteSession(sessionId)

    fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    fun getRoutingMode(): RoutingMode = routingMode

    private fun buildPrompt(
        userText: String,
        sessionId: SessionId,
        taskType: String,
        deviceState: DeviceState,
    ): String {
        val contextBudget = routingModule.selectContextBudget(taskType, deviceState)
        val memorySnippets = memoryModule.retrieveRelevantMemory(userText, 3)
            .joinToString("\n") { "memory: ${it.content}" }
        return buildString {
            append("task=")
            append(taskType)
            append(" context_budget=")
            append(contextBudget)
            append("\n")
            append(conversationModule.buildPromptContext(sessionId))
            append("\n")
            append(memorySnippets)
            append("\n")
            append("user: ")
            append(userText)
        }.take(contextBudget * 4)
    }

    private fun registerArtifact(modelId: String, fileName: String) {
        modelArtifactManager.registerArtifact(
            ModelArtifact(
                modelId = modelId,
                version = "1",
                fileName = fileName,
                expectedSha256 = artifactSha256ByModelId[modelId]?.trim().orEmpty(),
            ),
        )
    }

    private fun requirePolicyEvent(eventType: String, failureMessage: String) {
        check(policyModule.enforceDataBoundary(eventType)) { failureMessage }
    }

    private fun selectModelId(taskType: String, deviceState: DeviceState): String {
        return when (routingMode) {
            RoutingMode.AUTO -> routingModule.selectModel(taskType, deviceState)
            RoutingMode.QWEN_0_8B -> ModelCatalog.QWEN_3_5_0_8B_Q4
            RoutingMode.QWEN_2B -> ModelCatalog.QWEN_3_5_2B_Q4
        }
    }

    private fun redactDiagnostics(raw: String): String {
        return raw.split("|").joinToString("|") { section ->
            section.split(";").joinToString(";") { entry ->
                val trimmed = entry.trim()
                val key = trimmed.substringBefore("=").substringBefore(":").trim().lowercase()
                if (SENSITIVE_DIAGNOSTIC_KEYS.contains(key)) {
                    "${key}=[REDACTED]"
                } else {
                    entry
                }
            }
        }
    }

    companion object {
        const val QWEN_0_8B_SHA256_ENV: String = "POCKETGPT_QWEN_3_5_0_8B_Q4_SHA256"
        const val QWEN_2B_SHA256_ENV: String = "POCKETGPT_QWEN_3_5_2B_Q4_SHA256"
        private val SENSITIVE_DIAGNOSTIC_KEYS = setOf(
            "user",
            "assistant",
            "prompt",
            "memory",
            "content",
            "jsonargs",
            "tool_args",
        )

        private fun defaultArtifactSha256ByModelId(): Map<String, String> = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to System.getenv(QWEN_0_8B_SHA256_ENV).orEmpty(),
            ModelCatalog.QWEN_3_5_2B_Q4 to System.getenv(QWEN_2B_SHA256_ENV).orEmpty(),
        )
    }
}
