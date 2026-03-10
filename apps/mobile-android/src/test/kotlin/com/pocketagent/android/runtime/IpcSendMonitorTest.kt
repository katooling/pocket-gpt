package com.pocketagent.android.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpcSendMonitorTest {
    @Test
    fun `send records failure count and logs context`() {
        val logs = mutableListOf<String>()
        val monitor = IpcSendMonitor(
            tag = "TestTag",
            logger = { message, _ -> logs += message },
        )

        val success = monitor.send(IpcMessageKind.REPLY) {
            error("simulated send failure")
        }

        assertFalse(success)
        assertEquals(1, monitor.failureCount(IpcMessageKind.REPLY))
        assertTrue(logs.single().contains("IPC_SEND_FAILURE|kind=REPLY|count=1"))
    }

    @Test
    fun `send succeeds without touching failure counters`() {
        val monitor = IpcSendMonitor(tag = "TestTag")

        val success = monitor.send(IpcMessageKind.STREAM_RESULT) {
            // no-op
        }

        assertTrue(success)
        assertEquals(0, monitor.failureCount(IpcMessageKind.STREAM_RESULT))
        assertTrue(monitor.failureSnapshot().isEmpty())
    }
}

