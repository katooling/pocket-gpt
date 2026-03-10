package com.pocketagent.android.runtime

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GpuOffloadQualificationTest {
    @Test
    fun `runtime unsupported fails fast without probe`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = {
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 8,
                )
            },
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
    fun `no provisioned model fails immediately without entering pending state`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = {
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 8,
                )
            },
        )
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { null },
        )

        val result = qualifier.evaluate(runtimeSupported = true)

        assertEquals(GpuProbeStatus.FAILED, result.status)
        assertEquals(GpuProbeFailureReason.MODEL_UNAVAILABLE, result.failureReason)
        assertEquals("download_model_to_validate_gpu", result.detail)
        assertEquals(0, probeClient.callCount)
    }

    @Test
    fun `qualification transitions pending to cached qualified`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = {
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 16,
                )
            },
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
        assertEquals(6, probeClient.callCount)

        val cached = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, cached.status)
        assertEquals(6, probeClient.callCount)
    }

    @Test
    fun `cache key invalidates when driver diagnostics change`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = {
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 4,
                )
            },
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
        assertEquals(4, probeClient.callCount)

        diagnosticsPayload = diagnosticsJson(driverVersion = 2L)
        val invalidated = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.PENDING, invalidated.status)
        assertEquals(4, probeClient.callCount)

        advanceUntilIdle()
        val requalified = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, requalified.status)
        assertEquals(8, probeClient.callCount)
    }

    @Test
    fun `cache key reuses qualification for same model bits across version relabels`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = {
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 4,
                )
            },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        var request = testProbeRequest(
            modelVersion = "v1",
            modelContentFingerprint = "sha-123",
            modelFileSizeBytes = 507_154_688L,
        )
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { request },
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        assertEquals(GpuProbeStatus.QUALIFIED, qualifier.evaluate(runtimeSupported = true).status)
        assertEquals(4, probeClient.callCount)

        request = testProbeRequest(
            modelVersion = "manual-import-2",
            modelContentFingerprint = "sha-123",
            modelFileSizeBytes = 507_154_688L,
        )

        val reused = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, reused.status)
        assertEquals(4, probeClient.callCount)
    }

    @Test
    fun `qualification keeps lower stable layer when upper layer probe process dies`() = runTest {
        val probeClient = RecordingProbeClient { request ->
            val layer = request.layerLadder.singleOrNull() ?: 0
            if (layer >= 32) {
                GpuProbeResult(
                    status = GpuProbeStatus.FAILED,
                    failureReason = GpuProbeFailureReason.PROBE_PROCESS_DIED,
                    detail = "probe_process_disconnected",
                )
            } else {
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = layer,
                    detail = "probe_success",
                )
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        val result = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, result.status)
        assertEquals(16, result.maxStableGpuLayers)
        assertTrue(result.detail?.contains("reason=PROBE_PROCESS_DIED") == true)
        assertEquals(6, probeClient.callCount)
    }

    @Test
    fun `qualification uses adaptive per-layer timeout for default policy`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = { request ->
                val layer = request.layerLadder.singleOrNull() ?: 0
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = layer,
                    detail = "probe_success",
                )
            },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        val result = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, result.status)
        assertEquals(32, result.maxStableGpuLayers)
        assertEquals(listOf(20_000L, 20_000L, 25_000L, 30_000L, 38_000L, 45_000L), probeClient.timeoutHistory)
    }

    @Test
    fun `qualification uses conservative ladder when half precision features are missing`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = { request ->
                val layer = request.layerLadder.singleOrNull() ?: 0
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = layer,
                    detail = "probe_success",
                )
            },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            diagnosticsReader = NativeVulkanDiagnosticsReader(
                payloadProvider = { diagnosticsJson(driverVersion = 1L, shaderFloat16 = false) },
            ),
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        val result = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, result.status)
        assertEquals(8, result.maxStableGpuLayers)
        assertEquals(4, probeClient.callCount)
        assertEquals(listOf(1, 2, 4, 8), probeClient.layerHistory)
    }

    @Test
    fun `qualification caps ladder using model file size and device local heap budget`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = { request ->
                val layer = request.layerLadder.singleOrNull() ?: 0
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = layer,
                    detail = "probe_success",
                )
            },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = {
                testProbeRequest(
                    modelVersion = "large-v1",
                    modelFileSizeBytes = 3_072L * 1024L * 1024L,
                )
            },
            diagnosticsReader = NativeVulkanDiagnosticsReader(
                payloadProvider = {
                    diagnosticsJson(
                        driverVersion = 1L,
                        deviceLocalHeapBytes = 1_024L * 1024L * 1024L,
                    )
                },
            ),
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        val result = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.QUALIFIED, result.status)
        assertEquals(8, result.maxStableGpuLayers)
        assertEquals(listOf(1, 2, 4, 8), probeClient.layerHistory)
    }

    @Test
    fun `runtime failure report demotes previously qualified gpu result`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = { request ->
                val layer = request.layerLadder.singleOrNull() ?: 0
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = layer,
                    detail = "probe_success",
                )
            },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        assertEquals(GpuProbeStatus.QUALIFIED, qualifier.evaluate(runtimeSupported = true).status)

        qualifier.reportRuntimeFailure(
            reason = GpuProbeFailureReason.NATIVE_GENERATE_FAILED,
            detail = "jni_runtime_error",
        )
        val demoted = qualifier.evaluate(runtimeSupported = true)

        assertEquals(GpuProbeStatus.FAILED, demoted.status)
        assertEquals(GpuProbeFailureReason.NATIVE_GENERATE_FAILED, demoted.failureReason)
        assertTrue(demoted.detail?.contains("runtime_failure_demoted:") == true)
        assertEquals(6, probeClient.callCount)
    }

    @Test
    fun `stale in flight probe state is restarted instead of staying pending forever`() = runTest {
        val probeClient = RecordingProbeClient(
            responseForRequest = {
                GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 8,
                )
            },
        )
        val clock = ProbeTestClock(startAtMs = 1_000L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            scope = TestScope(dispatcher),
            clock = clock,
        )

        val firstPending = qualifier.evaluate(runtimeSupported = true)
        assertEquals(GpuProbeStatus.PENDING, firstPending.status)

        clock.advanceBy(10 * 60_000L)
        val secondPending = qualifier.evaluate(runtimeSupported = true)

        assertEquals(GpuProbeStatus.PENDING, secondPending.status)
        assertTrue(secondPending.checkedAtEpochMs > firstPending.checkedAtEpochMs)
    }

    @Test
    fun `retriable probe failures do not add final unnecessary retry delay`() = runTest {
        val probeClient = RecordingProbeClient {
            GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.SERVICE_BUSY,
                detail = "service_busy",
            )
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val qualifier = buildQualifier(
            probeClient = probeClient,
            probeRequestResolver = { testProbeRequest() },
            scope = TestScope(dispatcher),
        )

        assertEquals(GpuProbeStatus.PENDING, qualifier.evaluate(runtimeSupported = true).status)
        advanceUntilIdle()
        val result = qualifier.evaluate(runtimeSupported = true)

        assertEquals(GpuProbeStatus.FAILED, result.status)
        assertEquals(GpuProbeFailureReason.SERVICE_BUSY, result.failureReason)
        assertEquals(13, probeClient.callCount)
        assertEquals(12 * 5_000L, testScheduler.currentTime)
        assertFalse(result.detail.isNullOrBlank())
    }
}

