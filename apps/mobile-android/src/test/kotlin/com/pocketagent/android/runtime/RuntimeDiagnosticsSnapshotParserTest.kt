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
            GPU_PROBE|status=QUALIFIED|max_layers=8|reason=none|detail=ok|cache_key=abc|native_backend_payload={"compiled_backend":"hexagon,opencl","backend_profile":"opencl","runtime_supported":true,"strict_accelerator_fail_fast":true,"auto_backend_cpu_fallback":false}
        """.trimIndent()

        val snapshot = RuntimeDiagnosticsSnapshotParser.parse(diagnostics)

        assertEquals("opencl", snapshot.backendProfile)
        assertEquals("hexagon,opencl", snapshot.compiledBackend)
        assertEquals(true, snapshot.nativeRuntimeSupported)
        assertEquals(true, snapshot.strictAcceleratorFailFast)
        assertEquals(false, snapshot.autoBackendCpuFallback)
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
