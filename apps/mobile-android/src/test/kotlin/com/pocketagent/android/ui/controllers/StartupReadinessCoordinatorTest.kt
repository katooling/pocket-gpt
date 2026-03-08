package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupReadinessCoordinatorTest {
    private val coordinator = StartupReadinessCoordinator()

    @Test
    fun `empty checks resolve ready state`() {
        val decision = coordinator.decide(
            startupChecks = emptyList(),
            runtimeBackend = "NATIVE_JNI",
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.READY, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.READY, decision.modelRuntimeStatus)
        assertTrue(decision.modelStatusDetail.contains("NATIVE_JNI"))
        assertNull(decision.startupError)
    }

    @Test
    fun `timeout only checks resolve blocked timeout with startup error`() {
        val decision = coordinator.decide(
            startupChecks = listOf("Startup checks timed out after 30s."),
            runtimeBackend = null,
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.BLOCKED_TIMEOUT, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.NOT_READY, decision.modelRuntimeStatus)
        assertEquals(0, decision.startupWarnings.size)
        assertNotNull(decision.startupError)
    }

    @Test
    fun `optional model warnings resolve ready state with warnings`() {
        val decision = coordinator.decide(
            startupChecks = listOf("Optional runtime model unavailable: qwen3.5-2b-q4."),
            runtimeBackend = null,
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.READY, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.READY, decision.modelRuntimeStatus)
        assertTrue(decision.modelStatusDetail.contains("optional model unavailable"))
        assertEquals(1, decision.startupWarnings.size)
        assertNull(decision.startupError)
    }

    @Test
    fun `blocking startup checks return blocked state and mapped startup error`() {
        val decision = coordinator.decide(
            startupChecks = listOf("Missing runtime model(s): qwen3.5-0.8b-q4."),
            runtimeBackend = null,
            statusDetailOverride = null,
        )

        assertEquals(StartupProbeState.BLOCKED, decision.startupProbeState)
        assertEquals(ModelRuntimeStatus.NOT_READY, decision.modelRuntimeStatus)
        assertNotNull(decision.startupError)
        assertEquals("UI-STARTUP-001", decision.startupError?.code)
    }
}
