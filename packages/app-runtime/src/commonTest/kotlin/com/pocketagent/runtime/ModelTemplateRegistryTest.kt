package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTemplateRegistryTest {
    @Test
    fun `registry returns chatml for required runtime models`() {
        val registry = ModelTemplateRegistry()

        assertEquals(ModelTemplateProfile.CHATML, registry.templateProfileForModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertEquals(ModelTemplateProfile.CHATML, registry.templateProfileForModel(ModelCatalog.QWEN_3_5_2B_Q4))
        assertEquals(ModelTemplateProfile.CHATML, registry.templateProfileForModel(ModelCatalog.SMOLLM3_3B_Q4_K_M))
        assertEquals(ModelTemplateProfile.PHI, registry.templateProfileForModel(ModelCatalog.PHI_4_MINI_Q4_K_M))
    }

    @Test
    fun `registry reports template unavailable for unknown model`() {
        val registry = ModelTemplateRegistry()

        val message = registry.ensureTemplateAvailable("unknown-model")
        assertTrue(message?.contains("TEMPLATE_UNAVAILABLE") == true)
    }

    @Test
    fun `default profiles are derived from model registry metadata`() {
        val customRegistry = ModelRegistry(
            metadataByModelId = mapOf(
                "custom-model" to RuntimeModelMetadata(
                    modelId = "custom-model",
                    templateProfile = ModelTemplateProfile.LLAMA3,
                    tier = RuntimeModelTier.DEBUG,
                    startupRequirement = StartupRequirement.NONE,
                ),
            ),
            startupMinimumReadyCount = 1,
        )

        val profiles = ModelTemplateRegistry.defaultProfiles(modelRegistry = customRegistry)

        assertEquals(ModelTemplateProfile.LLAMA3, profiles["custom-model"])
    }
}
