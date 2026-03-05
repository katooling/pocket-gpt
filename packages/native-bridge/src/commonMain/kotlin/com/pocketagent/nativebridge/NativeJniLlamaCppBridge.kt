package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog

class NativeJniLlamaCppBridge(
    private val libraryName: String = "pocket_llama",
    private val nativeApi: NativeApi = JniNativeApi(),
    private val libraryLoader: (String) -> Unit = System::loadLibrary,
    private val supportedModels: Set<String> = setOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    ),
    private val fallbackBridge: LlamaCppRuntimeBridge = AdbDeviceLlamaCppBridge(),
    private val fallbackEnabled: Boolean = defaultFallbackEnabled(),
) : LlamaCppRuntimeBridge {
    private var initialized = false
    private var runtimeReady = false
    private var usingFallback = false
    @Volatile
    private var activeRequestId: String? = null

    override fun isReady(): Boolean {
        ensureRuntimeInitialized()
        return runtimeReady
    }

    override fun listAvailableModels(): List<String> = supportedModels.sorted()

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        ensureRuntimeInitialized()
        if (!runtimeReady || !supportedModels.contains(modelId)) {
            return false
        }
        if (usingFallback) {
            return fallbackBridge.loadModel(modelId, modelPath)
        }
        val normalizedModelPath = modelPath?.trim().orEmpty()
        if (normalizedModelPath.isBlank()) {
            return false
        }
        return runCatching { nativeApi.loadModel(modelId, normalizedModelPath) }.getOrDefault(false)
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
            }.getOrElse {
                return GenerationResult(
                    finishReason = GenerationFinishReason.ERROR,
                    tokenCount = tokenCount,
                    firstTokenMs = firstTokenMs,
                    totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                    cancelled = false,
                    errorCode = "JNI_EXCEPTION",
                )
            }
            val finishReason = status.finishReason
            GenerationResult(
                finishReason = finishReason,
                tokenCount = tokenCount,
                firstTokenMs = firstTokenMs,
                totalMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L),
                cancelled = finishReason == GenerationFinishReason.CANCELLED,
                errorCode = status.errorCode,
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
            return false
        }
        if (usingFallback) {
            return fallbackBridge.cancelGeneration()
        }
        return runCatching { nativeApi.cancelGeneration() }.getOrDefault(false)
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

    private fun ensureRuntimeInitialized() {
        if (initialized) {
            return
        }
        initialized = true
        val nativeReady = runCatching {
            libraryLoader(libraryName)
            nativeApi.initialize()
        }.getOrDefault(false)
        if (nativeReady) {
            runtimeReady = true
            usingFallback = false
            return
        }

        if (fallbackEnabled && fallbackBridge.isReady()) {
            runtimeReady = true
            usingFallback = true
            return
        }

        runtimeReady = false
        usingFallback = false
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
        fun loadModel(modelId: String, modelPath: String): Boolean
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
    }

    private class JniNativeApi : NativeApi {
        external fun nativeInitialize(): Boolean
        external fun nativeLoadModel(modelId: String, modelPath: String): Boolean
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

        override fun initialize(): Boolean = nativeInitialize()

        override fun loadModel(modelId: String, modelPath: String): Boolean = nativeLoadModel(modelId, modelPath)

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
    }

    companion object {
        const val ENABLE_ADB_FALLBACK_ENV: String = "POCKETGPT_ENABLE_ADB_FALLBACK"

        private fun defaultFallbackEnabled(): Boolean {
            val raw = System.getenv(ENABLE_ADB_FALLBACK_ENV)
                ?.trim()
                ?.lowercase()
                ?: return false
            return raw in setOf("1", "true", "yes")
        }
    }
}
