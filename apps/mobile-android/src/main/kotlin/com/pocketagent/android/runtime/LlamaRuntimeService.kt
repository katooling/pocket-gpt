package com.pocketagent.android.runtime

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.pocketagent.android.BuildConfig
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.LlamaCppRuntimeBridge
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val DEFAULT_COMMAND_TIMEOUT_MS = 5_000L
private const val LOAD_MODEL_TIMEOUT_MS = 120_000L
private const val UNLOAD_MODEL_TIMEOUT_MS = 15_000L
private const val SET_CONFIG_TIMEOUT_MS = 15_000L
private const val LIST_MODELS_TIMEOUT_MS = 15_000L
private const val REMOTE_ERROR_PROCESS_DIED = "REMOTE_PROCESS_DIED"
private const val REMOTE_ERROR_BIND_FAILED = "REMOTE_BIND_FAILED"
private const val REMOTE_ERROR_TIMEOUT = "REMOTE_TIMEOUT"
private const val REMOTE_ERROR_BUSY = "REMOTE_BUSY"
private const val REMOTE_ERROR_RUNTIME = "REMOTE_RUNTIME_ERROR"
private const val REMOTE_ERROR_MODEL_NOT_LOADED = "REMOTE_MODEL_NOT_LOADED"
private const val ANDROID_RUNTIME_MODE_ENV = "POCKETGPT_ANDROID_RUNTIME_MODE"
private const val ANDROID_RUNTIME_MODE_REMOTE = "remote"
private const val ANDROID_RUNTIME_MODE_IN_PROCESS = "in_process"

fun createDefaultAndroidRuntimeBridge(context: Context): LlamaCppRuntimeBridge {
    return when (resolveAndroidRuntimeMode()) {
        ANDROID_RUNTIME_MODE_IN_PROCESS -> NativeJniLlamaCppBridge()
        else -> RemoteLlamaCppRuntimeBridge(context.applicationContext)
    }
}

fun createDefaultAndroidInferenceModule(
    context: Context,
): com.pocketagent.nativebridge.LlamaCppInferenceModule {
    return com.pocketagent.nativebridge.LlamaCppInferenceModule(
        runtimeBridge = createDefaultAndroidRuntimeBridge(context.applicationContext),
    )
}

internal fun resolveAndroidRuntimeMode(
    environment: Map<String, String> = System.getenv(),
    debugBuild: Boolean = BuildConfig.DEBUG,
): String {
    val defaultMode = if (debugBuild) ANDROID_RUNTIME_MODE_IN_PROCESS else ANDROID_RUNTIME_MODE_REMOTE
    return environment[ANDROID_RUNTIME_MODE_ENV]
        ?.trim()
        ?.lowercase()
        ?.takeIf { it == ANDROID_RUNTIME_MODE_IN_PROCESS || it == ANDROID_RUNTIME_MODE_REMOTE }
        ?: defaultMode
}

internal object LlamaRuntimeIpc {
    const val MSG_PING = 1
    const val MSG_SET_CONFIG = 2
    const val MSG_GET_CONFIG = 3
    const val MSG_LIST_MODELS = 4
    const val MSG_LOAD_MODEL = 5
    const val MSG_UNLOAD_MODEL = 6
    const val MSG_GENERATE_START = 7
    const val MSG_GENERATE_CANCEL = 8
    const val MSG_SUPPORTS_GPU = 9
    const val MSG_GET_VULKAN_DIAGNOSTICS = 10
    const val MSG_RUN_GPU_PROBE = 11
    const val MSG_LAST_ERROR = 12

    const val MSG_REPLY = 100
    const val MSG_STREAM_TOKEN = 101
    const val MSG_STREAM_RESULT = 102

    const val EXTRA_OK = "ok"
    const val EXTRA_ERROR_CODE = "error_code"
    const val EXTRA_ERROR_DETAIL = "error_detail"
    const val EXTRA_SERVICE_EPOCH = "service_epoch"

