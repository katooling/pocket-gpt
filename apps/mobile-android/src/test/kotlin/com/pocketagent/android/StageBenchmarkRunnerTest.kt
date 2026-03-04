package com.pocketagent.android

import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageBenchmarkRunnerTest {
    @Test
    fun `scenario A computes medians and passes threshold`() {
        val runtime = FakeStageBenchmarkRuntime(
            sendResponses = mutableListOf(
                response(firstTokenMs = 1800, totalMs = 1000, tokenCount = 10),
                response(firstTokenMs = 2200, totalMs = 1000, tokenCount = 8),
                response(firstTokenMs = 2000, totalMs = 1000, tokenCount = 9),
            ),
        )
        val runner = StageBenchmarkRunner(runtime)

        val result = runner.runScenarioA(sessionRuns = 3)

        assertEquals("A", result.scenario)
        assertEquals(2000, result.p50FirstTokenMs)
        assertEquals(9.0, result.p50DecodeTokensPerSecond, 0.0001)
        assertTrue(result.pass)
    }

    @Test
    fun `scenario B fails when decode throughput is below threshold`() {
        val runtime = FakeStageBenchmarkRuntime(
            sendResponses = mutableListOf(
                response(firstTokenMs = 1400, totalMs = 2000, tokenCount = 4),
                response(firstTokenMs = 1500, totalMs = 2000, tokenCount = 5),
                response(firstTokenMs = 1300, totalMs = 2000, tokenCount = 3),
            ),
        )
        val runner = StageBenchmarkRunner(runtime)

        val result = runner.runScenarioB(sessionRuns = 3)

        assertEquals("B", result.scenario)
        assertEquals(1400, result.p50FirstTokenMs)
        assertEquals(2.0, result.p50DecodeTokensPerSecond, 0.0001)
        assertFalse(result.pass)
    }

    @Test
    fun `scenario C uses image path and reports pass on sufficient decode rate`() {
        val runtime = FakeStageBenchmarkRuntime(
            sendResponses = mutableListOf(),
            imageResponses = mutableListOf(
                "one two three four five six seven eight",
                "one two three four five six seven eight",
                "one two three four five six seven eight",
            ),
        )
        val runner = StageBenchmarkRunner(runtime)

        val result = runner.runScenarioC(sessionRuns = 3)

        assertEquals("C", result.scenario)
        assertEquals(0L, result.p50FirstTokenMs)
        assertTrue(result.p50DecodeTokensPerSecond >= 4.0)
        assertTrue(result.pass)
        assertEquals(3, runtime.imageCallCount)
    }

    private fun response(firstTokenMs: Long, totalMs: Long, tokenCount: Int): ChatResponse {
        val text = (1..tokenCount).joinToString(separator = " ") { "tok$it" }
        return ChatResponse(
            sessionId = SessionId("session"),
            modelId = "auto",
            text = text,
            firstTokenLatencyMs = firstTokenMs,
            totalLatencyMs = totalMs,
        )
    }
}

private class FakeStageBenchmarkRuntime(
    private val sendResponses: MutableList<ChatResponse>,
    private val imageResponses: MutableList<String> = mutableListOf(),
) : StageBenchmarkRuntime {
    private var sessionCounter = 0
    var imageCallCount: Int = 0
        private set

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("session-$sessionCounter")
    }

    override fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
    ): ChatResponse {
        return sendResponses.removeFirst()
    }

    override fun analyzeImage(imagePath: String, prompt: String): String {
        imageCallCount += 1
        return imageResponses.removeFirstOrNull() ?: "fallback image summary tokens"
    }
}
