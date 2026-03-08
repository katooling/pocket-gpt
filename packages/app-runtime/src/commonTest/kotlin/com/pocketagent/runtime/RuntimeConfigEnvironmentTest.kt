package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeConfigEnvironmentTest {
    @Test
    fun `model env names are derived from catalog tokens`() {
        assertEquals(
            "POCKETGPT_MODEL_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH",
            RuntimeConfig.sideLoadPathEnvName(ModelCatalog.QWEN_3_5_0_8B_Q4),
        )
        assertEquals(
            "POCKETGPT_MODEL_SMOLLM2_360M_INSTRUCT_Q4_K_M_SHA256",
            RuntimeConfig.sha256EnvName(ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M),
        )
    }

    @Test
    fun `fromEnvironment parses model-agnostic env keys for fast-tier models`() {
        val modelId = ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M
        val payloadSha = "a".repeat(64)
        val environment = mapOf(
            RuntimeConfig.MODEL_RUNTIME_PROFILE_ENV to "dev_fast",
            RuntimeConfig.MODEL_PROVENANCE_ISSUER_ENV to "global-issuer",
            RuntimeConfig.sideLoadPathEnvName(modelId) to "/tmp/smollm2-360m.gguf",
            RuntimeConfig.sha256EnvName(modelId) to payloadSha,
            RuntimeConfig.provenanceSignatureEnvName(modelId) to "sig-smol-360m",
        )

        val config = RuntimeConfig.fromEnvironment(environment = environment)

        assertEquals(ModelRuntimeProfile.DEV_FAST, config.modelRuntimeProfile)
        assertEquals("/tmp/smollm2-360m.gguf", config.artifactFilePathByModelId.getValue(modelId))
        assertEquals(payloadSha, config.artifactSha256ByModelId.getValue(modelId))
        assertEquals("global-issuer", config.artifactProvenanceIssuerByModelId.getValue(modelId))
        assertEquals("sig-smol-360m", config.artifactProvenanceSignatureByModelId.getValue(modelId))
    }

    @Test
    fun `per-model issuer env overrides global issuer when deriving signature`() {
        val modelId = ModelCatalog.QWEN_3_5_0_8B_Q4
        val payloadSha = "b".repeat(64)
        val modelIssuer = "model-issuer"
        val environment = mapOf(
            RuntimeConfig.MODEL_PROVENANCE_ISSUER_ENV to "global-issuer",
            RuntimeConfig.sha256EnvName(modelId) to payloadSha,
            RuntimeConfig.provenanceIssuerEnvName(modelId) to modelIssuer,
        )

        val config = RuntimeConfig.fromEnvironment(environment = environment)

        assertEquals(modelIssuer, config.artifactProvenanceIssuerByModelId.getValue(modelId))
        assertEquals(
            sha256Hex("$modelIssuer|$modelId|$payloadSha|v1".encodeToByteArray()),
            config.artifactProvenanceSignatureByModelId.getValue(modelId),
        )
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
