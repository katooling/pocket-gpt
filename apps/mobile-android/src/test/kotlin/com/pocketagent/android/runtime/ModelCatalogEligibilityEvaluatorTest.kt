package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogEligibilityEvaluatorTest {
    private val evaluator = DefaultModelCatalogEligibilityEvaluator()

    @Test
    fun `standard q4 release stays supported`() {
        val snapshot = evaluator.evaluate(
            manifest = manifestFor(
                version = manifestVersion(
                    modelId = "qwen3-0.6b-q4_k_m",
                    version = "q4_k_m",
                ),
            ),
            snapshot = null,
            signals = ModelEligibilitySignals.assumeSupported(),
        )

        val eligibility = snapshot.eligibilityFor("qwen3-0.6b-q4_k_m", "q4_k_m")
        assertEquals(ModelSupportLevel.SUPPORTED, eligibility.supportLevel)
        assertTrue(eligibility.catalogVisible)
        assertTrue(eligibility.downloadAllowed)
        assertTrue(eligibility.loadAllowed)
    }

    @Test
    fun `q1 g128 release is hidden on cpu only devices`() {
        listOf(
            "bonsai-1.7b-q1_0_g128",
            "bonsai-4b-q1_0_g128",
            "bonsai-8b-q1_0_g128",
        ).forEach { modelId ->
            val snapshot = evaluator.evaluate(
                manifest = manifestFor(
                    version = manifestVersion(
                        modelId = modelId,
                        version = "q1_0_g128",
                    ),
                ),
                snapshot = null,
                signals = ModelEligibilitySignals(
                    runtimeCompatibilityTag = "android-arm64-v8a",
                    runtimeSupportsGpuOffload = false,
                    deviceAdvisory = DeviceGpuOffloadAdvisory(
                        supportedForProbe = false,
                        automaticOpenClEligible = false,
                        reason = "adreno_family_missing",
                    ),
                    gpuProbeResult = GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                    ),
                ),
            )

            val eligibility = snapshot.eligibilityFor(modelId, "q1_0_g128")
            assertEquals(ModelSupportLevel.UNSUPPORTED, eligibility.supportLevel)
            assertFalse(eligibility.catalogVisible)
            assertFalse(eligibility.downloadAllowed)
            assertFalse(eligibility.loadAllowed)
            assertEquals(ModelEligibilityReason.GPU_RUNTIME_UNAVAILABLE, eligibility.reason)
        }
    }

    @Test
    fun `q1 g128 release is experimental while qualification is pending`() {
        val snapshot = evaluator.evaluate(
            manifest = manifestFor(
                version = manifestVersion(
                    modelId = "bonsai-8b-q1_0_g128",
                    version = "q1_0_g128",
                ),
            ),
            snapshot = null,
            signals = ModelEligibilitySignals(
                runtimeCompatibilityTag = "android-arm64-v8a",
                runtimeSupportsGpuOffload = true,
                deviceAdvisory = DeviceGpuOffloadAdvisory(
                    supportedForProbe = true,
                    automaticOpenClEligible = true,
                    isAdrenoFamily = true,
                    hasArmDotProd = true,
                    hasArmI8mm = true,
                    adrenoGeneration = 7,
                    reason = "advisory_qualified",
                ),
                gpuProbeResult = GpuProbeResult(
                    status = GpuProbeStatus.PENDING,
                    detail = "qualification_in_progress",
                ),
            ),
        )

        val eligibility = snapshot.eligibilityFor("bonsai-8b-q1_0_g128", "q1_0_g128")
        assertEquals(ModelSupportLevel.EXPERIMENTAL, eligibility.supportLevel)
        assertTrue(eligibility.catalogVisible)
        assertTrue(eligibility.downloadAllowed)
        assertTrue(eligibility.loadAllowed)
        assertEquals(ModelEligibilityReason.GPU_QUALIFICATION_PENDING, eligibility.reason)
    }

    @Test
    fun `q1 g128 release becomes supported on qualified gpu path`() {
        val snapshot = evaluator.evaluate(
            manifest = manifestFor(
                version = manifestVersion(
                    modelId = "bonsai-8b-q1_0_g128",
                    version = "q1_0_g128",
                ),
            ),
            snapshot = null,
            signals = ModelEligibilitySignals(
                runtimeCompatibilityTag = "android-arm64-v8a",
                runtimeSupportsGpuOffload = true,
                deviceAdvisory = DeviceGpuOffloadAdvisory(
                    supportedForProbe = true,
                    automaticOpenClEligible = true,
                    isAdrenoFamily = true,
                    hasArmDotProd = true,
                    hasArmI8mm = true,
                    adrenoGeneration = 7,
                    reason = "advisory_qualified",
                ),
                gpuProbeResult = GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 32,
                ),
            ),
        )

        val eligibility = snapshot.eligibilityFor("bonsai-8b-q1_0_g128", "q1_0_g128")
        assertEquals(ModelSupportLevel.SUPPORTED, eligibility.supportLevel)
    }

    @Test
    fun `installed versions also receive eligibility decisions`() {
        val snapshot = evaluator.evaluate(
            manifest = ModelDistributionManifest(models = emptyList()),
            snapshot = RuntimeProvisioningSnapshot(
                models = listOf(
                    ProvisionedModelState(
                        modelId = "bonsai-8b-q1_0_g128",
                        displayName = "Bonsai",
                        fileName = "bonsai.gguf",
                        absolutePath = "/tmp/bonsai.gguf",
                        sha256 = "a".repeat(64),
                        importedAtEpochMs = 1L,
                        activeVersion = "q1_0_g128",
                        installedVersions = listOf(
                            ModelVersionDescriptor(
                                modelId = "bonsai-8b-q1_0_g128",
                                version = "q1_0_g128",
                                displayName = "Bonsai q1_0_g128",
                                absolutePath = "/tmp/bonsai.gguf",
                                sha256 = "a".repeat(64),
                                provenanceIssuer = "issuer",
                                provenanceSignature = "sig",
                                runtimeCompatibility = "android-arm64-v8a",
                                fileSizeBytes = 1L,
                                importedAtEpochMs = 1L,
                                isActive = true,
                            ),
                        ),
                    ),
                ),
                storageSummary = StorageSummary(
                    totalBytes = 1L,
                    freeBytes = 1L,
                    usedByModelsBytes = 1L,
                    tempDownloadBytes = 0L,
                ),
                requiredModelIds = emptySet(),
            ),
            signals = ModelEligibilitySignals(
                runtimeCompatibilityTag = "android-arm64-v8a",
                runtimeSupportsGpuOffload = false,
                deviceAdvisory = DeviceGpuOffloadAdvisory(
                    supportedForProbe = false,
                    automaticOpenClEligible = false,
                    reason = "adreno_family_missing",
                ),
                gpuProbeResult = GpuProbeResult(
                    status = GpuProbeStatus.FAILED,
                    failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                ),
            ),
        )

        val eligibility = snapshot.eligibilityFor("bonsai-8b-q1_0_g128", "q1_0_g128")
        assertFalse(eligibility.loadAllowed)
    }

    private fun manifestFor(version: ModelDistributionVersion): ModelDistributionManifest {
        return ModelDistributionManifest(
            models = listOf(
                ModelDistributionModel(
                    modelId = version.modelId,
                    displayName = version.modelId,
                    versions = listOf(version),
                ),
            ),
        )
    }

    private fun manifestVersion(
        modelId: String,
        version: String,
    ): ModelDistributionVersion {
        return ModelDistributionVersion(
            modelId = modelId,
            version = version,
            downloadUrl = "https://example.test/$modelId/$version.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 1L,
        )
    }
}
