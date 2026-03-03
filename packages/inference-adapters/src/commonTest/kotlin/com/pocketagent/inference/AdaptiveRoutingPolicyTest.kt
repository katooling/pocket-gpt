package com.pocketagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveRoutingPolicyTest {
    private val routing = AdaptiveRoutingPolicy()

    @Test
    fun `uses smoke model in high thermal conditions`() {
        val selected = routing.selectModel(
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 8, ramClassGb = 8),
        )
        assertEquals(ModelCatalog.SMOKE_ECHO_120M, selected)
    }

    @Test
    fun `uses qwen 2b for long text on high ram`() {
        val selected = routing.selectModel(
            taskType = "long_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 12),
        )
        assertEquals(ModelCatalog.QWEN_3_5_2B_Q4, selected)
    }
}
