package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelspec.DefaultNormalizedModelCatalogRegistry
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelAdmissionPolicyTest {
    @Test
    fun `download is blocked for gpu-only model when runtime is unavailable`() {
        val policy = DefaultModelAdmissionPolicy(
            signalsProvider = staticSignalsProvider(
                ModelEligibilitySignals(
                    runtimeCompatibilityTag = "android-arm64-v8a",
                    runtimeSupportsGpuOffload = false,
                    deviceAdvisory = DeviceGpuOffloadAdvisory(
                        supportedForProbe = true,
                        automaticOpenClEligible = true,
                        isAdrenoFamily = true,
                        adrenoGeneration = 7,
                        reason = "advisory_qualified",
                    ),
                    gpuProbeResult = GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                    ),
                    runtimeDiagnostics = RuntimeDiagnosticsSnapshot(
                        activeBackend = "cpu",
                        nativeRuntimeSupported = false,
                        backendCapabilities = listOf(
                            RuntimeBackendCapability(
                                family = RuntimeBackendFamily.OPENCL,
                                backend = "opencl",
                                compiled = true,
                                registered = true,
                                discovered = false,
                                active = false,
                                deviceCount = 0,
                                runtimeAvailable = false,
                                qualified = false,
                            ),
                        ),
                        backendCapabilityByFamily = mapOf(
                            RuntimeBackendFamily.OPENCL to RuntimeBackendCapability(
                                family = RuntimeBackendFamily.OPENCL,
                                backend = "opencl",
                                compiled = true,
                                registered = true,
                                discovered = false,
                                active = false,
                                deviceCount = 0,
                                runtimeAvailable = false,
                                qualified = false,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val decision = policy.evaluate(
            action = ModelAdmissionAction.DOWNLOAD,
            subject = ModelAdmissionSubject(
                modelId = "bonsai-8b-q1_0_g128",
                version = "q1_0_g128",
                runtimeCompatibility = "android-arm64-v8a",
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(ModelEligibilityReason.GPU_RUNTIME_UNAVAILABLE, decision.eligibility.reason)
        assertTrue(decision.eligibility.technicalDetail.orEmpty().contains("opencl_discovered=false"))
    }

    @Test
    fun `load is allowed for qualified gpu path`() {
        val policy = DefaultModelAdmissionPolicy(
            signalsProvider = staticSignalsProvider(
                ModelEligibilitySignals(
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
                        maxStableGpuLayers = 16,
                    ),
                    runtimeDiagnostics = RuntimeDiagnosticsSnapshot(
                        activeBackend = "opencl",
                        nativeRuntimeSupported = true,
                        backendCapabilities = listOf(
                            RuntimeBackendCapability(
                                family = RuntimeBackendFamily.OPENCL,
                                backend = "opencl",
                                compiled = true,
                                registered = true,
                                discovered = true,
                                active = true,
                                deviceCount = 1,
                                runtimeAvailable = true,
                                qualified = true,
                            ),
                        ),
                        backendCapabilityByFamily = mapOf(
                            RuntimeBackendFamily.OPENCL to RuntimeBackendCapability(
                                family = RuntimeBackendFamily.OPENCL,
                                backend = "opencl",
                                compiled = true,
                                registered = true,
                                discovered = true,
                                active = true,
                                deviceCount = 1,
                                runtimeAvailable = true,
                                qualified = true,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val decision = policy.evaluate(
            action = ModelAdmissionAction.LOAD,
            subject = ModelAdmissionSubject(
                modelId = "bonsai-8b-q1_0_g128",
                version = "q1_0_g128",
                runtimeCompatibility = "android-arm64-v8a",
            ),
        )

        assertTrue(decision.allowed)
        assertEquals(ModelSupportLevel.SUPPORTED, decision.eligibility.supportLevel)
    }

    @Test
    fun `load is blocked when required artifact bundle is incomplete`() {
        val policy = DefaultModelAdmissionPolicy(
            signalsProvider = staticSignalsProvider(ModelEligibilitySignals.assumeSupported()),
            catalogRegistry = DefaultNormalizedModelCatalogRegistry(),
            launchPlanner = DefaultModelRuntimeLaunchPlanner(
                catalogRegistry = DefaultNormalizedModelCatalogRegistry(),
            ),
        )

        val decision = policy.evaluate(
            action = ModelAdmissionAction.LOAD,
            subject = ModelVersionDescriptor(
                modelId = "qwen3.5-0.8b-q4",
                version = "q4",
                displayName = "Qwen",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                provenanceIssuer = "issuer",
                provenanceSignature = "sig",
                runtimeCompatibility = "android-arm64-v8a",
                fileSizeBytes = 123L,
                importedAtEpochMs = 1L,
                isActive = true,
                sourceKind = ModelSourceKind.BUILT_IN,
                artifacts = listOf(
                    InstalledArtifactDescriptor(
                        artifactId = "primary",
                        role = ModelArtifactRole.PRIMARY_GGUF,
                        fileName = "qwen.gguf",
                        absolutePath = "/tmp/qwen.gguf",
                    ),
                ),
            ).toAdmissionSubject(),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.eligibility.technicalDetail.orEmpty().contains("missing_artifacts"))
    }

    private fun staticSignalsProvider(signals: ModelEligibilitySignals): ModelEligibilitySignalsProvider {
        return object : ModelEligibilitySignalsProvider {
            override fun currentSignals(): ModelEligibilitySignals = signals
        }
    }
}
