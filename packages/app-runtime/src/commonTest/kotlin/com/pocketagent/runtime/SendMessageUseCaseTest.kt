package com.pocketagent.runtime

import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.InMemoryMemoryModule
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SendMessageUseCaseTest {
    @Test
    fun `throws when routing policy event is denied`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = SendPolicyModule(
                allowedEvents = setOf(
                    "memory.write_user_turn",
                    "inference.generate",
                    "observability.record_runtime_metrics",
                ),
            ),
            inferenceModule = SendRecordingInferenceModule(),
        )

        val error = assertFailsWith<IllegalStateException> {
            fixture.useCase.execute(fixture.request())
        }

        assertTrue(error.message?.contains("Policy module rejected routing event type.") == true)
    }

    @Test
    fun `interactive chat bypasses response cache and still requires model load`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(loadModelResult = false),
        )

        val error = assertFailsWith<IllegalStateException> {
            fixture.useCase.execute(fixture.request())
        }

        assertTrue(error.message?.contains("Failed to load runtime model") == true)
        assertEquals(1, fixture.inferenceModule.loadCalls)
    }

    @Test
    fun `timeout invokes request scoped cancellation when stream contract v2 is enabled`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("late "),
                busyWaitMsBeforeTokens = 30L,
            ),
        )

        assertFailsWith<RuntimeGenerationTimeoutException> {
            fixture.useCase.execute(
                fixture.request(
                    requestTimeoutMs = 5L,
                ),
            )
        }

        assertTrue(fixture.cancelByRequestCalls > 0)
        assertEquals(0, fixture.cancelBySessionCalls)
    }

    @Test
    fun `timeout invokes session cancellation when stream contract v2 is disabled`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = false),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("late "),
                busyWaitMsBeforeTokens = 30L,
            ),
        )

        assertFailsWith<RuntimeGenerationTimeoutException> {
            fixture.useCase.execute(
                fixture.request(
                    requestTimeoutMs = 5L,
                ),
            )
        }

        assertTrue(fixture.cancelBySessionCalls > 0)
        assertEquals(0, fixture.cancelByRequestCalls)
    }

    @Test
    fun `generation does not timeout after first token is emitted`() {
        val fixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(
                generatedTokens = listOf("hello ", "world "),
                busyWaitMsAfterFirstToken = 30L,
            ),
        )

        val response = fixture.useCase.execute(
            fixture.request(
                requestTimeoutMs = 5L,
            ),
        )

        assertEquals("hello world", response.text)
        assertEquals("completed", response.finishReason)
        assertEquals(0, fixture.cancelByRequestCalls)
        assertEquals(0, fixture.cancelBySessionCalls)
    }

    @Test
    fun `residency policy controls unload now vs idle schedule`() {
        val unloadNowFixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(generatedTokens = listOf("ok ")),
        )
        unloadNowFixture.useCase.execute(
            unloadNowFixture.request(
                keepModelLoaded = false,
                residencyPolicy = ModelResidencyPolicy(keepLoadedWhileAppForeground = true, idleUnloadTtlMs = 4_000L),
            ),
        )
        assertEquals(1, unloadNowFixture.inferenceModule.unloadCalls)
        assertTrue(unloadNowFixture.runtimeResidencyManager.listResident().isEmpty())

        val scheduleFixture = createFixture(
            runtimeConfig = sendRuntimeConfig(streamContractV2Enabled = true),
            policyModule = permissivePolicy(),
            inferenceModule = SendRecordingInferenceModule(generatedTokens = listOf("ok ")),
        )
        scheduleFixture.useCase.execute(
            scheduleFixture.request(
                keepModelLoaded = true,
                residencyPolicy = ModelResidencyPolicy(keepLoadedWhileAppForeground = true, idleUnloadTtlMs = 4_000L),
            ),
        )
        assertEquals(0, scheduleFixture.inferenceModule.unloadCalls)
        assertEquals(1, scheduleFixture.runtimeResidencyManager.listResident().size)
    }
}

private class SendMessageFixture(
    val inferenceModule: SendRecordingInferenceModule,
    val runtimeResidencyManager: RuntimeResidencyManager,
) {
    lateinit var useCase: SendMessageUseCase
    var cancelByRequestCalls: Int = 0
    var cancelBySessionCalls: Int = 0

    fun request(
        requestTimeoutMs: Long = 60_000L,
        keepModelLoaded: Boolean = true,
        residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(
            keepLoadedWhileAppForeground = true,
            idleUnloadTtlMs = 3_000L,
        ),
    ): SendMessageUseCase.Request {
        return SendMessageUseCase.Request(
            sessionId = SessionId("session-1"),
            userText = "hello world",
            taskType = "short_text",
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            maxTokens = 64,
            keepModelLoaded = keepModelLoaded,
            onToken = {},
            requestTimeoutMs = requestTimeoutMs,
            requestId = "req-1",
            performanceConfig = PerformanceRuntimeConfig.default(),
            residencyPolicy = residencyPolicy,
            routingMode = RoutingMode.AUTO,
        )
    }
}

