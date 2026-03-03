package com.pocketagent.android

import com.pocketagent.inference.DeviceState

class ResilienceGuards(
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
