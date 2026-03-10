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
    private val lifecycleLock = Any()
    private val lifecycleObserverLock = Any()
    private var initialized = false
    private var runtimeReady = false
    private var usingFallback = false
    private var activeLoadToken: Long = 0L
    private var nextObserverId: Int = 1
    private val lifecycleObservers: MutableMap<Int, (ModelLifecycleEvent) -> Unit> = mutableMapOf()
    @Volatile
    private var lastBridgeError: BridgeError? = null
    @Volatile
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    @Volatile
    private var activeRequestId: String? = null
    @Volatile
    private var loadedModel: LoadedModelInfo? = null
    @Volatile
    private var currentLifecycleEvent: ModelLifecycleEvent = ModelLifecycleEvent(
        state = ModelLifecycleState.UNLOADED,
    )

    override fun isReady(): Boolean {
        ensureRuntimeInitialized()
        return runtimeReady
    }

    override fun listAvailableModels(): List<String> = supportedModels.sorted()

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeGenerationConfig = config
        if (usingFallback) {
            fallbackBridge.setRuntimeGenerationConfig(config)
            return
        }
        if (!runtimeReady) {
            return
        }
        runCatching {
            nativeApi.setSamplingConfig(
                temperature = config.sampling.temperature,
                topK = config.sampling.topK,
                topP = config.sampling.topP,
                nKeep = config.nKeep,
            )
        }.onSuccess {
            clearBridgeError()
        }.onFailure { error ->
            recordBridgeError("JNI_SET_SAMPLING_CONFIG_EXCEPTION", error)
        }
    }

    override fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeGenerationConfig

    override fun modelLayerCount(): Int? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return fallbackBridge.modelLayerCount()
        }
        if (!runtimeReady) {
            return null
        }
        return runCatching { nativeApi.modelLayerCount() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_MODEL_LAYER_COUNT_EXCEPTION", error) }
            .getOrNull()
            ?.takeIf { it > 0 }
    }

    override fun modelSizeBytes(): Long? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return fallbackBridge.modelSizeBytes()
        }
        if (!runtimeReady) {
            return null
        }
        return runCatching { nativeApi.modelSizeBytes() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_MODEL_SIZE_EXCEPTION", error) }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    override fun estimateMaxGpuLayers(nCtx: Int): Int? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return fallbackBridge.estimateMaxGpuLayers(nCtx)
        }
        if (!runtimeReady || nCtx <= 0) {
            return null
        }
        return runCatching { nativeApi.estimateMaxGpuLayers(nCtx) }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_ESTIMATE_GPU_LAYERS_EXCEPTION", error) }
            .getOrNull()
            ?.takeIf { it >= 0 }
    }

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

    override fun setBackendProfile(profile: String) {
        ensureRuntimeInitialized()
        if (usingFallback || !runtimeReady) {
            return
        }
        runCatching { nativeApi.setBackendProfile(profile) }
    }

    override fun backendDiagnosticsJson(): String? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return null
        }
        return runCatching { nativeApi.backendDiagnosticsJson() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_GPU_DIAGNOSTICS_EXCEPTION", error) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun prefixCacheDiagnosticsLine(): String? {
        ensureRuntimeInitialized()
        if (usingFallback) {
            return null
        }
        return runCatching { nativeApi.prefixCacheDiagnosticsLine() }
            .onSuccess { clearBridgeError() }
            .onFailure { error -> recordBridgeError("JNI_PREFIX_CACHE_DIAGNOSTICS_EXCEPTION", error) }
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
        return loadModel(modelId, modelPath, ModelLoadOptions())
    }

    override fun loadModel(modelId: String, modelPath: String?, options: ModelLoadOptions): Boolean {
        val token = synchronized(lifecycleLock) {
            activeLoadToken += 1L
            activeLoadToken
        }
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADING,
                modelId = modelId,
                modelVersion = options.modelVersion,
            ),
        )
        val loaded = synchronized(lifecycleLock) {
            if (token != activeLoadToken) {
                recordBridgeError("LOAD_CANCELLED_NEWER_REQUEST", "modelId=$modelId")
                false
            } else {
                loadModelUnchecked(modelId = modelId, modelPath = modelPath, options = options)
            }
        }
        synchronized(lifecycleLock) {
            if (token != activeLoadToken) {
                if (loaded) {
                    unloadModelUnchecked()
                }
                emitLifecycleFailure(
                    modelId = modelId,
                    modelVersion = options.modelVersion,
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "modelId=$modelId",
                )
                return false
            }
            if (loaded) {
                loadedModel = LoadedModelInfo(
                    modelId = modelId,
                    modelPath = modelPath?.trim()?.takeIf { it.isNotEmpty() },
                    modelVersion = options.modelVersion,
                )
                emitLifecycleEvent(
                    ModelLifecycleEvent(
                        state = ModelLifecycleState.LOADED,
                        modelId = modelId,
                        modelVersion = options.modelVersion,
                    ),
                )
            } else {
                loadedModel = null
                emitLifecycleFailure(
                    modelId = modelId,
                    modelVersion = options.modelVersion,
                    code = mapBridgeErrorToLifecycleCode(lastBridgeError?.code),
                    detail = lastBridgeError?.detail,
                )
            }
        }
        return loaded
    }

    private fun loadModelUnchecked(modelId: String, modelPath: String?, options: ModelLoadOptions): Boolean {
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
        applyPreferredBackend(config.gpuBackend)
        val cpuOnlyBackend = config.gpuBackend == GpuExecutionBackend.CPU
        val gpuEnabledRequested = config.gpuEnabled && !cpuOnlyBackend
        val preferredBackendAvailable = preferredBackendAvailable(config.gpuBackend)
        val runtimeGpuSupported = gpuOffloadAllowed && gpuEnabledRequested && supportsGpuOffload()
        val gpuEnabledAndSupported = runtimeGpuSupported && preferredBackendAvailable
        val requestedGpuLayers = if (gpuEnabledAndSupported) config.gpuLayers else 0
        val requestedDraftGpuLayers = if (gpuEnabledAndSupported && config.speculativeEnabled && config.speculativeDraftGpuLayers > 0) {
            config.speculativeDraftGpuLayers.coerceAtLeast(0)
        } else {
            0
        }
        val strictGpuOffload = options.strictGpuOffload && isStrictBackendSelection(config)
        val gpuRequested = gpuEnabledRequested && (
            config.gpuLayers > 0 ||
                (config.speculativeEnabled && config.speculativeDraftGpuLayers > 0)
            )
        if (gpuRequested && strictGpuOffload && !gpuEnabledAndSupported) {
            val detail = buildString {
                append("backend=${config.gpuBackend.name.lowercase()}")
                append("|runtime_support=$runtimeGpuSupported")
                append("|preferred_backend_available=$preferredBackendAvailable")
                append("|requested_layers=${config.gpuLayers}")
                append("|requested_draft_layers=${if (config.speculativeEnabled) config.speculativeDraftGpuLayers else 0}")
            }
            recordBridgeError("GPU_BACKEND_UNAVAILABLE", detail)
            return false
        }
        val primaryAttempt = runCatching {
            nativeApi.loadModel(
                modelId = modelId,
                modelPath = normalizedModelPath,
                nThreads = config.nThreads,
                nThreadsBatch = config.nThreadsBatch,
                nBatch = config.nBatch,
                nUbatch = config.nUbatch,
                nCtx = config.nCtx,
                nGpuLayers = requestedGpuLayers,
                quantizedKvCache = config.quantizedKvCache,
                temperature = config.sampling.temperature,
                topK = config.sampling.topK,
                topP = config.sampling.topP,
                speculativeEnabled = config.speculativeEnabled,
                speculativeDraftModelPath = config.speculativeDraftModelPath,
                speculativeMaxDraftTokens = config.speculativeMaxDraftTokens,
                speculativeMinDraftTokens = config.speculativeMinDraftTokens,
                speculativeDraftGpuLayers = requestedDraftGpuLayers,
                useMmap = config.useMmap,
                useMlock = config.useMlock,
                nKeep = config.nKeep,
            )
        }
        if (primaryAttempt.getOrNull() == true) {
            clearBridgeError()
            return true
        }

        val finalError = primaryAttempt.exceptionOrNull()
        if (finalError != null) {
            recordBridgeError("JNI_LOAD_EXCEPTION", finalError)
        } else if (gpuRequested && strictGpuOffload) {
            recordBridgeError(
                "GPU_BACKEND_LOAD_FAILED",
                "modelId=$modelId|backend=${config.gpuBackend.name.lowercase()}|gpu_layers=$requestedGpuLayers|draft_gpu_layers=$requestedDraftGpuLayers",
            )
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
        var peakRssMb: Double? = null
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
            peakRssMb = nativeApi.peakRssMb()
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
                peakRssMb = peakRssMb,
                errorCode = statusErrorCode,
            )
        } finally {
            activeRequestId = null
        }
    }

    override fun unloadModel() {
        offloadModel(reason = "legacy_unload")
    }

    override fun offloadModel(reason: String): Boolean {
        val token = synchronized(lifecycleLock) {
            activeLoadToken += 1L
            activeLoadToken
        }
        val model = loadedModel
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.OFFLOADING,
                modelId = model?.modelId,
                modelVersion = model?.modelVersion,
            ),
        )
        synchronized(lifecycleLock) {
            if (token != activeLoadToken) {
                emitLifecycleFailure(
                    modelId = model?.modelId,
                    modelVersion = model?.modelVersion,
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "reason=$reason",
                )
                return false
            }
            unloadModelUnchecked()
            loadedModel = null
            emitLifecycleEvent(
                ModelLifecycleEvent(
                    state = ModelLifecycleState.UNLOADED,
                    modelId = model?.modelId,
                    modelVersion = model?.modelVersion,
                ),
            )
        }
        return true
    }

    private fun unloadModelUnchecked() {
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

    override fun getLoadedModel(): LoadedModelInfo? = loadedModel

    override fun currentModelLifecycleState(): ModelLifecycleEvent = currentLifecycleEvent

    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        val id = synchronized(lifecycleObserverLock) {
            val observerId = nextObserverId++
            lifecycleObservers[observerId] = listener
            observerId
        }
        listener(currentLifecycleEvent)
        return AutoCloseable {
            synchronized(lifecycleObserverLock) {
                lifecycleObservers.remove(id)
            }
        }
    }

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

    private fun emitLifecycleFailure(
        modelId: String?,
        modelVersion: String?,
        code: ModelLifecycleErrorCode,
        detail: String?,
    ) {
        emitLifecycleEvent(
            ModelLifecycleEvent(
                state = ModelLifecycleState.FAILED,
                modelId = modelId,
                modelVersion = modelVersion,
                error = ModelLifecycleError(code = code, detail = detail),
            ),
        )
    }

    private fun emitLifecycleEvent(event: ModelLifecycleEvent) {
        currentLifecycleEvent = event
        val listeners = synchronized(lifecycleObserverLock) {
            lifecycleObservers.values.toList()
        }
        listeners.forEach { listener ->
            runCatching { listener(event) }
        }
    }

    private fun mapBridgeErrorToLifecycleCode(errorCode: String?): ModelLifecycleErrorCode {
        val normalized = errorCode?.trim()?.uppercase().orEmpty()
        return when {
            normalized.contains("MODEL_PATH") ||
                normalized.contains("MODEL_INVALID") ||
                normalized.contains("MODEL_UNAVAILABLE") ||
                normalized.contains("PROVISIONING_IMPORT") ||
                normalized.contains("MODEL_FILE")
            -> ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE

            normalized.contains("RUNTIME_INCOMPATIBLE") ||
                normalized.contains("COMPATIBILITY") ||
                normalized.contains("PROVENANCE") ||
                normalized.contains("CHECKSUM")
            -> ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE

            normalized.contains("RUNTIME_NOT_READY") ||
                normalized.contains("JNI_INIT") ||
                normalized.contains("RUNTIME_UNAVAILABLE") ||
                normalized.contains("JNI_LIBRARY") ||
                normalized.contains("BACKEND")
            -> ModelLifecycleErrorCode.BACKEND_INIT_FAILED

            normalized.contains("BUSY")
            -> ModelLifecycleErrorCode.BUSY_GENERATION

            normalized.contains("CANCELLED_NEWER_REQUEST")
            -> ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST

            else -> ModelLifecycleErrorCode.UNKNOWN
        }
    }

    private fun applyPreferredBackend(backend: GpuExecutionBackend) {
        if (usingFallback || !runtimeReady) {
            return
        }
        val profile = when (backend) {
            GpuExecutionBackend.AUTO -> "auto"
            GpuExecutionBackend.HEXAGON -> "hexagon"
            GpuExecutionBackend.OPENCL -> "opencl"
            GpuExecutionBackend.CPU -> "cpu"
        }
        runCatching { nativeApi.setBackendProfile(profile) }
    }

    private fun preferredBackendAvailable(backend: GpuExecutionBackend): Boolean {
        if (backend == GpuExecutionBackend.AUTO || backend == GpuExecutionBackend.CPU) {
            return true
        }
        val diagnostics = backendDiagnosticsJson().orEmpty()
        if (diagnostics.isBlank()) {
            // If diagnostics are unavailable, avoid false negatives and rely on runtime probing.
            return true
        }
        val compiledBackends = parseCompiledBackends(diagnostics)
        return when (backend) {
            GpuExecutionBackend.HEXAGON -> {
                if (compiledBackends.isNotEmpty()) {
                    compiledBackends.contains("hexagon")
                } else {
                    diagnostics.lowercase().contains("hexagon")
                }
            }
            GpuExecutionBackend.OPENCL -> {
                if (compiledBackends.isNotEmpty()) {
                    compiledBackends.contains("opencl")
                } else {
                    diagnostics.lowercase().contains("opencl")
                }
            }
            else -> true
        }
    }

    private fun parseCompiledBackends(diagnosticsJson: String): Set<String> {
        val match = COMPILED_BACKEND_REGEX.find(diagnosticsJson) ?: return emptySet()
        val raw = match.groupValues.getOrNull(1).orEmpty()
        if (raw.isBlank()) {
            return emptySet()
        }
        return raw.lowercase()
            .split(',', ';', '|', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun isStrictBackendSelection(config: RuntimeGenerationConfig): Boolean {
        if (!config.strictGpuOffload) {
            return false
        }
        return when (config.gpuBackend) {
            GpuExecutionBackend.HEXAGON,
            GpuExecutionBackend.OPENCL,
            -> true
            GpuExecutionBackend.AUTO,
            GpuExecutionBackend.CPU,
            -> false
        }
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
            nCtx: Int,
            nGpuLayers: Int,
            quantizedKvCache: Boolean,
            temperature: Float,
            topK: Int,
            topP: Float,
            speculativeEnabled: Boolean,
            speculativeDraftModelPath: String?,
            speculativeMaxDraftTokens: Int,
            speculativeMinDraftTokens: Int,
            speculativeDraftGpuLayers: Int,
            useMmap: Boolean,
            useMlock: Boolean,
            nKeep: Int,
        ): Boolean
        fun setSamplingConfig(
            temperature: Float,
            topK: Int,
            topP: Float,
            nKeep: Int,
        )
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
        fun modelLayerCount(): Int? = null
        fun modelSizeBytes(): Long? = null
        fun estimateMaxGpuLayers(nCtx: Int): Int? = null
        fun backendDiagnosticsJson(): String
        fun peakRssMb(): Double? = null
        fun prefixCacheDiagnosticsLine(): String? = null
        fun setBackendProfile(profile: String) {}
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
            nCtx: Int,
            nGpuLayers: Int,
            quantizedKvCache: Boolean,
            temperature: Float,
            topK: Int,
            topP: Float,
            speculativeEnabled: Boolean,
            speculativeDraftModelPath: String?,
            speculativeMaxDraftTokens: Int,
            speculativeMinDraftTokens: Int,
            speculativeDraftGpuLayers: Int,
            useMmap: Boolean,
            useMlock: Boolean,
            nKeep: Int,
        ): Boolean
        external fun nativeSetSamplingConfig(
            temperature: Float,
            topK: Int,
            topP: Float,
            nKeep: Int,
        )
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
        external fun nativeModelLayerCount(): Int
        external fun nativeModelSizeBytes(): Long
        external fun nativeEstimateMaxGpuLayers(nCtx: Int): Int
        external fun nativeBackendDiagnosticsJson(): String
        external fun nativePeakRssMb(): Double
        external fun nativePrefixCacheDiagnosticsLine(): String
        external fun nativeSetBackendProfile(profile: String)

        override fun initialize(): Boolean = nativeInitialize()

        override fun loadModel(
            modelId: String,
            modelPath: String,
            nThreads: Int,
            nThreadsBatch: Int,
            nBatch: Int,
            nUbatch: Int,
            nCtx: Int,
            nGpuLayers: Int,
            quantizedKvCache: Boolean,
            temperature: Float,
            topK: Int,
            topP: Float,
            speculativeEnabled: Boolean,
            speculativeDraftModelPath: String?,
            speculativeMaxDraftTokens: Int,
            speculativeMinDraftTokens: Int,
            speculativeDraftGpuLayers: Int,
            useMmap: Boolean,
            useMlock: Boolean,
            nKeep: Int,
        ): Boolean {
            return nativeLoadModel(
                modelId,
                modelPath,
                nThreads,
                nThreadsBatch,
                nBatch,
                nUbatch,
                nCtx,
                nGpuLayers,
                quantizedKvCache,
                temperature,
                topK,
                topP,
                speculativeEnabled,
                speculativeDraftModelPath,
                speculativeMaxDraftTokens,
                speculativeMinDraftTokens,
                speculativeDraftGpuLayers,
                useMmap,
                useMlock,
                nKeep,
            )
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

        override fun setSamplingConfig(
            temperature: Float,
            topK: Int,
            topP: Float,
            nKeep: Int,
        ) {
            nativeSetSamplingConfig(
                temperature = temperature,
                topK = topK,
                topP = topP,
                nKeep = nKeep,
            )
        }

        override fun generate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicyCode: Int): String =
            nativeGenerate(prompt, maxTokens, cacheKey, cachePolicyCode)

        override fun cancelGeneration(): Boolean = nativeCancelGeneration()

        override fun unloadModel() = nativeUnloadModel()

        override fun supportsGpuOffload(): Boolean = nativeSupportsGpuOffload()

        override fun modelLayerCount(): Int? = nativeModelLayerCount().takeIf { it > 0 }

        override fun modelSizeBytes(): Long? = nativeModelSizeBytes().takeIf { it > 0L }

        override fun estimateMaxGpuLayers(nCtx: Int): Int? = nativeEstimateMaxGpuLayers(nCtx).takeIf { it >= 0 }

        override fun backendDiagnosticsJson(): String = nativeBackendDiagnosticsJson()

        override fun peakRssMb(): Double? {
            val value = nativePeakRssMb()
            return value.takeIf { !it.isNaN() && it >= 0.0 }
        }

        override fun prefixCacheDiagnosticsLine(): String = nativePrefixCacheDiagnosticsLine()

        override fun setBackendProfile(profile: String) = nativeSetBackendProfile(profile)
    }

    companion object {
        const val ENABLE_ADB_FALLBACK_ENV: String = "POCKETGPT_ENABLE_ADB_FALLBACK"
        const val ENABLE_GPU_OFFLOAD_ENV: String = "POCKETGPT_ENABLE_GPU_OFFLOAD"
        private val COMPILED_BACKEND_REGEX = "\"compiled_backend\"\\s*:\\s*\"([^\"]*)\"".toRegex(
            option = RegexOption.IGNORE_CASE,
        )

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
