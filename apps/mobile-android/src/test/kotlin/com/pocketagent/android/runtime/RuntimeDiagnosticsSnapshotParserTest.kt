package com.pocketagent.android.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeDiagnosticsSnapshotParserTest {
    @Test
    fun `parser extracts backend profile and compiled backends from diagnostics`() {
        val diagnostics = """
            diag=ok
            GPU_OFFLOAD|runtime_supported=true|device_feature_advisory_supported=true|probe_status=QUALIFIED|probe_layers=8|probe_reason=none|probe_source=runtime_plus_probe|probe_detail=ok
            GPU_PROBE|status=QUALIFIED|max_layers=8|reason=none|detail=ok|cache_key=abc|native_backend_payload={"compiled_backend":"hexagon,opencl","backend_profile":"opencl","active_backend":"opencl","runtime_supported":true,"strict_accelerator_fail_fast":true,"auto_backend_cpu_fallback":false,"opencl_device_version":"2.0","opencl_adreno_generation":7,"active_model_quantization":"q4_k_m","flash_attn_guard_reason":"opencl_backend","quantized_kv_guard_reason":"opencl_backend"}
        """.trimIndent()

        val snapshot = RuntimeDiagnosticsSnapshotParser.parse(diagnostics)

        assertEquals("opencl", snapshot.backendProfile)
        assertEquals("hexagon,opencl", snapshot.compiledBackend)
        assertEquals("opencl", snapshot.activeBackend)
        assertEquals(true, snapshot.nativeRuntimeSupported)
        assertEquals(true, snapshot.strictAcceleratorFailFast)
        assertEquals(false, snapshot.autoBackendCpuFallback)
        assertEquals("2.0", snapshot.openclDeviceVersion)
        assertEquals(7, snapshot.openclAdrenoGeneration)
        assertEquals("q4_k_m", snapshot.activeModelQuantization)
        assertEquals("opencl_backend", snapshot.flashAttnGuardReason)
        assertEquals("opencl_backend", snapshot.quantizedKvGuardReason)
    }

    @Test
    fun `parser tolerates missing diagnostics lines`() {
        val snapshot = RuntimeDiagnosticsSnapshotParser.parse("diag=ok")

        assertNull(snapshot.backendProfile)
        assertNull(snapshot.compiledBackend)
        assertNull(snapshot.nativeRuntimeSupported)
        assertNull(snapshot.strictAcceleratorFailFast)
    }
}
