package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelDescriptor

class ModelInteractionRegistry(
    private val profileByModelId: Map<String, ModelInteractionProfile> = defaultProfiles(),
) {
    fun interactionProfileForModel(modelId: String): ModelInteractionProfile {
        return profileByModelId[modelId]
            ?: throw RuntimeTemplateUnavailableException("TEMPLATE_UNAVAILABLE: model profile missing for $modelId")
    }

    fun templateProfileForModel(modelId: String): ModelTemplateProfile {
        return interactionProfileForModel(modelId).templateProfile
    }

    fun ensureTemplateAvailable(modelId: String): String? {
        return runCatching { interactionProfileForModel(modelId) }
            .exceptionOrNull()
            ?.message
    }

    companion object {
        const val FEATURE_TOOL_CALL_XML: String = "TOOL_CALL_XML"
        const val FEATURE_THINKING_TAGS: String = "THINKING_TAGS"

        fun defaultProfiles(): Map<String, ModelInteractionProfile> {
            return ModelCatalog.modelDescriptors()
                .filter { descriptor -> descriptor.bridgeSupported || descriptor.startupCandidate }
                .associate { descriptor ->
                    descriptor.modelId to profileFromDescriptor(descriptor)
                }
        }

        private fun profileFromDescriptor(descriptor: ModelDescriptor): ModelInteractionProfile {
            val templateProfile = ModelTemplateProfile.valueOf(descriptor.chatTemplateId)
            val features = descriptor.interactionFeatures
            val toolCallSupport = if (features.contains(FEATURE_TOOL_CALL_XML)) {
                ToolCallSupport.XmlTagFormat()
            } else {
                ToolCallSupport.NONE
            }
            val thinkingSupport = if (features.contains(FEATURE_THINKING_TAGS)) {
                ThinkingSupport.THINK_TAGS
            } else {
                ThinkingSupport.NONE
            }
            val systemPromptStrategy = if (templateProfile == ModelTemplateProfile.GEMMA) {
                SystemPromptStrategy.PREPEND_TO_USER
            } else {
                SystemPromptStrategy.NATIVE
            }
            val roleNameOverrides = if (templateProfile == ModelTemplateProfile.GEMMA) {
                mapOf(InteractionRole.ASSISTANT to "model")
            } else {
                emptyMap()
            }
            return ModelInteractionProfile(
                templateProfile = templateProfile,
                thinkingSupport = thinkingSupport,
                toolCallSupport = toolCallSupport,
                systemPromptStrategy = systemPromptStrategy,
                roleNameOverrides = roleNameOverrides,
            )
        }
    }
}
