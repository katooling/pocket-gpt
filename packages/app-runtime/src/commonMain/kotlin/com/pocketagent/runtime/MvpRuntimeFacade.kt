package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.DeviceState
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

@Deprecated(
    message = "Use StreamChatRequestV2 with canonical interaction messages.",
    replaceWith = ReplaceWith("StreamChatRequestV2"),
)
data class StreamUserMessageRequest(
    val sessionId: SessionId,
    val userText: String,
    val taskType: String,
    val deviceState: DeviceState,
    val maxTokens: Int = 128,
    val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    val requestId: String = defaultRequestId(),
    val performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
    val residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(),
)

data class StreamChatRequestV2(
    val sessionId: SessionId,
    val messages: List<InteractionMessage>,
    val taskType: String,
    val deviceState: DeviceState,
    val maxTokens: Int = 128,
    val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    val requestId: String = defaultRequestId(),
    val previousResponseId: String? = null,
    val performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
    val residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(),
)

enum class ChatStreamPhase {
    CHAT_START,
    MODEL_LOAD,
    PROMPT_PROCESSING,
    TOKEN_STREAM,
    CHAT_END,
    ERROR,
}

sealed interface ChatStreamDelta {
    data class TextDelta(
        val text: String,
    ) : ChatStreamDelta
}

sealed interface ChatStreamEvent {
    val requestId: String

    data class Started(
        override val requestId: String,
        val startedAtEpochMs: Long,
    ) : ChatStreamEvent

    data class Phase(
        override val requestId: String,
        val phase: ChatStreamPhase,
        val detail: String? = null,
    ) : ChatStreamEvent

    @Deprecated(
        message = "Use Delta with ChatStreamDelta.TextDelta.",
        replaceWith = ReplaceWith("Delta"),
    )
    data class TokenDelta(
        override val requestId: String,
        val token: String,
        val accumulatedText: String,
    ) : ChatStreamEvent

    data class Delta(
        override val requestId: String,
        val delta: ChatStreamDelta,
        val accumulatedText: String,
    ) : ChatStreamEvent

    data class Completed(
        override val requestId: String,
        val response: ChatResponse,
        val finishReason: String,
        val terminalEventSeen: Boolean = true,
        val firstTokenMs: Long? = null,
        val completionMs: Long? = null,
    ) : ChatStreamEvent

    data class Failed(
        override val requestId: String,
        val errorCode: String,
        val message: String,
        val terminalEventSeen: Boolean = true,
        val firstTokenMs: Long? = null,
        val completionMs: Long? = null,
    ) : ChatStreamEvent

    data class Cancelled(
        override val requestId: String,
        val reason: String,
        val terminalEventSeen: Boolean = true,
        val firstTokenMs: Long? = null,
        val completionMs: Long? = null,
    ) : ChatStreamEvent
}

interface MvpRuntimeFacade {
    fun createSession(): SessionId
    fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return streamChat(request.toV2Request())
    }
    fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent>
    fun cancelGeneration(sessionId: SessionId): Boolean
    fun cancelGenerationByRequest(requestId: String): Boolean = false
    fun runTool(toolName: String, jsonArgs: String): String
    fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return ToolExecutionResult.fromLegacy(runTool(toolName, jsonArgs))
    }
    fun analyzeImage(imagePath: String, prompt: String): String
    fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        return ImageAnalysisResult.fromLegacy(analyzeImage(imagePath, prompt))
    }
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
    fun runtimeBackend(): String? = null
    fun supportsGpuOffload(): Boolean = false
}

