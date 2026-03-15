package com.pocketagent.runtime

@Deprecated(
    message = "Use ModelInteractionRegistry; template selection is now derived from ModelInteractionProfile.",
    replaceWith = ReplaceWith("ModelInteractionRegistry"),
)
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
            val interactionProfiles = ModelInteractionRegistry.defaultProfiles()
            return modelRegistry.allMetadata().associate { metadata ->
                val resolvedProfile = interactionProfiles[metadata.modelId]?.templateProfile ?: metadata.templateProfile
                metadata.modelId to resolvedProfile
            }
        }
    }
}
