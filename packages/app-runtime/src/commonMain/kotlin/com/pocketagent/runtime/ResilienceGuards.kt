package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState

data class StartupCheckAssessment(
    val blockingChecks: List<String>,
    val recoverableChecks: List<String>,
) {
    val canProceed: Boolean
        get() = blockingChecks.isEmpty()
}

class StartupAssessor {
    fun assessStartupChecks(checks: List<String>): StartupCheckAssessment {
        if (checks.isEmpty()) {
            return StartupCheckAssessment(blockingChecks = emptyList(), recoverableChecks = emptyList())
        }

        val blocking = mutableListOf<String>()
        val recoverable = mutableListOf<String>()
        checks.forEach { check ->
            val normalized = check.trim().lowercase()
            if (isBlockingStartupCheck(normalized)) {
                blocking.add(check)
            } else {
                recoverable.add(check)
            }
        }
        return StartupCheckAssessment(
            blockingChecks = blocking,
            recoverableChecks = recoverable,
        )
    }

    private fun isBlockingStartupCheck(normalizedCheck: String): Boolean {
        return BLOCKING_STARTUP_SIGNATURES.any { normalizedCheck.contains(it) }
    }

    companion object {
        private val BLOCKING_STARTUP_SIGNATURES = listOf(
            "artifact manifest invalid",
            "artifact verification failed",
            "model_artifact_verification_error",
            "missing runtime model",
            "failed to load baseline runtime model",
            "runtime backend is adb_fallback",
            "runtime backend is unavailable",
            "network policy wiring invalid",
            "network security config invalid",
            "network security config missing",
        )
    }
}

class TaskGuard(
    private val maxPromptChars: Int = 8000,
    private val minBatteryForLongTasks: Int = 20,
) {
    fun validatePrompt(prompt: String): String {
        if (prompt.length <= maxPromptChars) {
            return prompt
        }
        return prompt.take(maxPromptChars)
    }

    fun canRunTask(taskType: String, deviceState: DeviceState): Boolean {
        if (taskType == "long_text" && deviceState.batteryPercent < minBatteryForLongTasks) {
            return false
        }
        if (deviceState.thermalLevel >= 8) {
            return false
        }
        return true
    }
}

class SessionRecoveryPolicy(
    private val maxConsecutiveRuntimeFailures: Int = 2,
) {
    fun shouldResetSessionAfterFailure(
        consecutiveFailures: Int,
        errorMessage: String?,
    ): Boolean {
        if (consecutiveFailures >= maxConsecutiveRuntimeFailures) {
            return true
        }
        val message = errorMessage?.lowercase().orEmpty()
        return RUNTIME_RESET_SIGNATURES.any { message.contains(it) }
    }

    companion object {
        private val RUNTIME_RESET_SIGNATURES = listOf(
            "runtime returned no tokens",
            "failed to load runtime model",
            "model artifact not registered",
        )
    }
}
