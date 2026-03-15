package com.pocketagent.inference

import com.pocketagent.core.RoutingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun `default get ready model follows runtime profile`() {
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.defaultGetReadyModelId(ModelRuntimeProfile.PROD),
        )
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.defaultGetReadyModelId(ModelRuntimeProfile.DEV_FAST),
        )
    }

    @Test
    fun `bridge supported model list is descriptor driven`() {
        val supported = ModelCatalog.bridgeSupportedModels().toSet()
        assertTrue(supported.contains(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertTrue(supported.contains(ModelCatalog.QWEN_3_5_2B_Q4))
        assertTrue(supported.contains(ModelCatalog.SMOLLM3_3B_Q4_K_M))
        assertTrue(!supported.contains(ModelCatalog.SMOKE_ECHO_120M))
    }

    @Test
    fun `bridge load validation enforces model and gguf path rules`() {
        val supportedModels = setOf(ModelCatalog.QWEN_3_5_0_8B_Q4)

        val unsupported = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_2B_Q4,
            modelPath = "/tmp/model.gguf",
            supportedModels = supportedModels,
        )
        val missingPath = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelPath = "",
            supportedModels = supportedModels,
        )
        val invalidPath = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelPath = "/tmp/model.bin",
            supportedModels = supportedModels,
        )
        val valid = ModelCatalog.validateBridgeLoad(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            modelPath = "/tmp/model.gguf",
            supportedModels = supportedModels,
        )

        assertEquals(false, unsupported.accepted)
        assertEquals("MODEL_UNSUPPORTED", unsupported.code)
        assertEquals(false, missingPath.accepted)
        assertEquals("MODEL_PATH_MISSING", missingPath.code)
        assertEquals(false, invalidPath.accepted)
        assertEquals("MODEL_PATH_INVALID", invalidPath.code)
        assertEquals(true, valid.accepted)
        assertEquals("/tmp/model.gguf", valid.normalizedModelPath)
    }

    @Test
    fun `explicit routing model lookup is descriptor driven`() {
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.modelIdForRoutingMode(RoutingMode.QWEN_0_8B),
        )
        assertEquals(
            ModelCatalog.QWEN_3_5_2B_Q4,
            ModelCatalog.modelIdForRoutingMode(RoutingMode.QWEN_2B),
        )
        assertEquals(
            ModelCatalog.SMOLLM3_3B_Q4_K_M,
            ModelCatalog.modelIdForRoutingMode(RoutingMode.SMOLLM3_3B),
        )
        assertNull(ModelCatalog.modelIdForRoutingMode(RoutingMode.AUTO))
    }

    @Test
    fun `routing modes for model include auto for auto-routing models`() {
        assertEquals(
            setOf(RoutingMode.AUTO, RoutingMode.QWEN_0_8B),
            ModelCatalog.routingModesForModel(ModelCatalog.QWEN_3_5_0_8B_Q4),
        )
        assertEquals(
            setOf(RoutingMode.AUTO, RoutingMode.SMOLLM3_3B),
            ModelCatalog.routingModesForModel(ModelCatalog.SMOLLM3_3B_Q4_K_M),
        )
    }

    @Test
    fun `descriptors expose env key tokens`() {
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.descriptorForEnvKeyToken("QWEN_3_5_0_8B_Q4")?.modelId,
        )
    }
}
