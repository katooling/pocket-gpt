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
        assertTrue(metadata.containsKey(ModelCatalog.SMOLLM3_3B_Q4_K_M))
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            registry.defaultGetReadyModelId(profile = ModelRuntimeProfile.PROD),
        )
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            registry.defaultGetReadyModelId(profile = ModelRuntimeProfile.DEV_FAST),
        )
    }

    @Test
    fun `default startup policy keeps qwen startup candidates with minimum one ready`() {
        val policy = ModelRegistry.default().startupPolicy(profile = ModelRuntimeProfile.PROD)

        assertEquals(
            listOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN_3_5_2B_Q4, ModelCatalog.SMOLLM3_3B_Q4_K_M),
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
    fun `dev fast startup policy falls back to startup candidates when no fast tier models`() {
        val policy = ModelRegistry.default().startupPolicy(profile = ModelRuntimeProfile.DEV_FAST)

        assertEquals(
            setOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4,
                ModelCatalog.QWEN_3_5_2B_Q4,
                ModelCatalog.SMOLLM3_3B_Q4_K_M,
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
            setOf(RoutingMode.AUTO, RoutingMode.SMOLLM3_3B),
            metadataByModelId.getValue(ModelCatalog.SMOLLM3_3B_Q4_K_M).routingModes,
        )
    }
}
