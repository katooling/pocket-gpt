package com.pocketagent.android

import com.pocketagent.core.InMemoryObservabilityModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidMvpContainerTest {
    @Test
    fun `manual routing mode override forces selected model`() {
        val inference = RecordingInferenceModule()
        val container = defaultContainer(inferenceModule = inference)
        val session = container.createSession()

        container.setRoutingMode(RoutingMode.QWEN_2B)
        container.sendUserMessage(
            sessionId = session,
            userText = "force 2b",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 15, thermalLevel = 5, ramClassGb = 4),
        )

        assertEquals(ModelCatalog.QWEN_3_5_2B_Q4, inference.loadCalls.first())
    }

    @Test
    fun `send user message runs load generate unload lifecycle`() {
        val inference = RecordingInferenceModule()
        val container = defaultContainer(inferenceModule = inference)
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
        val container = defaultContainer(inferenceModule = inference)
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
        val container = defaultContainer(inferenceModule = inference)
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
        val container = defaultContainer(inferenceModule = inference)
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
        assertTrue(secondPrompt.contains("memory:"))
        assertTrue(
            secondPrompt.contains("memory: project launch timeline and owner updates") ||
                secondPrompt.contains("memory: what is the launch timeline follow up"),
        )
    }

    @Test
    fun `restored session history is used for follow up prompts`() {
        val inference = RecordingInferenceModule()
        val container = defaultContainer(inferenceModule = inference)
        val session = SessionId("restored-session")
        container.restoreSession(
            sessionId = session,
            turns = listOf(
                Turn(role = "user", content = "remember this project update", timestampEpochMs = 1),
                Turn(role = "assistant", content = "I will remember it", timestampEpochMs = 2),
            ),
        )

        container.sendUserMessage(
            sessionId = session,
            userText = "what did I ask you to remember",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
        )

        val prompt = inference.capturedPrompts.last()
        assertTrue(prompt.contains("remember this project update"))
        assertTrue(prompt.contains("I will remember it"))
        assertTrue(prompt.contains("<|im_start|>user"))
        assertTrue(prompt.contains("<|im_start|>assistant"))
    }

    @Test
    fun `startup checks fail when artifact checksum metadata is missing`() {
        val container = AndroidMvpContainer(
            inferenceModule = RecordingInferenceModule(),
            artifactSha256ByModelId = emptyMap(),
        )

        val checks = container.runStartupChecks()

        assertTrue(checks.isNotEmpty())
        assertTrue(checks.any { it.contains("MODEL_ARTIFACT_CONFIG_MISSING:model=${ModelCatalog.QWEN_3_5_0_8B_Q4};field=sha256") })
        assertTrue(checks.any { it.contains("MODEL_ARTIFACT_CONFIG_MISSING:model=${ModelCatalog.QWEN_3_5_2B_Q4};field=sha256") })
    }

    @Test
    fun `startup checks pass with valid sha256 metadata and ready runtime models`() {
        val inference = RecordingInferenceModule()
        val payloads = testPayloads()
        val container = AndroidMvpContainer(
            inferenceModule = inference,
            artifactPayloadByModelId = payloads,
            artifactSha256ByModelId = payloads.mapValues { (_, bytes) -> sha256Hex(bytes) },
            artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
                ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
                ModelCatalog.SMOLLM3_3B_Q4_K_M to "internal-release",
            ),
            artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_0_8B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4)),
                ModelCatalog.QWEN_3_5_2B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_2B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_2B_Q4)),
                ModelCatalog.SMOLLM3_3B_Q4_K_M to provenanceSignature("internal-release", ModelCatalog.SMOLLM3_3B_Q4_K_M, payloads.getValue(ModelCatalog.SMOLLM3_3B_Q4_K_M)),
            ),
        )

        val checks = container.runStartupChecks()

        assertEquals(emptyList(), checks)
        assertTrue(inference.loadCalls.isEmpty())
        assertEquals(0, inference.unloadCalls)
    }

    @Test
    fun `startup checks degrade to optional warning when one baseline model is unavailable`() {
        val payloads = testPayloads()
        val inference = RecordingInferenceModule(
            availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4),
        )
        val container = AndroidMvpContainer(
            inferenceModule = inference,
            artifactPayloadByModelId = payloads,
            artifactSha256ByModelId = payloads.mapValues { (_, bytes) -> sha256Hex(bytes) },
            artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
                ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
                ModelCatalog.SMOLLM3_3B_Q4_K_M to "internal-release",
            ),
            artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_0_8B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4)),
                ModelCatalog.QWEN_3_5_2B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_2B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_2B_Q4)),
                ModelCatalog.SMOLLM3_3B_Q4_K_M to provenanceSignature("internal-release", ModelCatalog.SMOLLM3_3B_Q4_K_M, payloads.getValue(ModelCatalog.SMOLLM3_3B_Q4_K_M)),
            ),
        )

        val checks = container.runStartupChecks()

        assertTrue(checks.any { it.contains("Optional runtime model unavailable") })
        assertTrue(checks.none { it.contains("Missing runtime model(s):") })
        assertTrue(inference.loadCalls.isEmpty())
    }

    @Test
    fun `manual 2b routing falls back to available model when preferred model is unavailable`() {
        val inference = RecordingInferenceModule(
            availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4),
        )
        val container = defaultContainer(inferenceModule = inference)
        val session = container.createSession()

        container.setRoutingMode(RoutingMode.QWEN_2B)
        val response = container.sendUserMessage(
            sessionId = session,
            userText = "force 2b but fallback to available",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 70, thermalLevel = 3, ramClassGb = 8),
        )

        assertEquals(ModelCatalog.QWEN_3_5_0_8B_Q4, response.modelId)
        assertEquals(ModelCatalog.QWEN_3_5_0_8B_Q4, inference.loadCalls.first())
    }

    @Test
    fun `startup checks fail when runtime backend is adb fallback and native is required`() {
        val bridge = BackendAwareTestBridge(backend = com.pocketagent.nativebridge.RuntimeBackend.ADB_FALLBACK)
        val payloads = testPayloads()
        val container = AndroidMvpContainer(
            inferenceModule = LlamaCppInferenceModule(bridge),
            artifactPayloadByModelId = payloads,
            artifactSha256ByModelId = payloads.mapValues { (_, bytes) -> sha256Hex(bytes) },
            artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
                ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
                ModelCatalog.SMOLLM3_3B_Q4_K_M to "internal-release",
            ),
            artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_0_8B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4)),
                ModelCatalog.QWEN_3_5_2B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_2B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_2B_Q4)),
                ModelCatalog.SMOLLM3_3B_Q4_K_M to provenanceSignature("internal-release", ModelCatalog.SMOLLM3_3B_Q4_K_M, payloads.getValue(ModelCatalog.SMOLLM3_3B_Q4_K_M)),
            ),
            requireNativeRuntimeForStartupChecks = true,
        )

        val checks = container.runStartupChecks()

        assertEquals(1, checks.size)
        assertTrue(checks.first().contains("Runtime backend is ADB_FALLBACK"))
        assertTrue(checks.first().contains(AndroidMvpContainer.REQUIRE_NATIVE_RUNTIME_STARTUP_ENV))
    }

    @Test
    fun `startup checks allow adb fallback when native runtime requirement is disabled`() {
        val bridge = BackendAwareTestBridge(backend = com.pocketagent.nativebridge.RuntimeBackend.ADB_FALLBACK)
        val payloads = testPayloads()
        val container = AndroidMvpContainer(
            inferenceModule = LlamaCppInferenceModule(bridge),
            artifactPayloadByModelId = payloads,
            artifactSha256ByModelId = payloads.mapValues { (_, bytes) -> sha256Hex(bytes) },
            artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
                ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
                ModelCatalog.SMOLLM3_3B_Q4_K_M to "internal-release",
            ),
            artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_0_8B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4)),
                ModelCatalog.QWEN_3_5_2B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_2B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_2B_Q4)),
                ModelCatalog.SMOLLM3_3B_Q4_K_M to provenanceSignature("internal-release", ModelCatalog.SMOLLM3_3B_Q4_K_M, payloads.getValue(ModelCatalog.SMOLLM3_3B_Q4_K_M)),
            ),
            requireNativeRuntimeForStartupChecks = false,
        )

        val checks = container.runStartupChecks()

        assertEquals(emptyList(), checks)
    }

    @Test
    fun `send user message fails when policy rejects inference event`() {
        val policy = RecordingPolicyModule(deniedEvents = setOf("inference.generate"))
        val container = defaultContainer(
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
        val container = defaultContainer(
            policyModule = RecordingPolicyModule(deniedEvents = setOf("tool.execute")),
        )

        val result = container.runTool("calculator", "{\"expression\":\"1+2\"}")

        assertEquals("Tool error: Policy module rejected tool event type.", result)
    }

    @Test
    fun `analyze image fails when policy rejects image event`() {
        val inference = RecordingInferenceModule()
        val policy = RecordingPolicyModule(deniedEvents = setOf("inference.image_analyze"))
        val container = defaultContainer(
            inferenceModule = inference,
            policyModule = policy,
        )

        val error = assertFailsWith<IllegalStateException> {
            container.analyzeImage("photo.jpg", "what is shown")
        }

        assertTrue(error.message?.contains("Policy module rejected image analysis event type") == true)
        assertTrue(policy.observedEvents.contains("routing.image_model_select"))
        assertTrue(policy.observedEvents.contains("inference.image_analyze"))
        assertEquals(1, inference.unloadCalls)
    }

    @Test
    fun `analyze image runs routing and model lifecycle with diagnostics metric`() {
        val inference = RecordingInferenceModule()
        val observability = RecordingObservabilityModule()
        val policy = RecordingPolicyModule()
        val container = defaultContainer(
            inferenceModule = inference,
            observabilityModule = observability,
            policyModule = policy,
        )

        val output = container.analyzeImage(
            imagePath = "/private/storage/photo.jpg",
            prompt = "Describe this photo",
            deviceState = DeviceState(batteryPercent = 92, thermalLevel = 4, ramClassGb = 8),
        )

        assertEquals("real runtime response", output)
        assertFalse(output.contains("/private/storage/photo.jpg"))
        assertEquals(1, inference.loadCalls.size)
        assertEquals(1, inference.unloadCalls)
        assertTrue(policy.observedEvents.contains("routing.image_model_select"))
        assertTrue(policy.observedEvents.contains("inference.image_analyze"))
        assertTrue(policy.observedEvents.contains("observability.record_runtime_metrics"))
        assertTrue(observability.recordedLatencyMetrics.keys.contains("inference.image.total_ms"))
        assertEquals(4, observability.recordedThermalLevels.last())
    }

    @Test
    fun `analyze image fails when runtime image model cannot load`() {
        val inference = RecordingInferenceModule(allowLoad = false)
        val container = defaultContainer(inferenceModule = inference)

        val error = assertFailsWith<IllegalStateException> {
            container.analyzeImage("photo.jpg", "describe this image")
        }

        assertTrue(error.message?.contains("Failed to load runtime model for image analysis") == true)
        assertEquals(0, inference.unloadCalls)
    }

    @Test
    fun `send user message hard-fails when artifact verification fails`() {
        val payloads = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "wrong-artifact".encodeToByteArray(),
            ModelCatalog.QWEN_3_5_2B_Q4 to "other-artifact".encodeToByteArray(),
        )
        val container = AndroidMvpContainer(
            inferenceModule = RecordingInferenceModule(),
            artifactPayloadByModelId = payloads,
            artifactSha256ByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ModelCatalog.QWEN_3_5_2B_Q4 to "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            ),
            artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
                ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
            ),
            artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_0_8B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4)),
                ModelCatalog.QWEN_3_5_2B_Q4 to provenanceSignature("internal-release", ModelCatalog.QWEN_3_5_2B_Q4, payloads.getValue(ModelCatalog.QWEN_3_5_2B_Q4)),
            ),
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

        assertTrue(error.message?.contains("MODEL_ARTIFACT_VERIFICATION_ERROR:CHECKSUM_MISMATCH") == true)
    }

    @Test
    fun `startup checks fail when offline probe is unexpectedly allowlisted`() {
        val policy = RecordingPolicyModule(allowlistedNetworkActions = setOf("runtime.offline_probe"))
        val container = defaultContainer(
            inferenceModule = RecordingInferenceModule(),
            policyModule = policy,
        )

        val checks = container.runStartupChecks()

        assertTrue(checks.any { it.contains("offline-only mode unexpectedly allowed runtime.offline_probe") })
    }

    @Test
    fun `export diagnostics fails when policy rejects diagnostics export event`() {
        val container = defaultContainer(
            policyModule = RecordingPolicyModule(deniedEvents = setOf("observability.export")),
        )

        val error = assertFailsWith<IllegalStateException> {
            container.exportDiagnostics()
        }

        assertTrue(error.message?.contains("Policy module rejected diagnostics export event type") == true)
    }

    @Test
    fun `export diagnostics redacts sensitive payload fields`() {
        val container = defaultContainer(
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
    private val availableModels: List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
        ModelCatalog.SMOLLM3_3B_Q4_K_M,
    ),
) : InferenceModule {
    val loadCalls = mutableListOf<String>()
    val capturedPrompts = mutableListOf<String>()
    var unloadCalls: Int = 0
    var generateCalls: Int = 0

    override fun listAvailableModels(): List<String> = availableModels

    override fun loadModel(modelId: String): Boolean {
        loadCalls.add(modelId)
        return allowLoad && availableModels.contains(modelId)
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

private class BackendAwareTestBridge(
    private val backend: com.pocketagent.nativebridge.RuntimeBackend,
) : com.pocketagent.nativebridge.LlamaCppRuntimeBridge {
    override fun isReady(): Boolean = backend != com.pocketagent.nativebridge.RuntimeBackend.UNAVAILABLE

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
        ModelCatalog.SMOLLM3_3B_Q4_K_M,
    )

    override fun loadModel(modelId: String, modelPath: String?): Boolean = isReady()

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: com.pocketagent.nativebridge.CachePolicy,
        onToken: (String) -> Unit,
    ): com.pocketagent.nativebridge.GenerationResult {
        if (!isReady()) {
            return com.pocketagent.nativebridge.GenerationResult(
                finishReason = com.pocketagent.nativebridge.GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "BACKEND_UNAVAILABLE",
            )
        }
        onToken("token ")
        return com.pocketagent.nativebridge.GenerationResult(
            finishReason = com.pocketagent.nativebridge.GenerationFinishReason.COMPLETED,
            tokenCount = 1,
            firstTokenMs = 1L,
            totalMs = 1L,
            cancelled = false,
            errorCode = null,
        )
    }

    override fun unloadModel() {
        // no-op
    }

    override fun runtimeBackend(): com.pocketagent.nativebridge.RuntimeBackend = backend
}

