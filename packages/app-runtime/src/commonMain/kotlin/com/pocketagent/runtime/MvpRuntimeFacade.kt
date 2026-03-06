package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
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

data class StreamUserMessageRequest(
    val sessionId: SessionId,
    val userText: String,
    val taskType: String,
    val deviceState: DeviceState,
    val maxTokens: Int = 128,
    val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    val requestId: String = defaultRequestId(),
)

sealed interface ChatStreamEvent {
    val requestId: String

    data class Started(
        override val requestId: String,
        val startedAtEpochMs: Long,
    ) : ChatStreamEvent

    data class TokenDelta(
        override val requestId: String,
        val token: String,
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
    fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent>
    fun cancelGeneration(sessionId: SessionId): Boolean
    fun cancelGenerationByRequest(requestId: String): Boolean = false
    fun runTool(toolName: String, jsonArgs: String): String
    fun analyzeImage(imagePath: String, prompt: String): String
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
    fun runtimeBackend(): String? = null
}

interface RuntimeContainer {
    fun createSession(): SessionId
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
    ): ChatResponse
    fun cancelGeneration(sessionId: SessionId): Boolean = false
    fun cancelGenerationByRequest(requestId: String): Boolean = false
    fun runTool(toolName: String, jsonArgs: String): String
    fun analyzeImage(imagePath: String, prompt: String): String
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
    fun runtimeBackend(): String? = null
}

class DefaultMvpRuntimeFacade(
    private val container: RuntimeContainer = DefaultRuntimeContainer(),
) : MvpRuntimeFacade {
    private val streamContractV2Enabled: Boolean = streamContractV2Enabled()

    override fun createSession(): SessionId = container.createSession()

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> = callbackFlow {
        val startedAtMs = System.currentTimeMillis()
        val terminalSent = AtomicBoolean(false)
        if (streamContractV2Enabled) {
            trySend(ChatStreamEvent.Started(requestId = request.requestId, startedAtEpochMs = startedAtMs))
        }
        val textBuilder = StringBuilder()
        var firstTokenMs: Long? = null
        val finished = AtomicBoolean(false)
        val producer = launch(Dispatchers.IO) {
            runCatching {
                val response = container.sendUserMessage(
                    sessionId = request.sessionId,
                    userText = request.userText,
                    taskType = request.taskType,
                    deviceState = request.deviceState,
                    maxTokens = request.maxTokens,
                    keepModelLoaded = true,
                    onToken = { token ->
                        if (firstTokenMs == null) {
                            firstTokenMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                        }
                        textBuilder.append(token)
                        trySend(
                            ChatStreamEvent.TokenDelta(
                                requestId = request.requestId,
                                token = token,
                                accumulatedText = textBuilder.toString().trim(),
                            ),
                        )
                    },
                    requestTimeoutMs = request.requestTimeoutMs,
                    requestId = request.requestId,
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

    override fun analyzeImage(imagePath: String, prompt: String): String {
        return container.analyzeImage(imagePath = imagePath, prompt = prompt)
    }

    override fun exportDiagnostics(): String = container.exportDiagnostics()

    override fun setRoutingMode(mode: RoutingMode) {
        container.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = container.getRoutingMode()

    override fun runStartupChecks(): List<String> = container.runStartupChecks()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        container.restoreSession(sessionId = sessionId, turns = turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = container.deleteSession(sessionId)

    override fun runtimeBackend(): String? = container.runtimeBackend()
}

class DefaultRuntimeContainer(
    runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
    memoryModule: MemoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
    private val orchestrator: RuntimeOrchestrator = RuntimeOrchestrator(
        memoryModule = memoryModule,
        runtimeConfig = runtimeConfig,
    ),
) : RuntimeContainer {
    override fun createSession(): SessionId = orchestrator.createSession()

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
        )
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = orchestrator.cancelGeneration(sessionId)

    override fun cancelGenerationByRequest(requestId: String): Boolean = orchestrator.cancelGenerationByRequest(requestId)

    override fun runTool(toolName: String, jsonArgs: String): String = orchestrator.runTool(toolName, jsonArgs)

    override fun analyzeImage(imagePath: String, prompt: String): String {
        return orchestrator.analyzeImage(imagePath = imagePath, prompt = prompt)
    }

    override fun exportDiagnostics(): String = orchestrator.exportDiagnostics()

    override fun setRoutingMode(mode: RoutingMode) {
        orchestrator.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = orchestrator.getRoutingMode()

    override fun runStartupChecks(): List<String> = orchestrator.runStartupChecks()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        orchestrator.restoreSession(sessionId = sessionId, turns = turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = orchestrator.deleteSession(sessionId)

    override fun runtimeBackend(): String? = orchestrator.runtimeBackend()
}

private const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 90_000L

private fun defaultRequestId(): String {
    return "req-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}"
}

private fun streamContractV2Enabled(): Boolean {
    val raw = System.getenv(RuntimeConfig.STREAM_CONTRACT_V2_ENV)
        ?.trim()
        ?.lowercase()
        ?: return true
    return raw in setOf("1", "true", "yes", "on")
}