private fun createFixture(
    runtimeConfig: RuntimeConfig,
    policyModule: SendPolicyModule,
    inferenceModule: SendRecordingInferenceModule,
): SendMessageFixture {
    val routingModule = SendStaticRoutingModule()
    val modelLifecycle = ModelLifecycleCoordinator(
        inferenceModule = inferenceModule,
        routingModule = routingModule,
        runtimeConfig = runtimeConfig,
    )
    val observability = NoopObservabilityModule()
    val runtimeResidencyManager = RuntimeResidencyManager(inferenceModule = inferenceModule)
    val fixture = SendMessageFixture(
        inferenceModule = inferenceModule,
        runtimeResidencyManager = runtimeResidencyManager,
    )
    fixture.useCase = SendMessageUseCase(
        conversationModule = InMemoryConversationModule(),
        routingModule = routingModule,
        policyModule = policyModule,
        observabilityModule = observability,
        memoryModule = InMemoryMemoryModule(),
        inferenceModule = inferenceModule,
        runtimeConfig = runtimeConfig,
        artifactVerifier = ArtifactVerifier(runtimeConfig),
        interactionPlanner = InteractionPlanner(ModelTemplateRegistry()),
        inferenceExecutor = InferenceExecutor(
            inferenceModule = inferenceModule,
            runtimeConfig = runtimeConfig,
        ),
        modelLifecycleCoordinator = modelLifecycle,
        runtimePlanResolver = RuntimePlanResolver(),
        runtimeResidencyManager = runtimeResidencyManager,
        cancelByRequest = { _ ->
            fixture.cancelByRequestCalls += 1
            true
        },
        cancelBySession = { _ ->
            fixture.cancelBySessionCalls += 1
            true
        },
    )
    return fixture
}

private class SendRecordingInferenceModule(
    private val loadModelResult: Boolean = true,
    private val generatedTokens: List<String> = listOf("hello ", "world "),
    private val busyWaitMsBeforeTokens: Long = 0L,
    private val busyWaitMsAfterFirstToken: Long = 0L,
) : InferenceModule {
    var loadCalls: Int = 0
    var unloadCalls: Int = 0

    override fun listAvailableModels(): List<String> {
        return listOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN_3_5_2B_Q4)
    }

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
        return loadModelResult
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        busyWait(busyWaitMsBeforeTokens)
        generatedTokens.forEachIndexed { index, token ->
            onToken(token)
            if (index == 0) {
                busyWait(busyWaitMsAfterFirstToken)
            }
        }
    }

    override fun unloadModel() {
        unloadCalls += 1
    }

    private fun busyWait(waitMs: Long) {
        if (waitMs <= 0L) {
            return
        }
        val start = System.currentTimeMillis()
        while ((System.currentTimeMillis() - start) < waitMs) {
            Thread.onSpinWait()
        }
    }
}

private class SendStaticRoutingModule : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String = ModelCatalog.QWEN_3_5_0_8B_Q4

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int = 512
}

private class SendPolicyModule(
    private val allowedEvents: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean = false

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = allowedEvents.contains(eventType)
}

private class NoopObservabilityModule : ObservabilityModule {
    override fun recordLatencyMetric(name: String, valueMs: Double) = Unit

    override fun recordThermalSnapshot(level: Int) = Unit

    override fun exportLocalDiagnostics(): String = "diag"
}

private fun permissivePolicy(): SendPolicyModule {
    return SendPolicyModule(
        allowedEvents = setOf(
            "routing.model_select",
            "memory.write_user_turn",
            "inference.generate",
            "observability.record_runtime_metrics",
        ),
    )
}

private fun sendRuntimeConfig(
    streamContractV2Enabled: Boolean,
): RuntimeConfig {
    val payload0 = "payload-0".encodeToByteArray()
    val payload2 = "payload-2".encodeToByteArray()
    return RuntimeConfig(
        artifactPayloadByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to payload0,
            ModelCatalog.QWEN_3_5_2B_Q4 to payload2,
        ),
        artifactFilePathByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "",
            ModelCatalog.QWEN_3_5_2B_Q4 to "",
        ),
        artifactSha256ByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to sendSha256(payload0),
            ModelCatalog.QWEN_3_5_2B_Q4 to sendSha256(payload2),
        ),
        artifactProvenanceIssuerByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
            ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
        ),
        artifactProvenanceSignatureByModelId = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "sig-0",
            ModelCatalog.QWEN_3_5_2B_Q4 to "sig-2",
        ),
        runtimeCompatibilityTag = "android-arm64-v8a",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = true,
        prefixCacheStrict = false,
        responseCacheTtlSec = 120L,
        responseCacheMaxEntries = 64,
        streamContractV2Enabled = streamContractV2Enabled,
    )
}

private fun sendSha256(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
