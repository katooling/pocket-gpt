package com.pocketagent.android

import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

private class RecordingInferenceModule(
    private val allowLoad: Boolean = true,
    private val tokensToEmit: List<String> = listOf("real ", "runtime ", "response "),
) : InferenceModule {
    val loadCalls = mutableListOf<String>()
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
        tokensToEmit.forEach(onToken)
    }

    override fun unloadModel() {
        unloadCalls += 1
    }
}
