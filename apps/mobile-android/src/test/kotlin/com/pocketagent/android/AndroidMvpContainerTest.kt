package com.pocketagent.android

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidMvpContainerTest {
    @Test
    fun `send user message runs load generate unload lifecycle`() {
        val inference = RecordingInferenceModule()
        val container = AndroidMvpContainer(inferenceModule = inference)
        val session = container.createSession()
        val response = container.sendUserMessage(
            sessionId = session,
            userText = "hello",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
        )

        assertTrue(response.text.isNotBlank())
        assertTrue(response.firstTokenLatencyMs >= 0)
        assertEquals(1, inference.loadCalls.size)
        assertEquals(1, inference.generateCalls)
        assertEquals(1, inference.unloadCalls)
    }

    @Test
    fun `send user message fails when runtime model cannot load`() {
        val inference = RecordingInferenceModule(allowLoad = false)
        val container = AndroidMvpContainer(inferenceModule = inference)
        val session = container.createSession()

        val error = assertFailsWith<IllegalStateException> {
            container.sendUserMessage(
                sessionId = session,
                userText = "hello",
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            )
        }
        assertTrue(error.message?.contains("Failed to load runtime model") == true)
        assertEquals(0, inference.generateCalls)
        assertEquals(0, inference.unloadCalls)
    }

    @Test
    fun `send user message fails when runtime emits no tokens`() {
        val inference = RecordingInferenceModule(tokensToEmit = emptyList())
        val container = AndroidMvpContainer(inferenceModule = inference)
        val session = container.createSession()

        val error = assertFailsWith<IllegalStateException> {
            container.sendUserMessage(
                sessionId = session,
                userText = "hello",
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            )
        }
        assertTrue(error.message?.contains("Runtime returned no tokens") == true)
        assertEquals(1, inference.unloadCalls)
    }

    @Test
    fun `follow-up prompt includes relevant memory snippet`() {
        val inference = RecordingInferenceModule()
        val container = AndroidMvpContainer(inferenceModule = inference)
        val session = container.createSession()

        container.sendUserMessage(
            sessionId = session,
            userText = "project launch timeline and owner updates",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 90, thermalLevel = 3, ramClassGb = 8),
        )
        container.sendUserMessage(
            sessionId = session,
            userText = "what is the launch timeline follow up",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 90, thermalLevel = 3, ramClassGb = 8),
        )

        assertEquals(2, inference.capturedPrompts.size)
        val secondPrompt = inference.capturedPrompts[1]
        assertTrue(secondPrompt.contains("memory: project launch timeline and owner updates"))
    }

    @Test
    fun `startup checks fail when artifact checksum metadata is missing`() {
        val container = AndroidMvpContainer(
            inferenceModule = RecordingInferenceModule(),
            artifactSha256ByModelId = emptyMap(),
        )

        val checks = container.runStartupChecks()

        assertTrue(checks.isNotEmpty())
        assertTrue(checks.first().contains("Artifact manifest invalid"))
        assertTrue(checks.first().contains(AndroidMvpContainer.QWEN_0_8B_SHA256_ENV))
        assertTrue(checks.first().contains(AndroidMvpContainer.QWEN_2B_SHA256_ENV))
    }

    @Test
    fun `startup checks pass with valid sha256 metadata and ready runtime models`() {
        val inference = RecordingInferenceModule()
        val container = AndroidMvpContainer(
            inferenceModule = inference,
            artifactSha256ByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ModelCatalog.QWEN_3_5_2B_Q4 to "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            ),
        )

        val checks = container.runStartupChecks()

        assertEquals(emptyList(), checks)
        assertTrue(inference.loadCalls.contains(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertNotEquals(0, inference.unloadCalls)
    }

    @Test
    fun `send user message fails when policy rejects inference event`() {
        val policy = RecordingPolicyModule(deniedEvents = setOf("inference.generate"))
        val container = AndroidMvpContainer(
            inferenceModule = RecordingInferenceModule(),
            policyModule = policy,
        )
        val session = container.createSession()

        val error = assertFailsWith<IllegalStateException> {
            container.sendUserMessage(
                sessionId = session,
                userText = "hello",
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            )
        }

        assertTrue(error.message?.contains("Policy module rejected inference event type") == true)
        assertTrue(policy.observedEvents.contains("routing.model_select"))
        assertTrue(policy.observedEvents.contains("memory.write_user_turn"))
        assertTrue(policy.observedEvents.contains("inference.generate"))
    }

    @Test
    fun `run tool is blocked when policy rejects tool event`() {
        val container = AndroidMvpContainer(
            policyModule = RecordingPolicyModule(deniedEvents = setOf("tool.execute")),
        )

        val result = container.runTool("calculator", "{\"expression\":\"1+2\"}")

        assertEquals("Tool error: Policy module rejected tool event type.", result)
    }

    @Test
    fun `analyze image fails when policy rejects image event`() {
        val container = AndroidMvpContainer(
            policyModule = RecordingPolicyModule(deniedEvents = setOf("inference.image_analyze")),
        )

        val error = assertFailsWith<IllegalStateException> {
            container.analyzeImage("photo.jpg", "what is shown")
        }

        assertTrue(error.message?.contains("Policy module rejected image analysis event type") == true)
    }

    @Test
    fun `export diagnostics fails when policy rejects diagnostics export event`() {
        val container = AndroidMvpContainer(
            policyModule = RecordingPolicyModule(deniedEvents = setOf("observability.export")),
        )

        val error = assertFailsWith<IllegalStateException> {
            container.exportDiagnostics()
        }

        assertTrue(error.message?.contains("Policy module rejected diagnostics export event type") == true)
    }

    @Test
    fun `export diagnostics redacts sensitive payload fields`() {
        val container = AndroidMvpContainer(
            policyModule = RecordingPolicyModule(),
            observabilityModule = LeakyObservabilityModule(
                diagnostics = "inference.total_ms=count:1,avg_ms:65.00|prompt:secret-value;memory=user-ssn;tool_args={\"token\":\"abc\"};thermal_samples=3",
            ),
        )

        val diagnostics = container.exportDiagnostics()

        assertTrue(diagnostics.contains("inference.total_ms=count:1,avg_ms:65.00"))
        assertTrue(diagnostics.contains("thermal_samples=3"))
        assertTrue(diagnostics.contains("prompt=[REDACTED]"))
        assertTrue(diagnostics.contains("memory=[REDACTED]"))
        assertTrue(diagnostics.contains("tool_args=[REDACTED]"))
        assertFalse(diagnostics.contains("secret-value"))
        assertFalse(diagnostics.contains("user-ssn"))
        assertFalse(diagnostics.contains("\"token\":\"abc\""))
    }
}

private class RecordingInferenceModule(
    private val allowLoad: Boolean = true,
    private val tokensToEmit: List<String> = listOf("real ", "runtime ", "response "),
) : InferenceModule {
    val loadCalls = mutableListOf<String>()
    val capturedPrompts = mutableListOf<String>()
    var unloadCalls: Int = 0
    var generateCalls: Int = 0

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    )

    override fun loadModel(modelId: String): Boolean {
        loadCalls.add(modelId)
        return allowLoad
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        generateCalls += 1
        capturedPrompts.add(request.prompt)
        tokensToEmit.forEach(onToken)
    }

    override fun unloadModel() {
        unloadCalls += 1
    }
}

private class RecordingPolicyModule(
    private val deniedEvents: Set<String> = emptySet(),
) : PolicyModule {
    val observedEvents: MutableList<String> = mutableListOf()

    override fun isNetworkAllowedForAction(action: String): Boolean = false

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean {
        observedEvents.add(eventType)
        return !deniedEvents.contains(eventType)
    }
}

private class LeakyObservabilityModule(
    private val diagnostics: String,
) : ObservabilityModule {
    override fun recordLatencyMetric(name: String, valueMs: Double) {
        // no-op for this test double
    }

    override fun recordThermalSnapshot(level: Int) {
        // no-op for this test double
    }

    override fun exportLocalDiagnostics(): String = diagnostics
}
