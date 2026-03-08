package com.pocketagent.runtime

class ModelTemplateRegistry(
    private val profileByModelId: Map<String, ModelTemplateProfile> = defaultProfiles(),
) {
    fun templateProfileForModel(modelId: String): ModelTemplateProfile {
        return profileByModelId[modelId]
            ?: throw RuntimeTemplateUnavailableException("TEMPLATE_UNAVAILABLE: model profile missing for $modelId")
    }

    fun ensureTemplateAvailable(modelId: String): String? {
        return runCatching { templateProfileForModel(modelId) }
            .exceptionOrNull()
            ?.message
    }

    companion object {
        fun defaultProfiles(modelRegistry: ModelRegistry = ModelRegistry.default()): Map<String, ModelTemplateProfile> {
            return modelRegistry.templateProfilesByModelId()
        }
    }
}
