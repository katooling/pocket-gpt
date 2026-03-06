package com.pocketagent.nativebridge

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest

class LlamaCppInferenceModule(
    private val runtimeBridge: LlamaCppRuntimeBridge = NativeJniLlamaCppBridge(),
) : InferenceModule {
    private var activeModelId: String? = null
    private val modelPathById: MutableMap<String, String> = mutableMapOf()

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
        if (activeModelId == modelId) {
            return true
        }
        if (activeModelId != null && activeModelId != modelId) {
            runtimeBridge.unloadModel()
        }
        val loaded = runtimeBridge.loadModel(modelId, modelPathById[modelId])
        activeModelId = if (loaded) modelId else null
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
        return result
    }

    override fun unloadModel() {
        runtimeBridge.unloadModel()
        activeModelId = null
    }

    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeBridge.setRuntimeGenerationConfig(config)
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

    fun registerModelPath(modelId: String, absolutePath: String) {
        val normalizedPath = absolutePath.trim()
        if (normalizedPath.isBlank()) {
            return
        }
        modelPathById[modelId] = normalizedPath
    }
}