    const val EXTRA_MODEL_ID = "model_id"
    const val EXTRA_MODEL_PATH = "model_path"
    const val EXTRA_MODEL_VERSION = "model_version"
    const val EXTRA_LAYER_LADDER = "layer_ladder"

    const val EXTRA_THREADS = "n_threads"
    const val EXTRA_THREADS_BATCH = "n_threads_batch"
    const val EXTRA_BATCH = "n_batch"
    const val EXTRA_UBATCH = "n_ubatch"
    const val EXTRA_CTX = "n_ctx"
    const val EXTRA_GPU_ENABLED = "gpu_enabled"
    const val EXTRA_GPU_LAYERS = "gpu_layers"
    const val EXTRA_KV_QUANTIZED = "kv_quantized"
    const val EXTRA_SAMPLING_TEMPERATURE = "sampling_temperature"
    const val EXTRA_SAMPLING_TOP_K = "sampling_top_k"
    const val EXTRA_SAMPLING_TOP_P = "sampling_top_p"
    const val EXTRA_SPECULATIVE_ENABLED = "speculative_enabled"
    const val EXTRA_SPECULATIVE_DRAFT_MODEL_ID = "speculative_draft_model_id"
    const val EXTRA_SPECULATIVE_DRAFT_MODEL_PATH = "speculative_draft_model_path"
    const val EXTRA_SPECULATIVE_DRAFT_MAX = "speculative_draft_max"
    const val EXTRA_SPECULATIVE_DRAFT_MIN = "speculative_draft_min"

    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_PROMPT = "prompt"
    const val EXTRA_MAX_TOKENS = "max_tokens"
    const val EXTRA_CACHE_KEY = "cache_key"
    const val EXTRA_CACHE_POLICY = "cache_policy"
    const val EXTRA_TOKEN = "token"

    const val EXTRA_RESULT_FINISH_REASON = "result_finish_reason"
    const val EXTRA_RESULT_TOKEN_COUNT = "result_token_count"
    const val EXTRA_RESULT_FIRST_TOKEN_MS = "result_first_token_ms"
    const val EXTRA_RESULT_TOTAL_MS = "result_total_ms"
    const val EXTRA_RESULT_CANCELLED = "result_cancelled"
    const val EXTRA_RESULT_PREFILL_MS = "result_prefill_ms"
    const val EXTRA_RESULT_DECODE_MS = "result_decode_ms"
    const val EXTRA_RESULT_TOKENS_PER_SEC = "result_tokens_per_sec"
    const val EXTRA_RESULT_PEAK_RSS_MB = "result_peak_rss_mb"

    const val EXTRA_RUNTIME_SUPPORTED = "runtime_supported"
    const val EXTRA_DRIVER_NAME = "driver_name"
    const val EXTRA_DRIVER_VERSION = "driver_version"
    const val EXTRA_VULKAN_DIAGNOSTICS_JSON = "vulkan_diagnostics_json"
    const val EXTRA_AVAILABLE_MODELS = "available_models"
    const val EXTRA_LAST_ERROR_CODE = "last_error_code"
    const val EXTRA_LAST_ERROR_DETAIL = "last_error_detail"

    const val EXTRA_PROBE_STATUS = "probe_status"
    const val EXTRA_PROBE_MAX_LAYERS = "probe_max_layers"
    const val EXTRA_PROBE_REASON = "probe_reason"
    const val EXTRA_PROBE_DETAIL = "probe_detail"
}

internal interface RemoteRuntimeTransport {
    fun epoch(): Long
    fun ping(): Boolean
    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig): Boolean
    fun getRuntimeGenerationConfig(): RuntimeGenerationConfig
    fun listAvailableModels(): List<String>
    fun loadModel(modelId: String, modelPath: String?): Boolean
    fun unloadModel(): Boolean
    fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult
    fun cancelGeneration(requestId: String?): Boolean
    fun supportsGpuOffload(): Boolean
    fun vulkanDiagnosticsJson(): String?
    fun lastError(): BridgeError?
    fun runGpuProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult
}

