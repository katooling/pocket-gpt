package com.pocketagent.inference

class SmokeInferenceModule : InferenceModule {
    private var activeModelId: String? = null

    override fun listAvailableModels(): List<String> = ModelCatalog.baselineModels()

    override fun loadModel(modelId: String): Boolean {
        if (!listAvailableModels().contains(modelId)) {
            return false
        }
        activeModelId = modelId
        return true
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        val loaded = activeModelId ?: error("Model must be loaded before generation.")
        val prefix = when (loaded) {
            ModelCatalog.SMOKE_ECHO_120M -> "SMOKE"
            ModelCatalog.QWEN_3_5_0_8B_Q4 -> "QWEN0.8B"
            ModelCatalog.QWEN_3_5_2B_Q4 -> "QWEN2B"
            else -> "UNKNOWN"
        }

        val response = buildString {
            append(prefix)
            append(": ")
            append("offline response for \"")
            append(request.prompt.take(120))
            append("\"")
            append(" [max_tokens=")
            append(request.maxTokens)
            append("]")
        }

        response.split(" ").forEach { token ->
            if (token.isNotBlank()) {
                onToken("$token ")
            }
        }
    }

    override fun unloadModel() {
        activeModelId = null
    }
}
