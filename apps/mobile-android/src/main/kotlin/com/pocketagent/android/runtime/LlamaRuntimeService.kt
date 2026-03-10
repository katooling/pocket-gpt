package com.pocketagent.android.runtime

import android.app.Service
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LlamaRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var primaryBridge: NativeJniLlamaCppBridge = NativeJniLlamaCppBridge(fallbackEnabled = false)
    internal var probeRunnerFactory: () -> GpuProbeRunner = { GpuProbeRunner() }
    internal val ipcSendMonitor = IpcSendMonitor(tag = LOG_TAG)
    private val serviceLock = Any()
    private var currentConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    private var activeRequestId: String? = null
    private var generationActive: Boolean = false
    private var probeActive: Boolean = false
    private val serviceEpoch: Long = System.currentTimeMillis()

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            val replyTo = message.replyTo ?: return@Handler true
            scope.launch {
                handleMessage(message = message, replyTo = replyTo)
            }
            true
        },
    )

    override fun onBind(intent: android.content.Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleMessage(message: Message, replyTo: Messenger) {
        when (message.what) {
            LlamaRuntimeIpc.MSG_PING -> sendReply(replyTo, message.arg1, okReply())
            LlamaRuntimeIpc.MSG_SET_CONFIG -> handleSetConfig(message = message, replyTo = replyTo)
            LlamaRuntimeIpc.MSG_GET_CONFIG -> sendReply(replyTo, message.arg1, currentConfig.toBundle().withOk())
            LlamaRuntimeIpc.MSG_LIST_MODELS -> handleNonStreaming(message, replyTo) {
                okReply().apply {
                    putStringArrayList(
                        LlamaRuntimeIpc.EXTRA_AVAILABLE_MODELS,
                        ArrayList(primaryBridge.listAvailableModels()),
                    )
                }
            }

            LlamaRuntimeIpc.MSG_LOAD_MODEL -> handleNonStreaming(message, replyTo) {
                val modelId = message.data?.getString(LlamaRuntimeIpc.EXTRA_MODEL_ID).orEmpty()
                val modelPath = message.data?.getString(LlamaRuntimeIpc.EXTRA_MODEL_PATH)
                val loaded = primaryBridge.loadModel(modelId, modelPath)
                if (loaded) okReply() else errorReply(primaryBridge.lastError())
            }

            LlamaRuntimeIpc.MSG_UNLOAD_MODEL -> handleNonStreaming(message, replyTo) {
                primaryBridge.unloadModel()
                okReply()
            }

            LlamaRuntimeIpc.MSG_GENERATE_START -> handleGenerate(message = message, replyTo = replyTo)
            LlamaRuntimeIpc.MSG_GENERATE_CANCEL -> {
                val requestId = message.data?.getString(LlamaRuntimeIpc.EXTRA_REQUEST_ID)
                val cancelled = if (requestId.isNullOrBlank()) {
                    primaryBridge.cancelGeneration()
                } else {
                    primaryBridge.cancelGeneration(requestId)
                }
                sendReply(replyTo, message.arg1, if (cancelled) okReply() else errorReply(primaryBridge.lastError()))
            }

            LlamaRuntimeIpc.MSG_SUPPORTS_GPU -> handleNonStreaming(message, replyTo) {
                okReply().apply {
                    putBoolean(LlamaRuntimeIpc.EXTRA_RUNTIME_SUPPORTED, primaryBridge.supportsGpuOffload())
                }
            }

            LlamaRuntimeIpc.MSG_GET_VULKAN_DIAGNOSTICS -> handleNonStreaming(message, replyTo) {
                okReply().apply {
                    putString(LlamaRuntimeIpc.EXTRA_VULKAN_DIAGNOSTICS_JSON, primaryBridge.vulkanDiagnosticsJson())
                }
            }

            LlamaRuntimeIpc.MSG_RUN_GPU_PROBE -> handleProbe(message = message, replyTo = replyTo)
            LlamaRuntimeIpc.MSG_LAST_ERROR -> {
                val error = primaryBridge.lastError()
                sendReply(
                    replyTo,
                    message.arg1,
                    okReply().apply {
                        putString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_CODE, error?.code)
                        putString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_DETAIL, error?.detail)
                    },
                )
            }
        }
    }

    private fun handleSetConfig(message: Message, replyTo: Messenger) {
        val data = message.data ?: Bundle()
        val config = data.toRuntimeGenerationConfig()
        if (isBusy()) {
            sendReply(replyTo, message.arg1, busyReply())
            return
        }
        synchronized(serviceLock) {
            currentConfig = config
            primaryBridge.setRuntimeGenerationConfig(config)
        }
        sendReply(replyTo, message.arg1, okReply())
    }

    private fun handleNonStreaming(
        message: Message,
        replyTo: Messenger,
        block: () -> Bundle,
    ) {
        if (isBusy()) {
            sendReply(replyTo, message.arg1, busyReply())
            return
        }
        val bundle = synchronized(serviceLock) {
            block()
        }
        sendReply(replyTo, message.arg1, bundle)
    }

    private fun handleGenerate(message: Message, replyTo: Messenger) {
        val data = message.data ?: Bundle()
        val requestId = data.getString(LlamaRuntimeIpc.EXTRA_REQUEST_ID).orEmpty()
        val prompt = data.getString(LlamaRuntimeIpc.EXTRA_PROMPT).orEmpty()
        val maxTokens = data.getInt(LlamaRuntimeIpc.EXTRA_MAX_TOKENS, 0)
        val cacheKey = data.getString(LlamaRuntimeIpc.EXTRA_CACHE_KEY)
        val cachePolicy = CachePolicy.entries.firstOrNull { it.code == data.getInt(LlamaRuntimeIpc.EXTRA_CACHE_POLICY, 0) }
            ?: CachePolicy.OFF
        synchronized(serviceLock) {
            if (generationActive || probeActive) {
                sendStreamResult(replyTo, message.arg1, busyGenerationResult())
                return
            }
            generationActive = true
            activeRequestId = requestId
        }
        val result = try {
            primaryBridge.generate(
                requestId = requestId,
                prompt = prompt,
                maxTokens = maxTokens,
                cacheKey = cacheKey,
                cachePolicy = cachePolicy,
            ) { token ->
                sendStreamToken(replyTo, message.arg1, token)
            }
        } finally {
            synchronized(serviceLock) {
                generationActive = false
                activeRequestId = null
            }
        }
        sendStreamResult(replyTo, message.arg1, result)
    }

    private fun handleProbe(message: Message, replyTo: Messenger) {
        val request = message.data.toProbeRequestOrNull()
        if (request == null) {
            sendReply(
                replyTo,
                message.arg1,
                errorReply(code = REMOTE_ERROR_RUNTIME, detail = "probe_request_invalid"),
            )
            return
        }
        synchronized(serviceLock) {
            if (generationActive || probeActive) {
                sendReply(
                    replyTo,
                    message.arg1,
                    Bundle().apply {
                        putString(LlamaRuntimeIpc.EXTRA_PROBE_STATUS, GpuProbeStatus.FAILED.name)
                        putInt(LlamaRuntimeIpc.EXTRA_PROBE_MAX_LAYERS, 0)
                        putString(LlamaRuntimeIpc.EXTRA_PROBE_REASON, GpuProbeFailureReason.UNKNOWN.name)
                        putString(LlamaRuntimeIpc.EXTRA_PROBE_DETAIL, REMOTE_ERROR_BUSY.lowercase())
                        putBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
                        putLong(LlamaRuntimeIpc.EXTRA_SERVICE_EPOCH, serviceEpoch)
                    },
                )
                return
            }
            probeActive = true
        }
        val result = try {
            probeRunnerFactory().runProbeLadder(request)
        } finally {
            synchronized(serviceLock) {
                probeActive = false
            }
        }
        sendReply(
            replyTo,
            message.arg1,
            Bundle().apply {
                putBoolean(LlamaRuntimeIpc.EXTRA_OK, result.status == GpuProbeStatus.QUALIFIED)
                putString(LlamaRuntimeIpc.EXTRA_PROBE_STATUS, result.status.name)
                putInt(LlamaRuntimeIpc.EXTRA_PROBE_MAX_LAYERS, result.maxStableGpuLayers)
                putString(LlamaRuntimeIpc.EXTRA_PROBE_REASON, result.failureReason?.name)
                putString(LlamaRuntimeIpc.EXTRA_PROBE_DETAIL, result.detail)
                putLong(LlamaRuntimeIpc.EXTRA_SERVICE_EPOCH, serviceEpoch)
            },
        )
    }

    private fun isBusy(): Boolean = synchronized(serviceLock) { generationActive || probeActive }

    private fun sendReply(replyTo: Messenger, correlationId: Int, payload: Bundle) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_REPLY).apply {
            arg1 = correlationId
            data = payload.withEpoch(serviceEpoch)
        }
        ipcSendMonitor.send(IpcMessageKind.REPLY) { replyTo.send(message) }
    }

    private fun sendStreamToken(replyTo: Messenger, correlationId: Int, token: String) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_STREAM_TOKEN).apply {
            arg1 = correlationId
            data = Bundle().apply { putString(LlamaRuntimeIpc.EXTRA_TOKEN, token) }
        }
        ipcSendMonitor.send(IpcMessageKind.STREAM_TOKEN) { replyTo.send(message) }
    }

    private fun sendStreamResult(replyTo: Messenger, correlationId: Int, result: GenerationResult) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_STREAM_RESULT).apply {
            arg1 = correlationId
            data = result.toBundle().withEpoch(serviceEpoch)
        }
        ipcSendMonitor.send(IpcMessageKind.STREAM_RESULT) { replyTo.send(message) }
    }

    private fun okReply(): Bundle = Bundle().apply { putBoolean(LlamaRuntimeIpc.EXTRA_OK, true) }

    private fun busyReply(): Bundle = errorReply(code = REMOTE_ERROR_BUSY, detail = REMOTE_ERROR_BUSY.lowercase())

    private fun errorReply(error: BridgeError?): Bundle {
        return errorReply(
            code = error?.code ?: REMOTE_ERROR_RUNTIME,
            detail = error?.detail,
        )
    }

    private fun errorReply(code: String, detail: String?): Bundle {
        return Bundle().apply {
            putBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_CODE, code)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_DETAIL, detail)
        }
    }

    private fun busyGenerationResult(): GenerationResult {
        return GenerationResult(
            finishReason = GenerationFinishReason.ERROR,
            tokenCount = 0,
            firstTokenMs = -1L,
            totalMs = 0L,
            cancelled = false,
            errorCode = REMOTE_ERROR_BUSY,
        )
    }

    private companion object {
        const val LOG_TAG = "LlamaRuntimeService"
    }
}