internal class MessengerRemoteRuntimeTransport(
    context: Context,
) : RemoteRuntimeTransport {
    private val appContext = context.applicationContext
    private val connectionEpoch = AtomicLong(1L)
    private val nextCorrelationId = AtomicInteger(1)
    private val pendingReplies = mutableMapOf<Int, LinkedBlockingQueue<Bundle>>()
    private val pendingStreams = mutableMapOf<Int, StreamWaiter>()
    private val transportLock = Any()

    @Volatile
    private var remoteMessenger: Messenger? = null
    @Volatile
    private var bindConnection: ServiceConnection? = null
    @Volatile
    private var bindLatch: CountDownLatch? = null

    private val incomingHandler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            LlamaRuntimeIpc.MSG_REPLY -> {
                val reply = synchronized(transportLock) { pendingReplies.remove(message.arg1) }
                reply?.offer(message.data ?: Bundle())
                true
            }
            LlamaRuntimeIpc.MSG_STREAM_TOKEN -> {
                val waiter = synchronized(transportLock) { pendingStreams[message.arg1] }
                waiter?.onToken(message.data?.getString(LlamaRuntimeIpc.EXTRA_TOKEN).orEmpty())
                true
            }
            LlamaRuntimeIpc.MSG_STREAM_RESULT -> {
                val waiter = synchronized(transportLock) { pendingStreams.remove(message.arg1) }
                waiter?.resultQueue?.offer(parseGenerationResult(message.data ?: Bundle()))
                true
            }
            else -> false
        }
    }
    private val replyMessenger = Messenger(incomingHandler)

    override fun epoch(): Long = connectionEpoch.get()

    override fun ping(): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_PING,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_SET_CONFIG,
            payload = config.toBundle(),
            timeoutMs = SET_CONFIG_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_GET_CONFIG,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return RuntimeGenerationConfig.default()
        return reply.toRuntimeGenerationConfig()
    }

    override fun listAvailableModels(): List<String> {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_LIST_MODELS,
            payload = Bundle(),
            timeoutMs = LIST_MODELS_TIMEOUT_MS,
        ) ?: return emptyList()
        return reply.getStringArrayList(LlamaRuntimeIpc.EXTRA_AVAILABLE_MODELS).orEmpty()
    }

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_LOAD_MODEL,
            payload = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_MODEL_ID, modelId)
                putString(LlamaRuntimeIpc.EXTRA_MODEL_PATH, modelPath)
            },
            timeoutMs = LOAD_MODEL_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun unloadModel(): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_UNLOAD_MODEL,
            payload = Bundle(),
            timeoutMs = UNLOAD_MODEL_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val correlationId = nextCorrelationId.getAndIncrement()
        val waiter = StreamWaiter(onToken = onToken)
        synchronized(transportLock) {
            pendingStreams[correlationId] = waiter
        }
        val remote = ensureConnected() ?: run {
            synchronized(transportLock) { pendingStreams.remove(correlationId) }
            return remoteDiedGenerationResult()
        }
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_GENERATE_START).apply {
            arg1 = correlationId
            data = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_REQUEST_ID, requestId)
                putString(LlamaRuntimeIpc.EXTRA_PROMPT, prompt)
                putInt(LlamaRuntimeIpc.EXTRA_MAX_TOKENS, maxTokens)
                putString(LlamaRuntimeIpc.EXTRA_CACHE_KEY, cacheKey)
                putInt(LlamaRuntimeIpc.EXTRA_CACHE_POLICY, cachePolicy.code)
            }
            replyTo = replyMessenger
        }
        try {
            remote.send(message)
        } catch (_: RemoteException) {
            handleDisconnectLocked()
            synchronized(transportLock) { pendingStreams.remove(correlationId) }
            return remoteDiedGenerationResult()
        }
        return waiter.resultQueue.take()
    }

    override fun cancelGeneration(requestId: String?): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_GENERATE_CANCEL,
            payload = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_REQUEST_ID, requestId)
            },
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
    }

    override fun supportsGpuOffload(): Boolean {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_SUPPORTS_GPU,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return false
        return reply.getBoolean(LlamaRuntimeIpc.EXTRA_RUNTIME_SUPPORTED, false)
    }

    override fun vulkanDiagnosticsJson(): String? {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_GET_VULKAN_DIAGNOSTICS,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return null
        return reply.getString(LlamaRuntimeIpc.EXTRA_VULKAN_DIAGNOSTICS_JSON)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun lastError(): BridgeError? {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_LAST_ERROR,
            payload = Bundle(),
            timeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        ) ?: return BridgeError(code = REMOTE_ERROR_PROCESS_DIED)
        val code = reply.getString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_CODE).orEmpty().trim()
        if (code.isEmpty()) {
            return null
        }
        return BridgeError(
            code = code,
            detail = reply.getString(LlamaRuntimeIpc.EXTRA_LAST_ERROR_DETAIL),
        )
    }

    override fun runGpuProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult {
        val reply = sendCommand(
            what = LlamaRuntimeIpc.MSG_RUN_GPU_PROBE,
            payload = Bundle().apply {
                putString(LlamaRuntimeIpc.EXTRA_MODEL_ID, request.modelId)
                putString(LlamaRuntimeIpc.EXTRA_MODEL_VERSION, request.modelVersion)
                putString(LlamaRuntimeIpc.EXTRA_MODEL_PATH, request.modelPath)
                putIntegerArrayList(LlamaRuntimeIpc.EXTRA_LAYER_LADDER, ArrayList(request.layerLadder))
            },
            timeoutMs = timeoutMs,
        ) ?: return GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.PROBE_PROCESS_DIED,
            detail = "probe_transport_disconnected",
        )
        val transportErrorCode = reply.getString(LlamaRuntimeIpc.EXTRA_ERROR_CODE).orEmpty()
        if (!reply.getBoolean(LlamaRuntimeIpc.EXTRA_OK, false) && !reply.containsKey(LlamaRuntimeIpc.EXTRA_PROBE_STATUS)) {
            val reason = when (transportErrorCode) {
                REMOTE_ERROR_TIMEOUT -> GpuProbeFailureReason.PROBE_TIMEOUT
                REMOTE_ERROR_BIND_FAILED -> GpuProbeFailureReason.PROBE_BIND_FAILED
                REMOTE_ERROR_PROCESS_DIED -> GpuProbeFailureReason.PROBE_PROCESS_DIED
                else -> GpuProbeFailureReason.UNKNOWN
            }
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = reason,
                detail = reply.getString(LlamaRuntimeIpc.EXTRA_ERROR_DETAIL),
            )
        }
        return GpuProbeResult(
            status = reply.getString(LlamaRuntimeIpc.EXTRA_PROBE_STATUS)
                ?.let { runCatching { GpuProbeStatus.valueOf(it) }.getOrNull() }
                ?: GpuProbeStatus.FAILED,
            maxStableGpuLayers = reply.getInt(LlamaRuntimeIpc.EXTRA_PROBE_MAX_LAYERS, 0),
            failureReason = reply.getString(LlamaRuntimeIpc.EXTRA_PROBE_REASON)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { GpuProbeFailureReason.valueOf(it) }.getOrNull() },
            detail = reply.getString(LlamaRuntimeIpc.EXTRA_PROBE_DETAIL),
        )
    }

    private fun sendCommand(
        what: Int,
        payload: Bundle,
        timeoutMs: Long,
    ): Bundle? {
        val remote = ensureConnected() ?: return remoteFailureReply(REMOTE_ERROR_BIND_FAILED)
        val correlationId = nextCorrelationId.getAndIncrement()
        val queue = LinkedBlockingQueue<Bundle>(1)
        synchronized(transportLock) {
            pendingReplies[correlationId] = queue
        }
        val message = Message.obtain(null, what).apply {
            arg1 = correlationId
            data = payload
            replyTo = replyMessenger
        }
        try {
            remote.send(message)
        } catch (_: RemoteException) {
            synchronized(transportLock) { pendingReplies.remove(correlationId) }
            handleDisconnectLocked()
            return remoteFailureReply(REMOTE_ERROR_PROCESS_DIED)
        }
        val reply = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(transportLock) { pendingReplies.remove(correlationId) }
        return reply ?: remoteFailureReply(REMOTE_ERROR_TIMEOUT)
    }

    private fun ensureConnected(): Messenger? {
        remoteMessenger?.let { return it }
        val latch = synchronized(transportLock) {
            remoteMessenger?.let { return it }
            bindLatch?.let { return@synchronized it }
            val connectedLatch = CountDownLatch(1)
            bindLatch = connectedLatch
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    remoteMessenger = Messenger(service)
                    synchronized(transportLock) {
                        bindLatch?.countDown()
                        bindLatch = null
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    handleDisconnectLocked()
                }

                override fun onBindingDied(name: ComponentName?) {
                    handleDisconnectLocked()
                }

                override fun onNullBinding(name: ComponentName?) {
                    handleDisconnectLocked()
                }
            }
            bindConnection = connection
            val bound = appContext.bindService(
                Intent(appContext, LlamaRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                bindConnection = null
                bindLatch = null
                connectedLatch.countDown()
            }
            connectedLatch
        }
        latch.await(DEFAULT_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return remoteMessenger
    }

    private fun handleDisconnectLocked() {
        remoteMessenger = null
        bindConnection = null
        connectionEpoch.incrementAndGet()
        val replyQueues = synchronized(transportLock) {
            val commandQueues = pendingReplies.values.toList()
            val streamWaiters = pendingStreams.values.toList()
            pendingReplies.clear()
            pendingStreams.clear()
            bindLatch?.countDown()
            bindLatch = null
            Pair(commandQueues, streamWaiters)
        }
        replyQueues.first.forEach { it.offer(remoteFailureReply(REMOTE_ERROR_PROCESS_DIED)) }
        replyQueues.second.forEach { it.resultQueue.offer(remoteDiedGenerationResult()) }
    }

    private fun remoteFailureReply(code: String): Bundle {
        return Bundle().apply {
            putBoolean(LlamaRuntimeIpc.EXTRA_OK, false)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_CODE, code)
            putString(LlamaRuntimeIpc.EXTRA_ERROR_DETAIL, code.lowercase())
        }
    }

    private fun remoteDiedGenerationResult(): GenerationResult {
        return GenerationResult(
            finishReason = GenerationFinishReason.ERROR,
            tokenCount = 0,
            firstTokenMs = -1L,
            totalMs = 0L,
            cancelled = false,
            errorCode = REMOTE_ERROR_PROCESS_DIED,
        )
    }

    private fun parseGenerationResult(bundle: Bundle): GenerationResult {
        val finishReason = bundle.getString(LlamaRuntimeIpc.EXTRA_RESULT_FINISH_REASON)
            ?.let { runCatching { GenerationFinishReason.valueOf(it) }.getOrNull() }
            ?: GenerationFinishReason.ERROR
        val prefillMs = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_PREFILL_MS) }
            ?.getLong(LlamaRuntimeIpc.EXTRA_RESULT_PREFILL_MS)
        val decodeMs = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_DECODE_MS) }
            ?.getLong(LlamaRuntimeIpc.EXTRA_RESULT_DECODE_MS)
        val tokensPerSec = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_TOKENS_PER_SEC) }
            ?.getDouble(LlamaRuntimeIpc.EXTRA_RESULT_TOKENS_PER_SEC)
        val peakRssMb = bundle.takeIf { it.containsKey(LlamaRuntimeIpc.EXTRA_RESULT_PEAK_RSS_MB) }
            ?.getDouble(LlamaRuntimeIpc.EXTRA_RESULT_PEAK_RSS_MB)
        return GenerationResult(
            finishReason = finishReason,
            tokenCount = bundle.getInt(LlamaRuntimeIpc.EXTRA_RESULT_TOKEN_COUNT, 0),
            firstTokenMs = bundle.getLong(LlamaRuntimeIpc.EXTRA_RESULT_FIRST_TOKEN_MS, -1L),
            totalMs = bundle.getLong(LlamaRuntimeIpc.EXTRA_RESULT_TOTAL_MS, 0L),
            cancelled = bundle.getBoolean(LlamaRuntimeIpc.EXTRA_RESULT_CANCELLED, false),
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            tokensPerSec = tokensPerSec,
            peakRssMb = peakRssMb,
            errorCode = bundle.getString(LlamaRuntimeIpc.EXTRA_ERROR_CODE),
        )
    }

    private data class StreamWaiter(
        val resultQueue: LinkedBlockingQueue<GenerationResult> = LinkedBlockingQueue(1),
        val onToken: (String) -> Unit,
    )
}

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

class LlamaRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var primaryBridge: NativeJniLlamaCppBridge = NativeJniLlamaCppBridge(fallbackEnabled = false)
    internal var probeRunnerFactory: () -> GpuProbeRunner = { GpuProbeRunner() }
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

    override fun onBind(intent: Intent?): IBinder = messenger.binder

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
        runCatching { replyTo.send(message) }
    }

    private fun sendStreamToken(replyTo: Messenger, correlationId: Int, token: String) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_STREAM_TOKEN).apply {
            arg1 = correlationId
            data = Bundle().apply { putString(LlamaRuntimeIpc.EXTRA_TOKEN, token) }
        }
        runCatching { replyTo.send(message) }
    }

    private fun sendStreamResult(replyTo: Messenger, correlationId: Int, result: GenerationResult) {
        val message = Message.obtain(null, LlamaRuntimeIpc.MSG_STREAM_RESULT).apply {
            arg1 = correlationId
            data = result.toBundle().withEpoch(serviceEpoch)
        }
        runCatching { replyTo.send(message) }
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
}

private fun Bundle?.toProbeRequestOrNull(): GpuProbeRequest? {
    val source = this ?: return null
    val modelId = source.getString(LlamaRuntimeIpc.EXTRA_MODEL_ID).orEmpty().trim()
    val modelVersion = source.getString(LlamaRuntimeIpc.EXTRA_MODEL_VERSION).orEmpty().trim()
    val modelPath = source.getString(LlamaRuntimeIpc.EXTRA_MODEL_PATH).orEmpty().trim()
    val ladder = source.getIntegerArrayList(LlamaRuntimeIpc.EXTRA_LAYER_LADDER)
        ?.map { it.toInt() }
        ?.filter { it > 0 }
        ?.distinct()
        .orEmpty()
    if (modelId.isBlank() || modelPath.isBlank() || ladder.isEmpty()) {
        return null
    }
    return GpuProbeRequest(
        modelId = modelId,
        modelVersion = modelVersion,
        modelPath = modelPath,
        layerLadder = ladder,
    )
}

