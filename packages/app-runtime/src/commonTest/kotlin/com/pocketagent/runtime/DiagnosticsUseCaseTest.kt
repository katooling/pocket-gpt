package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiagnosticsUseCaseTest {
    @Test
    fun `export returns redacted diagnostics when policy allows access`() {
        val useCase = DiagnosticsUseCase(
            policyModule = DiagnosticsPolicyModule(allowedEvents = setOf("observability.export")),
            observabilityModule = DiagnosticsObservabilityModule(
                diagnostics = "prompt=hello|latency_ms=12.0;content=secret;model=qwen",
            ),
            diagnosticsRedactor = DiagnosticsRedactor(),
        )

        val exported = useCase.export()

        assertEquals("prompt=[REDACTED]|latency_ms=12.0;content=[REDACTED];model=qwen", exported)
    }

    @Test
    fun `export fails when policy denies diagnostics event`() {
        val useCase = DiagnosticsUseCase(
            policyModule = DiagnosticsPolicyModule(allowedEvents = emptySet()),
            observabilityModule = DiagnosticsObservabilityModule(diagnostics = "diag"),
            diagnosticsRedactor = DiagnosticsRedactor(),
        )

        assertFailsWith<IllegalStateException> {
            useCase.export()
        }
    }
}

private class DiagnosticsPolicyModule(
    private val allowedEvents: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean = false

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = allowedEvents.contains(eventType)
}

private class DiagnosticsObservabilityModule(
    private val diagnostics: String,
) : ObservabilityModule {
    override fun recordLatencyMetric(name: String, valueMs: Double) = Unit

    override fun recordThermalSnapshot(level: Int) = Unit

    override fun exportLocalDiagnostics(): String = diagnostics
}