private class RecordingPolicyModule(
    private val deniedEvents: Set<String> = emptySet(),
    private val allowlistedNetworkActions: Set<String> = emptySet(),
) : PolicyModule {
    val observedEvents: MutableList<String> = mutableListOf()

    override fun isNetworkAllowedForAction(action: String): Boolean {
        return allowlistedNetworkActions.contains(action)
    }

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

private fun defaultContainer(
    inferenceModule: InferenceModule = RecordingInferenceModule(),
    policyModule: PolicyModule = RecordingPolicyModule(),
    observabilityModule: ObservabilityModule = InMemoryObservabilityModule(),
): AndroidMvpContainer {
    val payloads = testPayloads()
    val issuerByModel = mapOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
        ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
    )
    val signatureByModel = mapOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4 to provenanceSignature(
            "internal-release",
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            payloads.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4),
        ),
        ModelCatalog.QWEN_3_5_2B_Q4 to provenanceSignature(
            "internal-release",
            ModelCatalog.QWEN_3_5_2B_Q4,
            payloads.getValue(ModelCatalog.QWEN_3_5_2B_Q4),
        ),
    )
    return AndroidMvpContainer(
        inferenceModule = inferenceModule,
        policyModule = policyModule,
        observabilityModule = observabilityModule,
        artifactPayloadByModelId = payloads,
        artifactSha256ByModelId = payloads.mapValues { (_, bytes) -> sha256Hex(bytes) },
        artifactProvenanceIssuerByModelId = issuerByModel,
        artifactProvenanceSignatureByModelId = signatureByModel,
    )
}