private fun RuntimeGenerationConfig.toBundle(): Bundle {
    return Bundle().apply {
        putInt(LlamaRuntimeIpc.EXTRA_THREADS, nThreads)
        putInt(LlamaRuntimeIpc.EXTRA_THREADS_BATCH, nThreadsBatch)
        putInt(LlamaRuntimeIpc.EXTRA_BATCH, nBatch)
        putInt(LlamaRuntimeIpc.EXTRA_UBATCH, nUbatch)
        putInt(LlamaRuntimeIpc.EXTRA_CTX, nCtx)
        putBoolean(LlamaRuntimeIpc.EXTRA_GPU_ENABLED, gpuEnabled)
        putInt(LlamaRuntimeIpc.EXTRA_GPU_LAYERS, gpuLayers)
        putBoolean(LlamaRuntimeIpc.EXTRA_KV_QUANTIZED, quantizedKvCache)
        putFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TEMPERATURE, sampling.temperature)
        putInt(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_K, sampling.topK)
        putFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_P, sampling.topP)
        putBoolean(LlamaRuntimeIpc.EXTRA_SPECULATIVE_ENABLED, speculativeEnabled)
        putString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_ID, speculativeDraftModelId)
        putString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_PATH, speculativeDraftModelPath)
        putInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MAX, speculativeMaxDraftTokens)
        putInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MIN, speculativeMinDraftTokens)
    }
}

