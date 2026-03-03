package com.pocketagent.inference

class AdaptiveRoutingPolicy : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String {
        if (deviceState.thermalLevel >= 7 || deviceState.batteryPercent < 20) {
            return ModelCatalog.QWEN_3_5_0_8B_Q4
        }
        if (deviceState.ramClassGb >= 8 && taskType in setOf("reasoning", "image")) {
            return ModelCatalog.QWEN_3_5_0_8B_Q4
        }
        if (deviceState.ramClassGb >= 12 && taskType == "long_text") {
            return ModelCatalog.QWEN_3_5_2B_Q4
        }
        return ModelCatalog.QWEN_3_5_0_8B_Q4
    }

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int {
        val base = when (taskType) {
            "long_text" -> 8192
            "image" -> 4096
            else -> 4096
        }
        return when {
            deviceState.thermalLevel >= 7 -> 2048
            deviceState.batteryPercent < 20 -> 2048
            else -> base
        }
    }
}
