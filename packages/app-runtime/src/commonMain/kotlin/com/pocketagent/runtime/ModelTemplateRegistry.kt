package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog

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
        fun defaultProfiles(): Map<String, ModelTemplateProfile> {
            return mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to ModelTemplateProfile.CHATML,
                ModelCatalog.QWEN_3_5_2B_Q4 to ModelTemplateProfile.CHATML,
            )
        }
    }
}
