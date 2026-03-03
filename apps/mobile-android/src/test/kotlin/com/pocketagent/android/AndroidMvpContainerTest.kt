package com.pocketagent.android

import com.pocketagent.inference.DeviceState
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidMvpContainerTest {
    private val container = AndroidMvpContainer()

    @Test
    fun `send user message returns non-empty assistant text`() {
        val session = container.createSession()
        val response = container.sendUserMessage(
            sessionId = session,
            userText = "hello",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
        )
        assertTrue(response.text.isNotBlank())
        assertTrue(response.firstTokenLatencyMs >= 0)
    }
}
