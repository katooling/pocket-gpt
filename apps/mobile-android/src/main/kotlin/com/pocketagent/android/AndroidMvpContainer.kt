package com.pocketagent.android

import com.pocketagent.core.ConversationModule
import com.pocketagent.core.DefaultPolicyModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.InMemoryObservabilityModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.SessionId
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
) {
    private val modelArtifactManager = ModelArtifactManager()
    private val imageInputModule = SmokeImageInputModule()

    init {
        modelArtifactManager.registerArtifact(
            ModelArtifact(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                version = "1",
                fileName = "qwen3.5-0.8b-q4.gguf",
                expectedSha256 = "replace-with-real-sha256",
            ),
        )
        modelArtifactManager.registerArtifact(
            ModelArtifact(
                modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                version = "1",
                fileName = "qwen3.5-2b-q4.gguf",
                expectedSha256 = "replace-with-real-sha256",
            ),
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
        val modelId = routingModule.selectModel(taskType, deviceState)
        check(inferenceModule.loadModel(modelId)) {
            "Failed to load runtime model: $modelId"
        }
        check(modelArtifactManager.setActiveModel(modelId)) {
            "Model artifact not registered for runtime model: $modelId"
        }

        conversationModule.appendUserTurn(sessionId, userText)
        memoryModule.saveMemoryChunk(
            MemoryChunk(
                id = "mem-${System.currentTimeMillis()}",
                content = userText,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val prompt = buildPrompt(userText, sessionId, taskType, deviceState)
        val startedMs = System.currentTimeMillis()
        var firstTokenLatencyMs = -1L
        val builder = StringBuilder()
        try {
            inferenceModule.generateStream(InferenceRequest(prompt = prompt, maxTokens = maxTokens)) { token ->
                if (firstTokenLatencyMs < 0) {
                    firstTokenLatencyMs = System.currentTimeMillis() - startedMs
                }
                builder.append(token)
            }
        } finally {
            inferenceModule.unloadModel()
        }
        check(firstTokenLatencyMs >= 0) { "Runtime returned no tokens." }
        val totalLatency = System.currentTimeMillis() - startedMs
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
        val result = toolModule.executeToolCall(ToolCall(toolName, jsonArgs))
        return if (result.success) result.content else "Tool error: ${result.content}"
    }

    fun analyzeImage(imagePath: String, prompt: String): String {
        return imageInputModule.analyzeImage(
            com.pocketagent.inference.ImageRequest(
                imagePath = imagePath,
                prompt = prompt,
                maxTokens = 128,
            ),
        )
    }

    fun runStartupChecks(): List<String> {
        val checks = mutableListOf<String>()
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

    fun exportDiagnostics(): String = observabilityModule.exportLocalDiagnostics()

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
}
