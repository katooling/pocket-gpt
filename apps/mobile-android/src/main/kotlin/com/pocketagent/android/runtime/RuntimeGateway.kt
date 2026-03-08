package com.pocketagent.android.runtime

import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.StreamUserMessageRequest
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.flow.Flow

fun interface DeviceGpuOffloadSupport {
    fun isSupported(): Boolean

    companion object {
        val ASSUME_SUPPORTED = DeviceGpuOffloadSupport { true }
    }
}

interface RuntimeGateway {
    fun createSession(): SessionId
    fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent>
    fun cancelGeneration(sessionId: SessionId): Boolean
    fun cancelGenerationByRequest(requestId: String): Boolean
    fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult
    fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
    fun runtimeBackend(): String?
    fun supportsGpuOffload(): Boolean
}

class MvpRuntimeGateway(
    private val facade: MvpRuntimeFacade,
    private val deviceGpuOffloadSupport: DeviceGpuOffloadSupport = DeviceGpuOffloadSupport.ASSUME_SUPPORTED,
) : RuntimeGateway {
    override fun createSession(): SessionId = facade.createSession()

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return facade.streamUserMessage(request)
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = facade.cancelGeneration(sessionId)

    override fun cancelGenerationByRequest(requestId: String): Boolean = facade.cancelGenerationByRequest(requestId)

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        return facade.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs)
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return facade.analyzeImageDetailed(imagePath = imagePath, prompt = prompt)
    }

    override fun exportDiagnostics(): String = facade.exportDiagnostics()

    override fun setRoutingMode(mode: RoutingMode) {
        facade.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = facade.getRoutingMode()

    override fun runStartupChecks(): List<String> = facade.runStartupChecks()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        facade.restoreSession(sessionId = sessionId, turns = turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = facade.deleteSession(sessionId)

    override fun runtimeBackend(): String? = facade.runtimeBackend()

    override fun supportsGpuOffload(): Boolean {
        return deviceGpuOffloadSupport.isSupported() && facade.supportsGpuOffload()
    }
}