private fun testPayloads(): Map<String, ByteArray> {
    return mapOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4 to "artifact-qwen-0.8b".encodeToByteArray(),
        ModelCatalog.QWEN_3_5_2B_Q4 to "artifact-qwen-2b".encodeToByteArray(),
        ModelCatalog.SMOLLM3_3B_Q4_K_M to "artifact-smollm3-3b".encodeToByteArray(),
    )
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val builder = StringBuilder()
    digest.forEach { b -> builder.append("%02x".format(b)) }
    return builder.toString()
}

private fun provenanceSignature(issuer: String, modelId: String, payload: ByteArray): String {
    val payloadSha = sha256Hex(payload)
    return sha256Hex("$issuer|$modelId|$payloadSha|v1".encodeToByteArray())
}

private class RecordingObservabilityModule : ObservabilityModule {
    val recordedLatencyMetrics: MutableMap<String, Double> = linkedMapOf()
    val recordedThermalLevels: MutableList<Int> = mutableListOf()

    override fun recordLatencyMetric(name: String, valueMs: Double) {
        recordedLatencyMetrics[name] = valueMs
    }

    override fun recordThermalSnapshot(level: Int) {
        recordedThermalLevels.add(level)
    }

    override fun exportLocalDiagnostics(): String {
        return recordedLatencyMetrics.entries.joinToString(",") { "${it.key}=${it.value}" }
    }
}
