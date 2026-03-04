package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                response(firstTokenMs = 1600, totalMs = 2000, tokenCount = 4),
            ),
        )
        val runner = StageBenchmarkRunner(runtime)

        val result = runner.runScenarioB(sessionRuns = 3)

        assertEquals("B", result.scenario)
        assertEquals(1500, result.p50FirstTokenMs)
        assertEquals(2.0, result.p50DecodeTokensPerSecond, 0.0001)
        assertFalse(result.pass)
    }

    @Test
    fun `scenario C uses image analysis throughput threshold`() {
        val runtime = FakeStageBenchmarkRuntime(
            sendResponses = mutableListOf(),
            imageOutputs = mutableListOf(
                "one two three four",
                "one two three four five six",
                "one two three four five",
            ),
        )
        val runner = StageBenchmarkRunner(runtime)

        val result = runner.runScenarioC(sessionRuns = 3)

        assertEquals("C", result.scenario)
        assertEquals(0, result.p50FirstTokenMs)
        assertTrue(result.p50DecodeTokensPerSecond > 0.0)
        assertTrue(result.pass)
    }

    private fun response(firstTokenMs: Long, totalMs: Long, tokenCount: Int): ChatResponse {
        val text = (1..tokenCount).joinToString(" ") { "tok$it" }
        return ChatResponse(
            sessionId = SessionId("session"),
            modelId = "model",
            text = text,
            firstTokenLatencyMs = firstTokenMs,
            totalLatencyMs = totalMs,
        )
    }
}

private class FakeStageBenchmarkRuntime(
    private val sendResponses: MutableList<ChatResponse>,
    private val imageOutputs: MutableList<String> = mutableListOf("image tokens"),
) : StageBenchmarkRuntime {
    private var sessionCounter = 0

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
        return imageOutputs.removeFirst()
    }
}
