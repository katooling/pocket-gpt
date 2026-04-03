package com.pocketagent.nativebridge

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenClRuntimePolicyTest {
    @Test
    fun `q1 g128 is supported only when qualification succeeds`() {
        assertEquals(
            OpenClQuantCompatibility.SUPPORTED,
            OpenClRuntimePolicy.releaseQuantCompatibility(
                modelPath = "/tmp/Bonsai-8B.gguf",
                modelId = "bonsai-8b-q1_0_g128",
                modelVersion = "q1_0_g128",
                qualification = OpenClQualificationSnapshot(
                    runtimeSupportsGpuOffload = true,
                    automaticOpenClEligible = true,
                    probeStatus = OpenClProbeQualificationStatus.QUALIFIED,
                ),
            ),
        )
    }

    @Test
    fun `q1 g128 is experimental on eligible but unqualified paths`() {
        val result = OpenClRuntimePolicy.releaseQuantCompatibility(
            modelPath = "/data/models/Bonsai-8B.gguf",
            modelId = "some-other-id",
            modelVersion = "q1_0_g128",
            qualification = OpenClQualificationSnapshot(
                runtimeSupportsGpuOffload = true,
                automaticOpenClEligible = true,
                probeStatus = OpenClProbeQualificationStatus.PENDING,
            ),
        )
        assertEquals(OpenClQuantCompatibility.EXPERIMENTAL, result)
    }

    @Test
    fun `q1 g128 is unsupported when device cannot attempt specialized opencl`() {
        val result = OpenClRuntimePolicy.releaseQuantCompatibility(
            modelPath = "/data/models/BONSAI-8B-custom.gguf",
            modelId = "custom-model",
            modelVersion = "q1_0_g128",
            qualification = OpenClQualificationSnapshot(
                runtimeSupportsGpuOffload = false,
                automaticOpenClEligible = false,
                probeStatus = OpenClProbeQualificationStatus.FAILED,
            ),
        )
        assertEquals(OpenClQuantCompatibility.UNSUPPORTED, result)
    }

    @Test
    fun `generic q1 quantization remains unsupported`() {
        assertEquals(
            OpenClQuantCompatibility.UNSUPPORTED,
            OpenClRuntimePolicy.releaseQuantCompatibility(
                modelPath = "/tmp/other-q1.gguf",
                modelId = "other-q1-model",
                modelVersion = "q1_0_g128",
            ),
        )
    }
}