interface RuntimeContainer {
    fun createSession(): SessionId
    fun sendChatMessages(
        sessionId: SessionId,
        messages: List<InteractionMessage>,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean = false,
        onToken: (String) -> Unit,
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        requestId: String = "legacy",
        previousResponseId: String? = null,
        performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
        residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(),
    ): ChatResponse
    fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean = false,
        onToken: (String) -> Unit,
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        requestId: String = "legacy",
        performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
        residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(),
    ): ChatResponse {
        return sendChatMessages(
            sessionId = sessionId,
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text(userText)),
                ),
            ),
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            keepModelLoaded = keepModelLoaded,
            onToken = onToken,
            requestTimeoutMs = requestTimeoutMs,
            requestId = requestId,
            previousResponseId = null,
            performanceConfig = performanceConfig,
            residencyPolicy = residencyPolicy,
        )
    }
    fun cancelGeneration(sessionId: SessionId): Boolean = false
    fun cancelGenerationByRequest(requestId: String): Boolean = false
    fun runTool(toolName: String, jsonArgs: String): String
    fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return ToolExecutionResult.fromLegacy(runTool(toolName, jsonArgs))
    }
    fun analyzeImage(imagePath: String, prompt: String): String
    fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        return ImageAnalysisResult.fromLegacy(analyzeImage(imagePath, prompt))
    }
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun warmupActiveModel(): WarmupResult = WarmupResult.skipped("warmup_unsupported")
    fun evictResidentModel(reason: String = "manual"): Boolean = false
    fun touchKeepAlive(): Boolean = false
    fun shortenKeepAlive(ttlMs: Long): Boolean = false
    fun onTrimMemory(level: Int): Boolean = false
    fun onAppBackground(): Boolean = false
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
    fun runtimeBackend(): String? = null
    fun supportsGpuOffload(): Boolean = false
}

