package com.pocketagent.android.runtime

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GpuOffloadQualificationTest {
    @Test
    fun `runtime unsupported fails fast without probe`() = runTest {
        val probeClient = RecordingProbeClient(
            response = GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = 8,
            ),
        )
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
        )

        val result = qualifier.evaluate(runtimeSupported = false)

        assertEquals(GpuProbeStatus.FAILED, result.status)
        assertEquals(GpuProbeFailureReason.RUNTIME_UNSUPPORTED, result.failureReason)
        assertEquals(0, probeClient.callCount)
    }

    @Test
    fun `qualification transitions pending to cached qualified`() = runTest {
        val probeClient = RecordingProbeClient(
            response = GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = 16,
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            scope = TestScope(dispatcher),
        )

        val pending = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.PENDING, pending.status)

        advanceUntilIdle()

        val qualified = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, qualified.status)
        assertEquals(16, qualified.maxStableGpuLayers)
        assertEquals(1, probeClient.callCount)

        val cached = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, cached.status)
        assertEquals(1, probeClient.callCount)
    }

    @Test
    fun `cache key invalidates when driver diagnostics change`() = runTest {
        val probeClient = RecordingProbeClient(
            response = GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = 4,
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        var diagnosticsPayload = diagnosticsJson(driverVersion = 1L)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            diagnosticsReader = NativeVulkanDiagnosticsReader(payloadProvider = { diagnosticsPayload }),
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        assertEquals(GpuProbeStatus.QUALIFIED, qualifier.evaluate(runtimeSupported = true).status)
        assertEquals(1, probeClient.callCount)

        diagnosticsPayload = diagnosticsJson(driverVersion = 2L)
        val invalidated = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.PENDING, invalidated.status)
        assertEquals(1, probeClient.callCount)

        advanceUntilIdle()
        val requalified = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, requalified.status)
        assertEquals(2, probeClient.callCount)
    }
}

private fun buildQualifier(
    probeClient: GpuProbeClient,
    probeRequestResolver: () -> GpuProbeRequest?,
    diagnosticsReader: NativeVulkanDiagnosticsReader = NativeVulkanDiagnosticsReader(
        payloadProvider = { diagnosticsJson(driverVersion = 1L) },
    ),
    scope: TestScope = TestScope(),
): InternalAndroidGpuOffloadQualifier {
    var now = 1_000L
    return InternalAndroidGpuOffloadQualifier(
        probeClient = probeClient,
        probeRequestResolver = probeRequestResolver,
        nativeDiagnosticsReader = diagnosticsReader,
        now = { now++ },
        appBuildSignature = "1:100",
        deviceFingerprint = "fingerprint-1",
        resultStore = InMemoryProbeResultStore(),
        scope = scope,
    )
}

private fun testProbeRequest(): GpuProbeRequest {
    return GpuProbeRequest(
        modelId = "qwen3.5-0.8b-q4",
        modelVersion = "v1",
        modelPath = "/tmp/model.gguf",
        layerLadder = listOf(1, 2, 4, 8, 16, 32),
    )
}

private fun diagnosticsJson(driverVersion: Long): String {
    return """
        {
          "runtime_supported": true,
          "driver_name": "test-driver",
          "driver_version": $driverVersion,
          "instance_api_version": 4202496,
          "selected_device_api_version": 4202496,
          "storage_buffer_16bit_access": true,
          "shader_float16": true
        }
    """.trimIndent()
}

private class InMemoryProbeResultStore : GpuProbeResultStore {
    private val values: MutableMap<String, String> = mutableMapOf()

    override fun get(cacheKey: String): String? = values[cacheKey]

    override fun put(cacheKey: String, value: String) {
        values[cacheKey] = value
    }
}

private class RecordingProbeClient(
    private val response: GpuProbeResult,
) : GpuProbeClient {
    var callCount: Int = 0
        private set

    override suspend fun runProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult {
        assertTrue(request.modelId.isNotBlank())
        assertTrue(timeoutMs > 0)
        callCount += 1
        return response
    }
}
