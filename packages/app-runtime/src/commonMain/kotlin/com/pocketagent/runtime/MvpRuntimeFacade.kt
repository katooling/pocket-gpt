package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.memory.FileBackedMemoryModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

data class StreamUserMessageRequest(
    val sessionId: SessionId,
    val userText: String,
    val taskType: String,
    val deviceState: DeviceState,
    val maxTokens: Int = 128,
)

sealed interface ChatStreamEvent {
    data class Token(
        val token: String,
        val accumulatedText: String,
    ) : ChatStreamEvent

    data class Completed(
        val response: ChatResponse,
    ) : ChatStreamEvent
}

interface MvpRuntimeFacade {
    fun createSession(): SessionId
    fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent>
    fun runTool(toolName: String, jsonArgs: String): String
    fun analyzeImage(imagePath: String, prompt: String): String
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
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
    ): ChatResponse
    fun runTool(toolName: String, jsonArgs: String): String
    fun analyzeImage(imagePath: String, prompt: String): String
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
}

class DefaultMvpRuntimeFacade(
    private val container: RuntimeContainer = DefaultRuntimeContainer(),
) : MvpRuntimeFacade {
    override fun createSession(): SessionId = container.createSession()

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> = channelFlow {
        val textBuilder = StringBuilder()
        val response = container.sendUserMessage(
            sessionId = request.sessionId,
            userText = request.userText,
            taskType = request.taskType,
            deviceState = request.deviceState,
            maxTokens = request.maxTokens,
            keepModelLoaded = false,
            onToken = { token ->
                textBuilder.append(token)
                trySend(
                    ChatStreamEvent.Token(
                        token = token,
                        accumulatedText = textBuilder.toString().trim(),
                    ),
                )
            },
        )
        trySend(ChatStreamEvent.Completed(response = response))
    }

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
}

class DefaultRuntimeContainer(
    private val orchestrator: RuntimeOrchestrator = RuntimeOrchestrator(
        memoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
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
    ): ChatResponse {
        return orchestrator.sendUserMessage(
            sessionId = sessionId,
            userText = userText,
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            onToken = onToken,
            keepModelLoaded = keepModelLoaded,
        )
    }

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
}