class DefaultMvpRuntimeFacade(
    private val container: RuntimeContainer = DefaultRuntimeContainer(),
) : MvpRuntimeFacade, RuntimeWarmupSupport, RuntimeResourceControl {
    private val streamContractV2Enabled: Boolean = streamContractV2Enabled()

    override fun createSession(): SessionId = container.createSession()

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return streamChat(request.toV2Request())
    }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = callbackFlow {
        val startedAtMs = System.currentTimeMillis()
        val terminalSent = AtomicBoolean(false)
        if (streamContractV2Enabled) {
            trySend(ChatStreamEvent.Started(requestId = request.requestId, startedAtEpochMs = startedAtMs))
            trySend(
                ChatStreamEvent.Phase(
                    requestId = request.requestId,
                    phase = ChatStreamPhase.CHAT_START,
                    detail = "chat.start",
                ),
            )
        }
        val textBuilder = StringBuilder()
        var firstTokenMs: Long? = null
        var tokenStreamPhaseEmitted = false
        val finished = AtomicBoolean(false)
        val producer = launch(Dispatchers.IO) {
            runCatching {
                if (streamContractV2Enabled) {
                    trySend(
                        ChatStreamEvent.Phase(
                            requestId = request.requestId,
                            phase = ChatStreamPhase.MODEL_LOAD,
                            detail = "model.load",
                        ),
                    )
                    trySend(
                        ChatStreamEvent.Phase(
                            requestId = request.requestId,
                            phase = ChatStreamPhase.PROMPT_PROCESSING,
                            detail = "prompt.processing",
                        ),
                    )
                }
                val response = container.sendChatMessages(
                    sessionId = request.sessionId,
                    messages = request.messages,
                    taskType = request.taskType,
                    deviceState = request.deviceState,
                    maxTokens = request.maxTokens,
                    keepModelLoaded = true,
                    onToken = { token ->
                        if (firstTokenMs == null) {
                            firstTokenMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                        }
                        if (streamContractV2Enabled && !tokenStreamPhaseEmitted) {
                            tokenStreamPhaseEmitted = true
                            trySend(
                                ChatStreamEvent.Phase(
                                    requestId = request.requestId,
                                    phase = ChatStreamPhase.TOKEN_STREAM,
                                    detail = "response.delta",
                                ),
                            )
                        }
                        textBuilder.append(token)
                        val accumulatedText = textBuilder.toString()
                        trySend(
                            ChatStreamEvent.Delta(
                                requestId = request.requestId,
                                delta = ChatStreamDelta.TextDelta(text = token),
                                accumulatedText = accumulatedText,
                            ),
                        )
                    },
                    requestTimeoutMs = request.requestTimeoutMs,
                    requestId = request.requestId,
                    previousResponseId = request.previousResponseId,
                    performanceConfig = request.performanceConfig,
                    residencyPolicy = request.residencyPolicy,
                )
                finished.set(true)
                terminalSent.set(true)
                trySend(
                    ChatStreamEvent.Completed(
                        requestId = request.requestId,
                        response = response,
                        finishReason = response.finishReason,
                        firstTokenMs = firstTokenMs ?: response.firstTokenLatencyMs,
                        completionMs = response.totalLatencyMs,
                    ),
                )
                if (streamContractV2Enabled) {
                    trySend(
                        ChatStreamEvent.Phase(
                            requestId = request.requestId,
                            phase = ChatStreamPhase.CHAT_END,
                            detail = "chat.end",
                        ),
                    )
                }
                close()
            }.onFailure { error ->
                finished.set(true)
                if (!streamContractV2Enabled) {
                    close(error)
                    return@onFailure
                }
                val completionMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                if (!terminalSent.get()) {
                    terminalSent.set(true)
                    val terminalEvent = when (error) {
                        is RuntimeGenerationTimeoutException -> ChatStreamEvent.Cancelled(
                            requestId = request.requestId,
                            reason = "timeout",
                            firstTokenMs = firstTokenMs,
                            completionMs = completionMs,
                        )

                        is RuntimeGenerationCancelledException -> ChatStreamEvent.Cancelled(
                            requestId = request.requestId,
                            reason = "cancelled",
                            firstTokenMs = firstTokenMs,
                            completionMs = completionMs,
                        )

                        else -> ChatStreamEvent.Failed(
                            requestId = request.requestId,
                            errorCode = when (error) {
                                is RuntimeTemplateUnavailableException -> "template_unavailable"
                                is RuntimeGenerationFailureException -> error.errorCode
                                    ?.trim()
                                    ?.lowercase()
                                    ?.ifBlank { null }
                                else -> null
                            } ?: "runtime_error",
                            message = error.message ?: "Runtime stream failed.",
                            firstTokenMs = firstTokenMs,
                            completionMs = completionMs,
                        )
                    }
                    trySend(terminalEvent)
                }
                if (streamContractV2Enabled) {
                    trySend(
                        ChatStreamEvent.Phase(
                            requestId = request.requestId,
                            phase = ChatStreamPhase.ERROR,
                            detail = "error",
                        ),
                    )
                }
                close()
            }
        }
        awaitClose {
            producer.cancel()
            if (!finished.get()) {
                container.cancelGenerationByRequest(request.requestId) || container.cancelGeneration(request.sessionId)
            }
        }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = container.cancelGeneration(sessionId)

    override fun cancelGenerationByRequest(requestId: String): Boolean = container.cancelGenerationByRequest(requestId)

    override fun runTool(toolName: String, jsonArgs: String): String = container.runTool(toolName, jsonArgs)

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return container.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs)
    }

    override fun analyzeImage(imagePath: String, prompt: String): String {
        return container.analyzeImage(imagePath = imagePath, prompt = prompt)
    }

    override fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        return container.analyzeImageDetailed(imagePath = imagePath, prompt = prompt)
    }

    override fun exportDiagnostics(): String = container.exportDiagnostics()

    override fun setRoutingMode(mode: RoutingMode) {
        container.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = container.getRoutingMode()

    override fun runStartupChecks(): List<String> = container.runStartupChecks()

    override fun warmupActiveModel(): WarmupResult = container.warmupActiveModel()

    override fun evictResidentModel(reason: String): Boolean = container.evictResidentModel(reason)

    override fun touchKeepAlive(): Boolean = container.touchKeepAlive()

    override fun shortenKeepAlive(ttlMs: Long): Boolean = container.shortenKeepAlive(ttlMs)

    override fun onTrimMemory(level: Int): Boolean = container.onTrimMemory(level)

    override fun onAppBackground(): Boolean = container.onAppBackground()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        container.restoreSession(sessionId = sessionId, turns = turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = container.deleteSession(sessionId)

    override fun runtimeBackend(): String? = container.runtimeBackend()

    override fun supportsGpuOffload(): Boolean = container.supportsGpuOffload()
}

class DefaultRuntimeContainer(
    runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
    conversationModule: ConversationModule = InMemoryConversationModule(),
    memoryModule: MemoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
    inferenceModule: InferenceModule? = null,
    private val orchestrator: RuntimeOrchestrator = RuntimeOrchestrator(
        conversationModule = conversationModule,
        memoryModule = memoryModule,
        runtimeConfig = runtimeConfig,
        inferenceModule = inferenceModule ?: com.pocketagent.nativebridge.LlamaCppInferenceModule(),
    ),
) : RuntimeContainer {
    override fun createSession(): SessionId = orchestrator.createSession()

    override fun sendChatMessages(
        sessionId: SessionId,
        messages: List<InteractionMessage>,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
        previousResponseId: String?,
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        return orchestrator.sendChatMessages(
            sessionId = sessionId,
            messages = messages,
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            keepModelLoaded = keepModelLoaded,
            onToken = onToken,
            requestTimeoutMs = requestTimeoutMs,
            requestId = requestId,
            previousResponseId = previousResponseId,
            performanceConfig = performanceConfig,
            residencyPolicy = residencyPolicy,
        )
    }

    override fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        return orchestrator.sendUserMessage(
            sessionId = sessionId,
            userText = userText,
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            onToken = onToken,
            keepModelLoaded = keepModelLoaded,
            requestTimeoutMs = requestTimeoutMs,
            requestId = requestId,
            performanceConfig = performanceConfig,
            residencyPolicy = residencyPolicy,
        )
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = orchestrator.cancelGeneration(sessionId)

    override fun cancelGenerationByRequest(requestId: String): Boolean = orchestrator.cancelGenerationByRequest(requestId)

    override fun runTool(toolName: String, jsonArgs: String): String = orchestrator.runTool(toolName, jsonArgs)

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return orchestrator.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs)
    }

    override fun analyzeImage(imagePath: String, prompt: String): String {
        return orchestrator.analyzeImage(imagePath = imagePath, prompt = prompt)
    }

    override fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        return orchestrator.analyzeImageDetailed(imagePath = imagePath, prompt = prompt)
    }

    override fun exportDiagnostics(): String = orchestrator.exportDiagnostics()

    override fun setRoutingMode(mode: RoutingMode) {
        orchestrator.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = orchestrator.getRoutingMode()

    override fun runStartupChecks(): List<String> = orchestrator.runStartupChecks()

    override fun warmupActiveModel(): WarmupResult = orchestrator.warmupActiveModel()

    override fun evictResidentModel(reason: String): Boolean = orchestrator.evictResidentModel(reason)

    override fun touchKeepAlive(): Boolean = orchestrator.touchKeepAlive()

    override fun shortenKeepAlive(ttlMs: Long): Boolean = orchestrator.shortenKeepAlive(ttlMs)

    override fun onTrimMemory(level: Int): Boolean = orchestrator.onTrimMemory(level)

    override fun onAppBackground(): Boolean = orchestrator.onAppBackground()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        orchestrator.restoreSession(sessionId = sessionId, turns = turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = orchestrator.deleteSession(sessionId)

    override fun runtimeBackend(): String? = orchestrator.runtimeBackend()

    override fun supportsGpuOffload(): Boolean = orchestrator.supportsGpuOffload()
}

private const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 600_000L

private fun defaultRequestId(): String {
    return "req-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}"
}

private fun StreamUserMessageRequest.toV2Request(): StreamChatRequestV2 {
    return StreamChatRequestV2(
        sessionId = sessionId,
        messages = listOf(
            InteractionMessage(
                id = "legacy-user-${requestId}",
                role = InteractionRole.USER,
                parts = listOf(InteractionContentPart.Text(userText)),
            ),
        ),
        taskType = taskType,
        deviceState = deviceState,
        maxTokens = maxTokens,
        requestTimeoutMs = requestTimeoutMs,
        requestId = requestId,
        performanceConfig = performanceConfig,
        residencyPolicy = residencyPolicy,
    )
}

private fun streamContractV2Enabled(): Boolean {
    val raw = System.getenv(RuntimeConfig.STREAM_CONTRACT_V2_ENV)
        ?.trim()
        ?.lowercase()
        ?: return true
    return raw in setOf("1", "true", "yes", "on")
}
