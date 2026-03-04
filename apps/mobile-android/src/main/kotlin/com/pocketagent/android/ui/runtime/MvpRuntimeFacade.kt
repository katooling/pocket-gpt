package com.pocketagent.android.ui.runtime

import com.pocketagent.android.AndroidNativeMemoryModule
import com.pocketagent.android.AndroidMvpContainer
import com.pocketagent.android.ChatResponse
import com.pocketagent.android.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
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

class DefaultMvpRuntimeFacade(
    private val container: AndroidMvpContainer = AndroidMvpContainer(
        memoryModule = AndroidNativeMemoryModule.defaultRuntimeModule(),
    ),
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
        ) { token ->
            textBuilder.append(token)
            trySend(
                ChatStreamEvent.Token(
                    token = token,
                    accumulatedText = textBuilder.toString().trim(),
                ),
            )
        }
        trySend(ChatStreamEvent.Completed(response = response))
    }

    override fun runTool(toolName: String, jsonArgs: String): String {
        return container.runTool(toolName, jsonArgs)
    }

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

    override fun deleteSession(sessionId: SessionId): Boolean {
        return container.deleteSession(sessionId)
    }
}
