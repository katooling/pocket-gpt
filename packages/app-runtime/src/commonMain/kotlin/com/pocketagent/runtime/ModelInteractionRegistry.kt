package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelCapability
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
            val thinkingSupport = resolveThinkingSupport(descriptor)
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

        private fun resolveThinkingSupport(descriptor: ModelDescriptor): ThinkingSupport {
            if (descriptor.interactionFeatures.contains(FEATURE_THINKING_TAGS)) {
                return ThinkingSupport.THINK_TAGS
            }
            val normalizedModelId = descriptor.modelId.lowercase()
            val knownThinkingModel = normalizedModelId.contains("deepseek-r1") ||
                normalizedModelId.contains("qwen3") ||
                normalizedModelId.contains("qwen3.5") ||
                normalizedModelId.contains("smollm3") ||
                normalizedModelId.contains("phi-4")
            if (knownThinkingModel) {
                return ThinkingSupport.THINK_TAGS
            }
            if (descriptor.capabilities.contains(ModelCapability.REASONING) &&
                descriptor.chatTemplateId != ModelTemplateProfile.GEMMA.name
            ) {
                return ThinkingSupport.THINK_TAGS
            }
            return ThinkingSupport.NONE
        }
    }
}
