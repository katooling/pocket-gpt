package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog

data class BridgeError(
    val code: String,
    val detail: String? = null,
)

class NativeJniLlamaCppBridge(
    private val libraryName: String = "pocket_llama",
    private val nativeApi: NativeApi = JniNativeApi(),
    private val libraryLoader: (String) -> Unit = System::loadLibrary,
    private val supportedModels: Set<String> = ModelCatalog.bridgeSupportedModels().toSet(),
    private val fallbackBridge: LlamaCppRuntimeBridge = AdbDeviceLlamaCppBridge(),
    private val fallbackEnabled: Boolean = defaultFallbackEnabled(),
    private val gpuOffloadAllowed: Boolean = defaultGpuOffloadEnabled(),
) : LlamaCppRuntimeBridge {
    private var initialized = false
    private var runtimeReady = false
    private var usingFallback = false
    @Volatile
    private var lastBridgeError: BridgeError? = null
    @Volatile
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    @Volatile
    private var activeRequestId: String? = null

    override fun isReady(): Boolean {
        ensureRuntimeInitialized()
        return runtimeReady
    }

    override fun listAvailableModels(): List<String> = supportedModels.sorted()

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeGenerationConfig = config
        if (usingFallback) {
            fallbackBridge.setRuntimeGenerationConfig(config)
        }
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeGenerationConfig

    override fun supportsGpuOffload(): Boolean {
        if (!gpuOffloadAllowed) {
            return false
        }
        ensureRuntimeInitialized()
        return if (usingFallback) {
            fallbackBridge.supportsGpuOffload()
        } else {
            runCatching { nativeApi.supportsGpuOffload() }
                .onSuccess { clearBridgeError() }
                .onFailure { error -> recordBridgeError("JNI_GPU_SUPPORT_EXCEPTION", error) }
                .getOrElse { false }
        }
    }

    override fun vulkanDiagnosticsJson(): String? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return null
        }
        return runCatching { nativeApi.vulkanDiagnosticsJson() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_GPU_DIAGNOSTICS_EXCEPTION", error) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun generateSyncProbe(
        prompt: String,
        maxTokens: Int,
        cachePolicy: CachePolicy = CachePolicy.OFF,
    ): Boolean {
        ensureRuntimeInitialized()
        if (!runtimeReady || usingFallback) {
            return false
        }
        return runCatching {
            nativeApi.generate(
                prompt = prompt,
                maxTokens = maxTokens,
                cacheKey = null,
                cachePolicyCode = cachePolicy.code,
            )
            true
        }.onSuccess {
            clearBridgeError()
        }.onFailure { error ->
            recordBridgeError("JNI_GENERATE_SYNC_EXCEPTION", error)
        }.getOrElse { false }
    }

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            recordBridgeError("RUNTIME_NOT_READY", "Runtime is not initialized.")
            return false
        }
        val validation = ModelCatalog.validateBridgeLoad(
            modelId = modelId,
            modelPath = modelPath,
            supportedModels = supportedModels,
        )
        if (!validation.accepted) {
            recordBridgeError(validation.code ?: "MODEL_INVALID", validation.detail)
            return false
        }
        if (usingFallback) {
            fallbackBridge.setRuntimeGenerationConfig(runtimeGenerationConfig)
            return fallbackBridge.loadModel(modelId, validation.normalizedModelPath)
        }
        val normalizedModelPath = validation.normalizedModelPath.orEmpty()
        val config = runtimeGenerationConfig
        val gpuEnabledAndSupported = gpuOffloadAllowed && config.gpuEnabled && supportsGpuOffload()
        val requestedGpuLayers = if (gpuEnabledAndSupported) config.gpuLayers else 0
        val primaryAttempt = runCatching {
            nativeApi.loadModel(
                modelId = modelId,
                modelPath = normalizedModelPath,
                nThreads = config.nThreads,
                nThreadsBatch = config.nThreadsBatch,
                nBatch = config.nBatch,
                nUbatch = config.nUbatch,
                nGpuLayers = requestedGpuLayers,
            )
        }
        if (primaryAttempt.getOrNull() == true) {
            clearBridgeError()
            return true
        }

        if (requestedGpuLayers > 0) {
            val cpuFallbackAttempt = runCatching {
                nativeApi.loadModel(
                    modelId = modelId,
                    modelPath = normalizedModelPath,
                    nThreads = config.nThreads,
                    nThreadsBatch = config.nThreadsBatch,
                    nBatch = config.nBatch,
                    nUbatch = config.nUbatch,
                    nGpuLayers = 0,
                )
            }
            if (cpuFallbackAttempt.getOrNull() == true) {
                clearBridgeError()
                return true
            }
            val finalError = cpuFallbackAttempt.exceptionOrNull() ?: primaryAttempt.exceptionOrNull()
            if (finalError != null) {
                recordBridgeError("JNI_LOAD_EXCEPTION", finalError)
            } else {
                recordBridgeError("JNI_LOAD_FAILED", "modelId=$modelId|gpu_layers=$requestedGpuLayers|cpu_retry=true")
            }
            return false
        }

        val finalError = primaryAttempt.exceptionOrNull()
        if (finalError != null) {
            recordBridgeError("JNI_LOAD_EXCEPTION", finalError)
        } else {
            recordBridgeError("JNI_LOAD_FAILED", "modelId=$modelId")
        }
        return false
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val startedMs = System.currentTimeMillis()
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            return GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "RUNTIME_UNAVAILABLE",
            )
        }
        if (usingFallback) {
            return fallbackBridge.generate(
                requestId = requestId,
                prompt = prompt,
                maxTokens = maxTokens,
                cacheKey = cacheKey,
                cachePolicy = cachePolicy,
                onToken = onToken,
            )
        }
        var tokenCount = 0
        var firstTokenMs = -1L
        activeRequestId = requestId
        return try {
            val status = runCatching {
                nativeApi.generateStream(
                    requestId = requestId,
                    prompt = prompt,
                    maxTokens = maxTokens,
                    cacheKey = cacheKey,
                    cachePolicyCode = cachePolicy.code,
                    onToken = NativeApi.TokenCallback { token ->
                        if (firstTokenMs < 0L) {
                            firstTokenMs = System.currentTimeMillis() - startedMs
                        }
                        tokenCount += 1
                        onToken(token)
                    },
                )
            }.onFailure { error ->
                recordBridgeError("JNI_GENERATE_EXCEPTION", error)
            }.getOrElse {
                return GenerationResult(
                    finishReason = GenerationFinishReason.ERROR,
                    tokenCount = tokenCount,
                    firstTokenMs = firstTokenMs,
                    totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                    cancelled = false,
                    errorCode = "JNI_GENERATE_EXCEPTION",
                )
            }
            val finishReason = status.finishReason
            val statusErrorCode = status.errorCode?.trim()?.takeIf { it.isNotEmpty() }
            if (
                finishReason == GenerationFinishReason.ERROR ||
                finishReason == GenerationFinishReason.CALLBACK_ERROR ||
                finishReason == GenerationFinishReason.UTF8_STREAM_ERROR
            ) {
                recordBridgeError(
                    code = statusErrorCode ?: "JNI_STREAM_ERROR",
                    detail = "requestId=$requestId",
                )
            } else if (finishReason != GenerationFinishReason.CANCELLED) {
                clearBridgeError()
            }
            GenerationResult(
                finishReason = finishReason,
                tokenCount = tokenCount,
                firstTokenMs = firstTokenMs,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = finishReason == GenerationFinishReason.CANCELLED,
                prefillMs = if (firstTokenMs >= 0) firstTokenMs else null,
                decodeMs = if (firstTokenMs >= 0) {
                    ((System.currentTimeMillis() - startedMs).coerceAtLeast(0L) - firstTokenMs).coerceAtLeast(0L)
                } else {
                    null
                },
                tokensPerSec = if (tokenCount > 0 && firstTokenMs >= 0) {
                    val decodeMs = ((System.currentTimeMillis() - startedMs).coerceAtLeast(0L) - firstTokenMs).coerceAtLeast(1L)
                    tokenCount.toDouble() / (decodeMs.toDouble() / 1000.0)
                } else {
                    null
                },
                errorCode = statusErrorCode,
            )
        } finally {
            activeRequestId = null
        }
    }

    override fun unloadModel() {
        if (!runtimeReady) {
            return
        }
        if (usingFallback) {
            fallbackBridge.unloadModel()
            return
        }
        runCatching { nativeApi.unloadModel() }
    }

    override fun cancelGeneration(): Boolean {
        if (!runtimeReady) {
            recordBridgeError("RUNTIME_NOT_READY", "Cancel requested while runtime not ready.")
            return false
        }
        if (usingFallback) {
            return fallbackBridge.cancelGeneration()
        }
        return runCatching { nativeApi.cancelGeneration() }
            .onSuccess { cancelled ->
                if (cancelled) {
                    clearBridgeError()
                } else {
                    recordBridgeError("JNI_CANCEL_RETURNED_FALSE", "native cancel returned false")
                }
            }
            .onFailure { error -> recordBridgeError("JNI_CANCEL_EXCEPTION", error) }
            .getOrElse { false }
    }

    override fun cancelGeneration(requestId: String): Boolean {
        if (usingFallback) {
            return fallbackBridge.cancelGeneration(requestId)
        }
        return if (activeRequestId == requestId) {
            cancelGeneration()
        } else {
            false
        }
    }

    override fun runtimeBackend(): RuntimeBackend {
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            return RuntimeBackend.UNAVAILABLE
        }
        return if (usingFallback) RuntimeBackend.ADB_FALLBACK else RuntimeBackend.NATIVE_JNI
    }

    override fun lastError(): BridgeError? = lastBridgeError

    private fun ensureRuntimeInitialized() {
        if (initialized) {
            return
        }
        initialized = true
        val nativeReady = runCatching {
            libraryLoader(libraryName)
            nativeApi.initialize()
        }.onFailure { error ->
            recordBridgeError("JNI_LIBRARY_OR_INIT_EXCEPTION", error)
        }.getOrElse { false }
        if (nativeReady) {
            runtimeReady = true
            usingFallback = false
            clearBridgeError()
            return
        }
        if (lastBridgeError == null) {
            recordBridgeError("JNI_INIT_FAILED", "native initialize returned false")
        }

        if (fallbackEnabled && fallbackBridge.isReady()) {
            runtimeReady = true
            usingFallback = true
            recordBridgeError("ADB_FALLBACK_ACTIVE", "Native runtime unavailable; using adb fallback.")
            return
        }

        runtimeReady = false
        usingFallback = false
        if (lastBridgeError == null) {
            recordBridgeError("RUNTIME_UNAVAILABLE", "Native and fallback runtimes unavailable.")
        }
    }

    private fun clearBridgeError() {
        lastBridgeError = null
    }

    private fun recordBridgeError(code: String, error: Throwable) {
        lastBridgeError = BridgeError(
            code = code,
            detail = error.message ?: error::class.simpleName,
        )
    }

    private fun recordBridgeError(code: String, detail: String?) {
        lastBridgeError = BridgeError(code = code, detail = detail)
    }

    interface NativeApi {
        fun interface TokenCallback {
            fun onToken(token: String)
        }

        data class StreamStatus(
            val finishReason: GenerationFinishReason,
            val errorCode: String? = null,
        )

        fun initialize(): Boolean
        fun loadModel(
            modelId: String,
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nBatch: Int,
            nUbatch: Int,
            nGpuLayers: Int,
        ): Boolean
        fun generateStream(
            requestId: String,
            prompt: String,
            maxTokens: Int,
            cacheKey: String?,
            cachePolicyCode: Int,
            onToken: TokenCallback,
        ): StreamStatus
        fun generate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicyCode: Int): String
        fun cancelGeneration(): Boolean
        fun unloadModel()
        fun supportsGpuOffload(): Boolean
        fun vulkanDiagnosticsJson(): String
    }

    private class JniNativeApi : NativeApi {
        external fun nativeInitialize(): Boolean
        external fun nativeLoadModel(
            modelId: String,
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nBatch: Int,
            nUbatch: Int,
            nGpuLayers: Int,
        ): Boolean
        external fun nativeGenerateStream(
            requestId: String,
            prompt: String,
            maxTokens: Int,
            cacheKey: String?,
            cachePolicy: Int,
            callback: NativeApi.TokenCallback,
        ): Int
        external fun nativeGenerate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicy: Int): String
        external fun nativeCancelGeneration(): Boolean
        external fun nativeUnloadModel()
        external fun nativeSupportsGpuOffload(): Boolean
        external fun nativeVulkanDiagnosticsJson(): String

        override fun initialize(): Boolean = nativeInitialize()

        override fun loadModel(
            modelId: String,
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nBatch: Int,
            nUbatch: Int,
            nGpuLayers: Int,
        ): Boolean {
            return nativeLoadModel(modelId, modelPath, nThreads, nThreadsBatch, nBatch, nUbatch, nGpuLayers)
        }

        override fun generateStream(
            requestId: String,
            prompt: String,
            maxTokens: Int,
            cacheKey: String?,
            cachePolicyCode: Int,
            onToken: NativeApi.TokenCallback,
        ): NativeApi.StreamStatus {
            val statusCode = nativeGenerateStream(requestId, prompt, maxTokens, cacheKey, cachePolicyCode, onToken)
            return when (statusCode) {
                0 -> NativeApi.StreamStatus(GenerationFinishReason.COMPLETED)
                1 -> NativeApi.StreamStatus(GenerationFinishReason.MAX_TOKENS)
                2 -> NativeApi.StreamStatus(GenerationFinishReason.CANCELLED, "JNI_CANCELLED")
                3 -> NativeApi.StreamStatus(GenerationFinishReason.CALLBACK_ERROR, "JNI_CALLBACK_ERROR")
                4 -> NativeApi.StreamStatus(GenerationFinishReason.UTF8_STREAM_ERROR, "JNI_UTF8_STREAM_ERROR")
                5 -> NativeApi.StreamStatus(GenerationFinishReason.ERROR, "JNI_RUNTIME_ERROR")
                else -> NativeApi.StreamStatus(GenerationFinishReason.ERROR, "JNI_UNKNOWN_STATUS_$statusCode")
            }
        }

        override fun generate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicyCode: Int): String =
            nativeGenerate(prompt, maxTokens, cacheKey, cachePolicyCode)

        override fun cancelGeneration(): Boolean = nativeCancelGeneration()

        override fun unloadModel() = nativeUnloadModel()

        override fun supportsGpuOffload(): Boolean = nativeSupportsGpuOffload()

        override fun vulkanDiagnosticsJson(): String = nativeVulkanDiagnosticsJson()
    }

    companion object {
        const val ENABLE_ADB_FALLBACK_ENV: String = "POCKETGPT_ENABLE_ADB_FALLBACK"
        const val ENABLE_GPU_OFFLOAD_ENV: String = "POCKETGPT_ENABLE_GPU_OFFLOAD"

        private fun defaultFallbackEnabled(): Boolean {
            val raw = System.getenv(ENABLE_ADB_FALLBACK_ENV)
                ?.trim()
                ?.lowercase()
                ?: return false
            return raw in setOf("1", "true", "yes")
        }

        private fun defaultGpuOffloadEnabled(): Boolean {
            val raw = System.getenv(ENABLE_GPU_OFFLOAD_ENV)
                ?.trim()
                ?.lowercase()
                ?: return true
            return raw !in setOf("0", "false", "no", "off")
        }
    }
}
