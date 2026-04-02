package com.pocketagent.nativebridge

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenClRuntimePolicyTest {
    @Test
    fun `bonsai q1 quantization is classified as safe for opencl`() {
        assertEquals(
            OpenClQuantCompatibility.SAFE,
            OpenClRuntimePolicy.releaseQuantCompatibility(
                modelPath = "/tmp/Bonsai-8B.gguf",
                modelId = "bonsai-8b-q1_0_g128",
                modelVersion = "q1_0_g128",
            ),
        )
    }

    @Test
    fun `bonsai path-based match returns SAFE even with non-matching model id`() {
        val result = OpenClRuntimePolicy.releaseQuantCompatibility(
            modelPath = "/data/models/Bonsai-8B.gguf",
            modelId = "some-other-id",
            modelVersion = null,
        )
        assertEquals(OpenClQuantCompatibility.SAFE, result)
    }

    @Test
    fun `bonsai case insensitive path match returns SAFE`() {
        val result = OpenClRuntimePolicy.releaseQuantCompatibility(
            modelPath = "/data/models/BONSAI-8B-custom.gguf",
            modelId = "custom-model",
            modelVersion = null,
        )
        assertEquals(OpenClQuantCompatibility.SAFE, result)
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
