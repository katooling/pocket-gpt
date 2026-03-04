package com.pocketagent.android

import com.pocketagent.inference.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResilienceGuardsTest {
    @Test
    fun `validate prompt truncates to configured max length`() {
        val guards = ResilienceGuards(maxPromptChars = 5)

        val prompt = guards.validatePrompt("123456789")

        assertEquals("12345", prompt)
    }

    @Test
    fun `can run long task only when battery is above threshold and thermal is safe`() {
        val guards = ResilienceGuards(minBatteryForLongTasks = 20)

        val lowBattery = guards.canRunTask(
            taskType = "long_text",
            deviceState = DeviceState(batteryPercent = 10, thermalLevel = 3, ramClassGb = 8),
        )
        val highThermal = guards.canRunTask(
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 8, ramClassGb = 8),
        )
        val safe = guards.canRunTask(
            taskType = "long_text",
            deviceState = DeviceState(batteryPercent = 55, thermalLevel = 4, ramClassGb = 8),
        )

        assertFalse(lowBattery)
        assertFalse(highThermal)
        assertTrue(safe)
    }

    @Test
    fun `startup check assessment separates blocking and recoverable checks`() {
        val guards = ResilienceGuards()

        val assessment = guards.assessStartupChecks(
            listOf(
                "Artifact manifest invalid: qwen@1:MISSING_SHA256",
                "Policy module rejected startup event type.",
            ),
        )

        assertFalse(assessment.canProceed)
        assertEquals(1, assessment.blockingChecks.size)
        assertEquals(1, assessment.recoverableChecks.size)
        assertTrue(assessment.blockingChecks.first().contains("Artifact manifest invalid"))
        assertTrue(assessment.recoverableChecks.first().contains("Policy module rejected startup event type"))
    }

    @Test
    fun `session reset is triggered by repeated failures or fatal signatures`() {
        val guards = ResilienceGuards(maxConsecutiveRuntimeFailures = 2)

        val repeatedFailure = guards.shouldResetSessionAfterFailure(
            consecutiveFailures = 2,
            errorMessage = "some transient error",
        )
        val fatalFailure = guards.shouldResetSessionAfterFailure(
            consecutiveFailures = 1,
            errorMessage = "Runtime returned no tokens.",
        )
        val recoverable = guards.shouldResetSessionAfterFailure(
            consecutiveFailures = 1,
            errorMessage = "temporary timeout",
        )

        assertTrue(repeatedFailure)
        assertTrue(fatalFailure)
        assertFalse(recoverable)
    }
}
