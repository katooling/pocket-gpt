package com.pocketagent.nativebridge

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest

class LlamaCppInferenceModule(
    private val runtimeBridge: LlamaCppRuntimeBridge = NativeJniLlamaCppBridge(),
) : InferenceModule {
    private var activeModelId: String? = null
    private val modelPathById: MutableMap<String, String> = mutableMapOf()
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    private var requiresReloadForConfigChange: Boolean = false
    private var activeRuntimeKey: LoadedRuntimeKey? = null
    @Volatile
    private var runtimeResidencyState: RuntimeResidencyState = RuntimeResidencyState()

    override fun listAvailableModels(): List<String> {
        val bridgeModels = runtimeBridge.listAvailableModels()
        if (modelPathById.isEmpty()) {
            return bridgeModels
        }
        return bridgeModels.filter { modelPathById[it]?.isNotBlank() == true }
    }

    override fun loadModel(modelId: String): Boolean {
        if (!runtimeBridge.listAvailableModels().contains(modelId)) {
            return false
        }
        val resolvedConfig = resolveRuntimeGenerationConfig(runtimeGenerationConfig)
        val loadedRuntimeKey = buildLoadedRuntimeKey(
            modelId = modelId,
            modelPath = modelPathById[modelId],
            config = resolvedConfig,
        )
        if (activeModelId == modelId && !requiresReloadForConfigChange && activeRuntimeKey == loadedRuntimeKey) {
            runtimeResidencyState = runtimeResidencyState.copy(
                key = loadedRuntimeKey,
                resident = true,
                residentHit = true,
                residentHitCount = runtimeResidencyState.residentHitCount + 1L,
                lastAccessAtEpochMs = System.currentTimeMillis(),
            )
            return true
        }
        runtimeBridge.setRuntimeGenerationConfig(resolvedConfig)
        val reloadReason = resolveReloadReason(loadedRuntimeKey)
        val startedAtMs = System.currentTimeMillis()
        val loaded = runtimeBridge.loadModel(modelId, modelPathById[modelId])
        val completedAtMs = System.currentTimeMillis()
        activeModelId = if (loaded) modelId else null
        activeRuntimeKey = if (loaded) loadedRuntimeKey else null
        runtimeResidencyState = runtimeResidencyState.copy(
            key = activeRuntimeKey,
            resident = loaded,
            residentHit = false,
            reloadReason = reloadReason,
            lastLoadDurationMs = (completedAtMs - startedAtMs).coerceAtLeast(0L),
            lastLoadAtEpochMs = completedAtMs,
            lastAccessAtEpochMs = completedAtMs,
        )
        requiresReloadForConfigChange = false
        return loaded
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        val result = generateStreamWithCache(
            requestId = "legacy",
            request = request,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            onToken = onToken,
        )
        check(result.success) {
            "llama.cpp runtime generation failed. finishReason=${result.finishReason} errorCode=${result.errorCode.orEmpty()}"
        }
    }

    fun generateStreamWithCache(
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        check(activeModelId != null) { "Model must be loaded before generation." }
        val result = runtimeBridge.generate(
            requestId = requestId,
            prompt = request.prompt,
            maxTokens = request.maxTokens,
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
            onToken = onToken,
        )
        runtimeResidencyState = runtimeResidencyState.copy(
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
        return result
    }

    override fun unloadModel() {
        runtimeBridge.unloadModel()
        activeModelId = null
        activeRuntimeKey = null
        runtimeResidencyState = runtimeResidencyState.copy(
            key = null,
            resident = false,
            residentHit = false,
            reloadReason = RuntimeReloadReason.EXPLICIT_UNLOAD,
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
        requiresReloadForConfigChange = false
    }

    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        val resolvedConfig = resolveRuntimeGenerationConfig(config)
        runtimeGenerationConfig = config
        runtimeBridge.setRuntimeGenerationConfig(resolvedConfig)
        if (activeRuntimeKey == null) {
            requiresReloadForConfigChange = false
            return
        }
        requiresReloadForConfigChange = activeRuntimeKey != buildLoadedRuntimeKey(
            modelId = activeRuntimeKey?.modelId.orEmpty(),
            modelPath = activeRuntimeKey?.modelPath,
            config = resolvedConfig,
        )
    }

    fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = runtimeBridge.getRuntimeGenerationConfig()

    fun supportsGpuOffload(): Boolean = runtimeBridge.supportsGpuOffload()

    fun cancelGeneration(): Boolean {
        return runtimeBridge.cancelGeneration()
    }

    fun cancelGeneration(requestId: String): Boolean {
        return runtimeBridge.cancelGeneration(requestId)
    }

    fun runtimeBackend(): RuntimeBackend = runtimeBridge.runtimeBackend()

    fun lastBridgeError(): BridgeError? = runtimeBridge.lastError()

    fun registerModelPath(modelId: String, absolutePath: String) {
        val normalizedPath = absolutePath.trim()
        if (normalizedPath.isBlank()) {
            return
        }
        modelPathById[modelId] = normalizedPath
    }

    fun residencyState(): RuntimeResidencyState = runtimeResidencyState

    fun recordWarmup(durationMs: Long) {
        runtimeResidencyState = runtimeResidencyState.copy(
            lastWarmupDurationMs = durationMs.coerceAtLeast(0L),
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun resolveRuntimeGenerationConfig(config: RuntimeGenerationConfig): RuntimeGenerationConfig {
        if (!config.speculativeEnabled) {
            return config.copy(speculativeDraftModelPath = null)
        }
        val resolvedDraftPath = config.speculativeDraftModelPath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: config.speculativeDraftModelId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { modelPathById[it] }
        return config.copy(speculativeDraftModelPath = resolvedDraftPath)
    }

    private fun buildLoadedRuntimeKey(
        modelId: String,
        modelPath: String?,
        config: RuntimeGenerationConfig,
    ): LoadedRuntimeKey {
        return LoadedRuntimeKey(
            modelId = modelId,
            modelPath = modelPath?.trim()?.takeIf { it.isNotEmpty() },
            backend = runtimeBridge.runtimeBackend(),
            nThreads = config.nThreads,
            nThreadsBatch = config.nThreadsBatch,
            nBatch = config.nBatch,
            nUbatch = config.nUbatch,
            nCtx = config.nCtx,
            gpuEnabled = config.gpuEnabled,
            gpuLayers = config.gpuLayers,
            quantizedKvCache = config.quantizedKvCache,
            temperature = config.sampling.temperature,
            topK = config.sampling.topK,
            topP = config.sampling.topP,
            speculativeEnabled = config.speculativeEnabled,
            speculativeDraftModelPath = config.speculativeDraftModelPath,
            speculativeMaxDraftTokens = config.speculativeMaxDraftTokens,
            speculativeMinDraftTokens = config.speculativeMinDraftTokens,
        )
    }

    private fun resolveReloadReason(nextKey: LoadedRuntimeKey): RuntimeReloadReason {
        val currentKey = activeRuntimeKey ?: return RuntimeReloadReason.INITIAL_LOAD
        return when {
            currentKey.modelId != nextKey.modelId -> RuntimeReloadReason.MODEL_CHANGED
            currentKey.modelPath != nextKey.modelPath -> RuntimeReloadReason.MODEL_PATH_CHANGED
            currentKey.backend != nextKey.backend -> RuntimeReloadReason.BACKEND_CHANGED
            else -> RuntimeReloadReason.GENERATION_CONFIG_CHANGED
        }
    }
}
