package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelPolicyTest {
    @Test
    fun `runtime profile resolves to dev fast for debug builds`() {
        assertEquals(ModelRuntimeProfile.DEV_FAST, resolveModelRuntimeProfile(isDebugBuild = true))
        assertEquals(ModelRuntimeProfile.PROD, resolveModelRuntimeProfile(isDebugBuild = false))
    }

    @Test
    fun `default get ready model id follows build profile`() {
        assertEquals(
            ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M,
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
}
