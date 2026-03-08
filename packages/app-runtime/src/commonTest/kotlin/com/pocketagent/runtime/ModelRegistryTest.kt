package com.pocketagent.runtime

import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelRegistryTest {
    @Test
    fun `default registry exposes expected runtime metadata`() {
        val registry = ModelRegistry.default()

        val metadata = registry.allMetadata().associateBy { it.modelId }
        assertTrue(metadata.containsKey(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertTrue(metadata.containsKey(ModelCatalog.QWEN_3_5_2B_Q4))
        assertTrue(metadata.containsKey(ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M))
        assertTrue(metadata.containsKey(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M))
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            registry.defaultGetReadyModelId(profile = ModelRuntimeProfile.PROD),
        )
        assertEquals(
            ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M,
            registry.defaultGetReadyModelId(profile = ModelRuntimeProfile.DEV_FAST),
        )
    }

    @Test
    fun `default startup policy keeps qwen startup candidates with minimum one ready`() {
        val policy = ModelRegistry.default().startupPolicy(profile = ModelRuntimeProfile.PROD)

        assertEquals(
            listOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN_3_5_2B_Q4),
            policy.candidateModelIds,
        )
        assertEquals(emptyList(), policy.requiredModelIds)
        assertEquals(1, policy.minimumReadyCount)
    }

    @Test
    fun `required startup models are reflected in startup policy`() {
        val registry = ModelRegistry(
            metadataByModelId = listOf(
                RuntimeModelMetadata(
                    modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                    templateProfile = ModelTemplateProfile.CHATML,
                    tier = RuntimeModelTier.BASELINE,
                    startupRequirement = StartupRequirement.REQUIRED,
                ),
                RuntimeModelMetadata(
                    modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                    templateProfile = ModelTemplateProfile.CHATML,
                    tier = RuntimeModelTier.BASELINE,
                    startupRequirement = StartupRequirement.OPTIONAL,
                ),
            ).associateBy { metadata -> metadata.modelId },
            startupMinimumReadyCount = 1,
        )

        val policy = registry.startupPolicy(profile = ModelRuntimeProfile.PROD)

        assertEquals(listOf(ModelCatalog.QWEN_3_5_0_8B_Q4), policy.requiredModelIds)
        assertEquals(1, policy.minimumReadyCount)
    }

    @Test
    fun `dev fast startup policy prefers fast and debug tiers`() {
        val policy = ModelRegistry.default().startupPolicy(profile = ModelRuntimeProfile.DEV_FAST)

        assertEquals(
            setOf(
                ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M,
                ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M,
            ),
            policy.candidateModelIds.toSet(),
        )
        assertEquals(emptyList(), policy.requiredModelIds)
        assertEquals(1, policy.minimumReadyCount)
    }

    @Test
    fun `default registry routing modes are sourced from catalog metadata`() {
        val metadataByModelId = ModelRegistry.default().allMetadata().associateBy { metadata -> metadata.modelId }

        assertEquals(
            setOf(RoutingMode.AUTO, RoutingMode.QWEN_0_8B),
            metadataByModelId.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4).routingModes,
        )
        assertEquals(
            setOf(RoutingMode.SMOLLM2_360M),
            metadataByModelId.getValue(ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M).routingModes,
        )
    }
}