private fun Bundle.toRuntimeGenerationConfig(): RuntimeGenerationConfig {
    return RuntimeGenerationConfig(
        nThreads = getInt(LlamaRuntimeIpc.EXTRA_THREADS, 0),
        nThreadsBatch = getInt(LlamaRuntimeIpc.EXTRA_THREADS_BATCH, 0),
        nBatch = getInt(LlamaRuntimeIpc.EXTRA_BATCH, 512),
        nUbatch = getInt(LlamaRuntimeIpc.EXTRA_UBATCH, 512),
        nCtx = getInt(LlamaRuntimeIpc.EXTRA_CTX, 2048),
        gpuEnabled = getBoolean(LlamaRuntimeIpc.EXTRA_GPU_ENABLED, false),
        gpuLayers = getInt(LlamaRuntimeIpc.EXTRA_GPU_LAYERS, 0),
        quantizedKvCache = getBoolean(LlamaRuntimeIpc.EXTRA_KV_QUANTIZED, true),
        sampling = com.pocketagent.nativebridge.RuntimeSamplingConfig(
            temperature = getFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TEMPERATURE, 0.7f),
            topK = getInt(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_K, 40),
            topP = getFloat(LlamaRuntimeIpc.EXTRA_SAMPLING_TOP_P, 0.95f),
        ),
        speculativeEnabled = getBoolean(LlamaRuntimeIpc.EXTRA_SPECULATIVE_ENABLED, false),
        speculativeDraftModelId = getString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_ID),
        speculativeDraftModelPath = getString(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MODEL_PATH),
        speculativeMaxDraftTokens = getInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MAX, 6),
        speculativeMinDraftTokens = getInt(LlamaRuntimeIpc.EXTRA_SPECULATIVE_DRAFT_MIN, 2),
    )
}

