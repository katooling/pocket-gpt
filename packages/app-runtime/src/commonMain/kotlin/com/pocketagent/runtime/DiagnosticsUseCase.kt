package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule

internal class DiagnosticsUseCase(
    private val policyModule: PolicyModule,
    private val observabilityModule: ObservabilityModule,
    private val diagnosticsRedactor: DiagnosticsRedactor,
) {
    fun export(): String {
        check(policyModule.enforceDataBoundary("observability.export")) {
            "Policy module rejected diagnostics export event type."
        }
        return diagnosticsRedactor.redact(observabilityModule.exportLocalDiagnostics())
    }
}
