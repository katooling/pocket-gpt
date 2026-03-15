package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelTemplateRegistryTest {
    @Test
    fun `registry provides a template profile for every registered model`() {
        val registry = ModelTemplateRegistry()

        ModelCatalog.modelDescriptors()
            .filter { it.bridgeSupported || it.startupCandidate }
            .forEach { descriptor ->
                val expected = ModelTemplateProfile.valueOf(descriptor.chatTemplateId)
                assertEquals(
                    expected,
                    registry.templateProfileForModel(descriptor.modelId),
                    "template profile mismatch for ${descriptor.modelId}",
                )
            }
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