private fun buildQualifier(
    probeClient: GpuProbeClient,
    probeRequestResolver: () -> GpuProbeRequest?,
    diagnosticsReader: NativeVulkanDiagnosticsReader = NativeVulkanDiagnosticsReader(
        payloadProvider = { diagnosticsJson(driverVersion = 1L) },
    ),
    scope: TestScope = TestScope(),
    clock: ProbeTestClock = ProbeTestClock(startAtMs = 1_000L),
): InternalAndroidGpuOffloadQualifier {
    return InternalAndroidGpuOffloadQualifier(
        probeClient = probeClient,
        probeRequestResolver = probeRequestResolver,
        nativeDiagnosticsReader = diagnosticsReader,
        now = { clock.now() },
        appBuildSignature = "1:100",
        deviceFingerprint = "fingerprint-1",
        resultStore = InMemoryProbeResultStore(),
        scope = scope,
    )
}

private fun testProbeRequest(
    modelVersion: String = "v1",
    modelContentFingerprint: String? = null,
    modelFileSizeBytes: Long = 0L,
): GpuProbeRequest {
    return GpuProbeRequest(
        modelId = "qwen3.5-0.8b-q4",
        modelVersion = modelVersion,
        modelPath = "/tmp/model.gguf",
        layerLadder = listOf(1, 2, 4, 8, 16, 32),
        modelContentFingerprint = modelContentFingerprint,
        modelFileSizeBytes = modelFileSizeBytes,
    )
}

private fun diagnosticsJson(
    driverVersion: Long,
    shaderFloat16: Boolean = true,
    storageBuffer16BitAccess: Boolean = true,
    selectedDeviceApiVersion: Long = 4202496L,
    deviceLocalHeapBytes: Long = 0L,
): String {
    return """
        {
          "runtime_supported": true,
          "driver_name": "test-driver",
          "driver_version": $driverVersion,
          "instance_api_version": 4202496,
          "selected_device_api_version": $selectedDeviceApiVersion,
          "device_local_heap_bytes": $deviceLocalHeapBytes,
          "storage_buffer_16bit_access": $storageBuffer16BitAccess,
          "shader_float16": $shaderFloat16
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
    private val responseForRequest: (GpuProbeRequest) -> GpuProbeResult,
) : GpuProbeClient {
    var callCount: Int = 0
        private set
    val timeoutHistory: MutableList<Long> = mutableListOf()
    val layerHistory: MutableList<Int> = mutableListOf()

    override suspend fun runProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult {
        assertTrue(request.modelId.isNotBlank())
        assertTrue(request.layerLadder.size == 1)
        assertTrue(timeoutMs > 0)
        callCount += 1
        timeoutHistory += timeoutMs
        layerHistory += request.layerLadder.single()
        return responseForRequest(request)
    }
}

private class ProbeTestClock(
    startAtMs: Long,
) {
    private var current: Long = startAtMs

    fun now(): Long = current++

    fun advanceBy(deltaMs: Long) {
        current += deltaMs.coerceAtLeast(0L)
    }
}
