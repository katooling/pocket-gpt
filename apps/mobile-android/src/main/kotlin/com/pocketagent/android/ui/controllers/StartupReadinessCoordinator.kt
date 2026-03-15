package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry

data class StartupReadinessDecision(
    val startupProbeState: StartupProbeState,
    val modelRuntimeStatus: ModelRuntimeStatus,
    val modelStatusDetail: String,
    val startupWarnings: List<String>,
    val startupError: UiError?,
)

class StartupReadinessCoordinator(
    private val modelRegistry: ModelRegistry = ModelRegistry.default(),
    private val runtimeProfile: ModelRuntimeProfile = ModelRuntimeProfile.PROD,
) {
    fun decide(
        startupChecks: List<String>,
        runtimeBackend: String?,
        statusDetailOverride: String?,
    ): StartupReadinessDecision {
        if (startupChecks.isEmpty()) {
            return StartupReadinessDecision(
                startupProbeState = StartupProbeState.READY,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
                modelStatusDetail = statusDetailOverride ?: readyStatusDetail(runtimeBackend),
                startupWarnings = emptyList(),
                startupError = null,
            )
        }

        if (isStartupTimeoutOnlyFailure(startupChecks)) {
            return StartupReadinessDecision(
                startupProbeState = StartupProbeState.BLOCKED_TIMEOUT,
                modelRuntimeStatus = ModelRuntimeStatus.NOT_READY,
                modelStatusDetail = "Startup checks timed out. Runtime readiness is unknown; refresh checks before sending.",
                startupWarnings = emptyList(),
                startupError = UiErrorMapper.startupFailure(startupChecks),
            )
        }

        if (isOptionalModelOnlyStartupFailure(startupChecks)) {
            return StartupReadinessDecision(
                startupProbeState = StartupProbeState.READY,
                modelRuntimeStatus = ModelRuntimeStatus.READY,
                modelStatusDetail = optionalModelStatusDetail(startupChecks),
                startupWarnings = startupChecks,
                startupError = null,
            )
        }

        return StartupReadinessDecision(
            startupProbeState = StartupProbeState.BLOCKED,
            modelRuntimeStatus = resolveModelStatusFromStartupChecks(startupChecks),
            modelStatusDetail = startupChecks.firstOrNull() ?: "Runtime startup checks failed.",
            startupWarnings = emptyList(),
            startupError = UiErrorMapper.startupFailure(startupChecks),
        )
    }

    private fun readyStatusDetail(runtimeBackend: String?): String {
        return if (runtimeBackend.isNullOrBlank()) {
            "Runtime model ready"
        } else {
            "Runtime model ready ($runtimeBackend)"
        }
    }

    private fun isStartupTimeoutOnlyFailure(startupChecks: List<String>): Boolean {
        if (startupChecks.isEmpty()) {
            return false
        }
        return startupChecks.all { check ->
            val normalized = check.lowercase()
            normalized.contains("startup checks timed out") || normalized.contains("timed out")
        }
    }

    private fun isOptionalModelOnlyStartupFailure(startupChecks: List<String>): Boolean {
        if (startupChecks.isEmpty()) {
            return false
        }
        return startupChecks.all { check ->
            check.lowercase().contains("optional runtime model unavailable")
        }
    }

    private fun optionalModelStatusDetail(startupChecks: List<String>): String {
        val missing = startupChecks
            .filter { it.lowercase().contains("optional runtime model unavailable") }
            .mapNotNull { check ->
                OPTIONAL_MODEL_ID_REGEX.find(check)?.groupValues?.getOrNull(1)?.trim()
            }
            .toSet()
        val startupModelCount = modelRegistry.startupPolicy(profile = runtimeProfile).candidateModelIds.size.coerceAtLeast(1)
        val readyCount = (startupModelCount - missing.size).coerceAtLeast(1)
        return if (missing.isEmpty()) {
            "Runtime ready. Optional models are still being provisioned."
        } else {
            val preview = missing.sorted().take(3)
            val overflowCount = (missing.size - preview.size).coerceAtLeast(0)
            val suffix = if (overflowCount > 0) ", +$overflowCount more" else ""
            "$readyCount model ready, ${missing.size} optional model unavailable (${preview.joinToString(", ")}$suffix)."
        }
    }

    private fun resolveModelStatusFromStartupChecks(startupChecks: List<String>): ModelRuntimeStatus {
        if (startupChecks.isEmpty()) {
            return ModelRuntimeStatus.READY
        }
        val startupSummary = startupChecks.joinToString(" ").lowercase()
        if (startupSummary.contains("optional runtime model unavailable")) {
            return ModelRuntimeStatus.READY
        }
        return if (
            startupSummary.contains("missing runtime model") ||
            startupSummary.contains("artifact verification failed") ||
            startupSummary.contains("model_artifact_config_missing")
        ) {
            ModelRuntimeStatus.NOT_READY
        } else {
            ModelRuntimeStatus.ERROR
        }
    }

    private companion object {
        val OPTIONAL_MODEL_ID_REGEX = Regex(
            pattern = """optional runtime model unavailable:\s*([a-z0-9._-]+)""",
            option = RegexOption.IGNORE_CASE,
        )
    }
}
