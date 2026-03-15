package com.pocketagent.android

import android.util.Log
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeResourceControl
import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.WarmupResult
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleEvent
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class HotSwappableRuntimeFacade(
    initial: MvpRuntimeFacade,
) : MvpRuntimeFacade, RuntimeWarmupSupport, RuntimeResourceControl {
    private val lock = ReentrantReadWriteLock()
    private var delegate: MvpRuntimeFacade = initial
    private var replacementCounter: Long = 0L

    fun replace(newDelegate: MvpRuntimeFacade) {
        lock.write {
            delegate = newDelegate
            replacementCounter += 1L
            safeLogInfo("RUNTIME_SWAP|phase=replaced|counter=$replacementCounter")
        }
    }

    override fun createSession(): SessionId = withDelegate { it.createSession() }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return withDelegate { it.streamChat(request) }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = withDelegate { it.cancelGeneration(sessionId) }

    override fun cancelGenerationByRequest(requestId: String): Boolean = withDelegate { it.cancelGenerationByRequest(requestId) }

    override fun runTool(toolName: String, jsonArgs: String): String = withDelegate { it.runTool(toolName, jsonArgs) }

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return withDelegate { it.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs) }
    }

    override fun analyzeImage(imagePath: String, prompt: String): String = withDelegate { it.analyzeImage(imagePath, prompt) }

    override fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        return withDelegate { it.analyzeImageDetailed(imagePath = imagePath, prompt = prompt) }
    }

    override fun exportDiagnostics(): String = withDelegate { it.exportDiagnostics() }

    override fun setRoutingMode(mode: RoutingMode) {
        withDelegate { it.setRoutingMode(mode) }
    }

    override fun getRoutingMode(): RoutingMode = withDelegate { it.getRoutingMode() }

    override fun runStartupChecks(): List<String> = withDelegate { it.runStartupChecks() }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        withDelegate { it.restoreSession(sessionId, turns) }
    }

    override fun deleteSession(sessionId: SessionId): Boolean = withDelegate { it.deleteSession(sessionId) }

    override fun runtimeBackend(): String? = withDelegate { it.runtimeBackend() }

    override fun supportsGpuOffload(): Boolean = withDelegate { it.supportsGpuOffload() }

    override fun warmupActiveModel(): WarmupResult {
        return withDelegate { facade ->
            (facade as? RuntimeWarmupSupport)?.warmupActiveModel()
                ?: WarmupResult.skipped("warmup_unsupported")
        }
    }

    override fun evictResidentModel(reason: String): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.evictResidentModel(reason) ?: false
        }
    }

    override fun loadModel(modelId: String, modelVersion: String?): RuntimeModelLifecycleCommandResult {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.loadModel(modelId = modelId, modelVersion = modelVersion)
                ?: RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.UNKNOWN,
                    detail = "runtime_model_load_unsupported",
                )
        }
    }

    override fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.offloadModel(reason = reason)
                ?: RuntimeModelLifecycleCommandResult.applied()
        }
    }

    override fun loadedModel(): RuntimeLoadedModel? {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.loadedModel()
        }
    }

    override fun activeGenerationCount(): Int {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.activeGenerationCount() ?: 0
        }
    }

    override fun touchKeepAlive(): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.touchKeepAlive() ?: false
        }
    }

    override fun shortenKeepAlive(ttlMs: Long): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.shortenKeepAlive(ttlMs) ?: false
        }
    }

    override fun onTrimMemory(level: Int): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.onTrimMemory(level) ?: false
        }
    }

    override fun onAppBackground(): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.onAppBackground() ?: false
        }
    }

    override fun onAppForeground(): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.onAppForeground() ?: false
        }
    }

    override fun addAutoReleaseDisableReason(reason: String) {
        withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.addAutoReleaseDisableReason(reason)
        }
    }

    override fun removeAutoReleaseDisableReason(reason: String) {
        withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.removeAutoReleaseDisableReason(reason)
        }
    }

    override fun exportDiagnosticsJson(): String? {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.exportDiagnosticsJson()
                ?: facade.exportDiagnosticsJson()
        }
    }

    override fun currentModelLifecycleEvent(): ModelLifecycleEvent? {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.currentModelLifecycleEvent()
                ?: facade.currentModelLifecycleEvent()
        }
    }

    override fun observeModelLifecycleEvents(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.observeModelLifecycleEvents(listener)
                ?: facade.observeModelLifecycleEvents(listener)
        }
    }

    private inline fun <T> withDelegate(block: (MvpRuntimeFacade) -> T): T {
        return lock.read {
            block(delegate)
        }
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i("AppRuntimeDeps", message) }
    }
}
