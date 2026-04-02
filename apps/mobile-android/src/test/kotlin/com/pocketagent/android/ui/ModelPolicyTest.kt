package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPolicyTest {
    @Test
    fun `runtime profile resolves to production for app builds`() {
        assertEquals(ModelRuntimeProfile.PROD, resolveModelRuntimeProfile(isDebugBuild = true))
        assertEquals(ModelRuntimeProfile.PROD, resolveModelRuntimeProfile(isDebugBuild = false))
    }

    @Test
    fun `default get ready model id follows build profile`() {
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            resolveDefaultGetReadyModelId(isDebugBuild = true),
        )
        assertEquals(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            resolveDefaultGetReadyModelId(isDebugBuild = false),
        )
    }

    @Test
    fun `default get ready version resolves from manifest`() {
        val defaultModelId = ModelCatalog.QWEN_3_5_0_8B_Q4
        val expected = ModelDistributionVersion(
            modelId = defaultModelId,
            version = "q4_0",
            downloadUrl = "https://example.test/qwen.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 100L,
        )
        val manifest = ModelDistributionManifest(
            models = listOf(
                ModelDistributionModel(
                    modelId = defaultModelId,
                    displayName = "Qwen",
                    versions = listOf(expected),
                ),
            ),
        )

        assertEquals(
            expected,
            resolveDefaultGetReadyVersion(manifest = manifest, defaultModelId = defaultModelId),
        )
        assertNull(resolveDefaultGetReadyVersion(manifest = manifest, defaultModelId = "missing"))
    }

    @Test
    fun `default get ready version prioritizes stable q4 over iq variants`() {
        val defaultModelId = ModelCatalog.QWEN_3_5_0_8B_Q4
        val iq2 = ModelDistributionVersion(
            modelId = defaultModelId,
            version = "ud_iq2_xxs",
            downloadUrl = "https://example.test/qwen-iq2.gguf",
            expectedSha256 = "b".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 90L,
        )
        val q4 = ModelDistributionVersion(
            modelId = defaultModelId,
            version = "q4_0",
            downloadUrl = "https://example.test/qwen-q4.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 100L,
        )
        val manifest = ModelDistributionManifest(
            models = listOf(
                ModelDistributionModel(
                    modelId = defaultModelId,
                    displayName = "Qwen",
                    versions = listOf(iq2, q4),
                ),
            ),
        )

        assertEquals(
            q4,
            resolveDefaultGetReadyVersion(manifest = manifest, defaultModelId = defaultModelId),
        )
    }

    @Test
    fun `supported routing modes include bonsai when bridge supported`() {
        assertTrue(supportedRoutingModes().contains(com.pocketagent.core.RoutingMode.BONSAI_8B))
    }
}