private fun GenerationResult.toBundle(): Bundle {
    return Bundle().apply {
        putString(LlamaRuntimeIpc.EXTRA_RESULT_FINISH_REASON, finishReason.name)
        putInt(LlamaRuntimeIpc.EXTRA_RESULT_TOKEN_COUNT, tokenCount)
        putLong(LlamaRuntimeIpc.EXTRA_RESULT_FIRST_TOKEN_MS, firstTokenMs)
        putLong(LlamaRuntimeIpc.EXTRA_RESULT_TOTAL_MS, totalMs)
        putBoolean(LlamaRuntimeIpc.EXTRA_RESULT_CANCELLED, cancelled)
        prefillMs?.let { putLong(LlamaRuntimeIpc.EXTRA_RESULT_PREFILL_MS, it) }
        decodeMs?.let { putLong(LlamaRuntimeIpc.EXTRA_RESULT_DECODE_MS, it) }
        tokensPerSec?.let { putDouble(LlamaRuntimeIpc.EXTRA_RESULT_TOKENS_PER_SEC, it) }
        peakRssMb?.let { putDouble(LlamaRuntimeIpc.EXTRA_RESULT_PEAK_RSS_MB, it) }
        putString(LlamaRuntimeIpc.EXTRA_ERROR_CODE, errorCode)
    }
}

private fun Bundle.withOk(): Bundle {
    putBoolean(LlamaRuntimeIpc.EXTRA_OK, true)
    return this
}

private fun Bundle.withEpoch(epoch: Long): Bundle {
    putLong(LlamaRuntimeIpc.EXTRA_SERVICE_EPOCH, epoch)
    return this
}
