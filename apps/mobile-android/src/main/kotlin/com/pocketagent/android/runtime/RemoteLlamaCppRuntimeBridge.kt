package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.LlamaCppRuntimeBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeGenerationConfig

internal class RemoteLlamaCppRuntimeBridge internal constructor(
    private val transport: RemoteRuntimeTransport,
) : LlamaCppRuntimeBridge {
    constructor(context: Context) : this(MessengerRemoteRuntimeTransport(context.applicationContext))

    private data class LoadedModelState(
        val modelId: String,
        val modelPath: String?,
    )

    @Volatile
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    @Volatile
    private var lastBridgeError: BridgeError? = null
    @Volatile
    private var loadedModelState: LoadedModelState? = null
    @Volatile
    private var preparedEpoch: Long = Long.MIN_VALUE

    override fun isReady(): Boolean {
        val ready = transport.ping()
        if (!ready) {
            recordError(REMOTE_ERROR_BIND_FAILED, "remote runtime ping failed")
        } else {
            clearError()
        }
        return ready
    }

    override fun listAvailableModels(): List<String> = transport.listAvailableModels()

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        if (!syncConfigToRemote()) {
            return false
        }
        val loaded = transport.loadModel(modelId = modelId, modelPath = modelPath)
        if (!loaded) {
            val remoteError = transport.lastError()
            recordError(
                remoteError?.code ?: REMOTE_ERROR_RUNTIME,
                remoteError?.detail ?: "remote load failed:modelId=$modelId",
            )
            return false
        }
        loadedModelState = LoadedModelState(modelId = modelId, modelPath = modelPath)
        preparedEpoch = transport.epoch()
        clearError()
        return true
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeGenerationConfig = config
        preparedEpoch = Long.MIN_VALUE
        if (!transport.setRuntimeGenerationConfig(config)) {
            recordError(REMOTE_ERROR_RUNTIME, "remote set config failed")
            return
        }
        clearError()
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeGenerationConfig

    override fun supportsGpuOffload(): Boolean {
        val supported = transport.supportsGpuOffload()
        if (!supported) {
            recordError(REMOTE_ERROR_RUNTIME, "remote supportsGpuOffload returned false")
        } else {
            clearError()
        }
        return supported
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        if (!prepareRuntimeState()) {
            return GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = lastBridgeError?.code ?: REMOTE_ERROR_RUNTIME,
            )
        }
        val result = transport.generate(
            requestId = requestId,
            prompt = prompt,
            maxTokens = maxTokens,
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
            onToken = onToken,
        )
        if (result.success || result.finishReason == GenerationFinishReason.CANCELLED) {
            clearError()
        } else {
            val code = result.errorCode ?: transport.lastError()?.code ?: REMOTE_ERROR_RUNTIME
            val detail = transport.lastError()?.detail ?: "remote generate failed:requestId=$requestId"
            recordError(code, detail)
            if (code == REMOTE_ERROR_PROCESS_DIED) {
                preparedEpoch = Long.MIN_VALUE
            }
        }
        return result
    }

    override fun cancelGeneration(): Boolean = cancelGenerationInternal(requestId = null)

    override fun cancelGeneration(requestId: String): Boolean = cancelGenerationInternal(requestId)

    private fun cancelGenerationInternal(requestId: String?): Boolean {
        val cancelled = transport.cancelGeneration(requestId)
        if (!cancelled) {
            recordError(REMOTE_ERROR_RUNTIME, "remote cancel returned false")
        }
        return cancelled
    }

    override fun unloadModel() {
        transport.unloadModel()
        loadedModelState = null
        preparedEpoch = transport.epoch()
    }

    override fun runtimeBackend(): RuntimeBackend {
        return if (transport.ping()) RuntimeBackend.REMOTE_ANDROID_SERVICE else RuntimeBackend.UNAVAILABLE
    }

    override fun lastError(): BridgeError? = lastBridgeError ?: transport.lastError()

    override fun vulkanDiagnosticsJson(): String? = transport.vulkanDiagnosticsJson()

    private fun prepareRuntimeState(): Boolean {
        if (!syncConfigToRemote()) {
            return false
        }
        val model = loadedModelState ?: run {
            recordError(REMOTE_ERROR_MODEL_NOT_LOADED, "generate requested before loadModel")
            return false
        }
        if (preparedEpoch == transport.epoch()) {
            return true
        }
        val reloaded = transport.loadModel(model.modelId, model.modelPath)
        if (!reloaded) {
            val remoteError = transport.lastError()
            recordError(
                remoteError?.code ?: REMOTE_ERROR_RUNTIME,
                remoteError?.detail ?: "remote model reload failed:modelId=${model.modelId}",
            )
            return false
        }
        preparedEpoch = transport.epoch()
        clearError()
        return true
    }

    private fun syncConfigToRemote(): Boolean {
        val synced = transport.setRuntimeGenerationConfig(runtimeGenerationConfig)
        if (!synced) {
            recordError(REMOTE_ERROR_RUNTIME, "remote set config failed")
            return false
        }
        return true
    }

    private fun recordError(code: String, detail: String?) {
        lastBridgeError = BridgeError(code = code, detail = detail)
    }

    private fun clearError() {
        lastBridgeError = null
    }
}
