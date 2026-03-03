package com.pocketagent.android

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest

class AndroidLlamaCppInferenceModule(
    private val runtimeBridge: LlamaCppRuntimeBridge = AndroidLlamaCppRuntimeBridge(),
) : InferenceModule {
    private var activeModelId: String? = null

    override fun listAvailableModels(): List<String> = runtimeBridge.listAvailableModels()

    override fun loadModel(modelId: String): Boolean {
        if (!runtimeBridge.listAvailableModels().contains(modelId)) {
            return false
        }
        if (activeModelId != null && activeModelId != modelId) {
            runtimeBridge.unloadModel()
        }
        val loaded = runtimeBridge.loadModel(modelId)
        activeModelId = if (loaded) modelId else null
        return loaded
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        check(activeModelId != null) { "Model must be loaded before generation." }
        val success = runtimeBridge.generate(
            prompt = request.prompt,
            maxTokens = request.maxTokens,
            onToken = onToken,
        )
        check(success) { "llama.cpp runtime generation failed." }
    }

    override fun unloadModel() {
        runtimeBridge.unloadModel()
        activeModelId = null
    }
}
