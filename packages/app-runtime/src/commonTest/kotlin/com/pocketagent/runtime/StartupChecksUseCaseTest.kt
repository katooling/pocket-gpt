package com.pocketagent.runtime

import com.pocketagent.core.PolicyModule
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.inference.RoutingModule
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupChecksUseCaseTest {
    @Test
    fun `manifest invalid short circuits startup checks`() {
        val inference = StartupInferenceModule(availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val runtimeConfig = startupRuntimeConfig(validSha = false)
        val useCase = buildUseCase(
            runtimeConfig = runtimeConfig,
            inferenceModule = inference,
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
        )

        val checks = useCase.run()

        assertTrue(checks.firstOrNull()?.contains("Artifact manifest invalid") == true)
        assertEquals(0, inference.loadCalls)
    }

    @Test
    fun `missing runtime models reports startup blocking failure`() {
        val useCase = buildUseCase(
            runtimeConfig = startupRuntimeConfig(validSha = true),
            inferenceModule = StartupInferenceModule(availableModels = emptyList()),
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
        )

        val checks = useCase.run()

        assertTrue(checks.any { it.contains("Missing runtime model(s)") })
    }

    @Test
    fun `optional model unavailable is emitted as degraded warning`() {
        val inference = StartupInferenceModule(
            availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4),
            loadModelResult = true,
        )
        val useCase = buildUseCase(
            runtimeConfig = startupRuntimeConfig(validSha = true),
            inferenceModule = inference,
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
        )

        val checks = useCase.run()

        assertTrue(checks.any { it.contains("Optional runtime model unavailable") })
        assertTrue(checks.none { it.contains("Missing runtime model(s)") })
        assertTrue(inference.loadCalls >= 1)
    }

    @Test
    fun `backend gate blocks startup when native runtime is required`() {
        val useCase = buildUseCase(
            runtimeConfig = startupRuntimeConfig(validSha = true, requireNativeRuntime = true),
            inferenceModule = StartupInferenceModule(
                availableModels = listOf(
                    ModelCatalog.QWEN_3_5_0_8B_Q4,
                    ModelCatalog.QWEN_3_5_2B_Q4,
                ),
            ),
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { "ADB_FALLBACK" },
        )

        val checks = useCase.run()

        assertTrue(checks.any { it.contains("Native JNI runtime is required") })
    }

    @Test
    fun `missing artifact config returns deterministic missing-config check`() {
        val runtimeConfig = startupRuntimeConfig(validSha = true).copy(
            artifactSha256ByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "",
                ModelCatalog.QWEN_3_5_2B_Q4 to "",
            ),
            artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "",
                ModelCatalog.QWEN_3_5_2B_Q4 to "",
            ),
        )
        val useCase = buildUseCase(
            runtimeConfig = runtimeConfig,
            inferenceModule = StartupInferenceModule(availableModels = emptyList()),
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
        )

        val checks = useCase.run()

        assertTrue(checks.any { it.contains("MODEL_ARTIFACT_CONFIG_MISSING:model=${ModelCatalog.QWEN_3_5_0_8B_Q4};field=sha256") })
    }

    @Test
    fun `partial startup config is optional when minimum ready count is satisfied`() {
        val payload0 = "payload-0".encodeToByteArray()
        val runtimeConfig = startupRuntimeConfig(validSha = true).copy(
            artifactPayloadByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to payload0,
            ),
            artifactFilePathByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "",
                ModelCatalog.QWEN_3_5_2B_Q4 to "",
            ),
            artifactSha256ByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to startupSha256(payload0),
                ModelCatalog.QWEN_3_5_2B_Q4 to "",
            ),
            artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
                ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
            ),
            artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to "sig-0",
                ModelCatalog.QWEN_3_5_2B_Q4 to "",
            ),
        )
        val useCase = buildUseCase(
            runtimeConfig = runtimeConfig,
            inferenceModule = StartupInferenceModule(
                availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4),
                loadModelResult = true,
            ),
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
        )

        val checks = useCase.run()

        assertTrue(checks.any { it.contains("Optional runtime model unavailable: ${ModelCatalog.QWEN_3_5_2B_Q4}") })
        assertTrue(checks.none { it.startsWith("MODEL_ARTIFACT_CONFIG_MISSING:") })
        assertTrue(checks.none { it.contains("Missing runtime model(s)") })
    }

    @Test
    fun `dev fast startup profile accepts fast tier artifacts without qwen config`() {
        val payload = "payload-fast".encodeToByteArray()
        val inference = StartupInferenceModule(
            availableModels = listOf(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M),
            loadModelResult = true,
        )
        val runtimeConfig = startupRuntimeConfig(validSha = true).copy(
            artifactPayloadByModelId = mapOf(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M to payload),
            artifactFilePathByModelId = mapOf(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M to ""),
            artifactSha256ByModelId = mapOf(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M to startupSha256(payload)),
            artifactProvenanceIssuerByModelId = mapOf(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M to "internal-release"),
            artifactProvenanceSignatureByModelId = mapOf(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M to "sig-fast"),
            modelRuntimeProfile = ModelRuntimeProfile.DEV_FAST,
        )
        val useCase = buildUseCase(
            runtimeConfig = runtimeConfig,
            inferenceModule = inference,
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
        )

        val checks = useCase.run()

        assertTrue(checks.none { it.startsWith("MODEL_ARTIFACT_CONFIG_MISSING:") })
        assertTrue(checks.none { it.contains(ModelCatalog.QWEN_3_5_0_8B_Q4) || it.contains(ModelCatalog.QWEN_3_5_2B_Q4) })
        assertEquals(0, inference.loadCalls)
    }

    @Test
    fun `startup checks fail fast when model registry has no startup candidates`() {
        val useCase = buildUseCase(
            runtimeConfig = startupRuntimeConfig(validSha = true),
            inferenceModule = StartupInferenceModule(availableModels = emptyList()),
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
            modelRegistry = ModelRegistry(
                metadataByModelId = emptyMap(),
                startupMinimumReadyCount = 1,
            ),
        )

        val checks = useCase.run()

        assertTrue(checks.any { it.contains("Startup model policy invalid") })
    }

    @Test
    fun `startup checks honor model registry minimum ready count`() {
        val useCase = buildUseCase(
            runtimeConfig = startupRuntimeConfig(validSha = true),
            inferenceModule = StartupInferenceModule(
                availableModels = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4),
            ),
            policyModule = StartupPolicyModule(
                allowedEvents = setOf("inference.startup_check"),
                allowedNetworkActions = emptySet(),
            ),
            runtimeBackendProvider = { null },
            modelRegistry = startupRegistryWithMinimumReady(minimumReadyCount = 2),
        )

        val checks = useCase.run()

        assertTrue(checks.any { it.contains("Missing runtime model(s): ${ModelCatalog.QWEN_3_5_2B_Q4}") })
    }
}

private fun buildUseCase(
    runtimeConfig: RuntimeConfig,
    inferenceModule: StartupInferenceModule,
    policyModule: StartupPolicyModule,
    runtimeBackendProvider: () -> String?,
    modelRegistry: ModelRegistry = ModelRegistry.default(),
): StartupChecksUseCase {
    val routingModule = StartupRoutingModule()
    val modelLifecycle = ModelLifecycleCoordinator(
        inferenceModule = inferenceModule,
        routingModule = routingModule,
        runtimeConfig = runtimeConfig,
    )
    val templateRegistry = ModelTemplateRegistry(
        profileByModelId = ModelTemplateRegistry.defaultProfiles(modelRegistry = modelRegistry),
    )
    return StartupChecksUseCase(
        artifactVerifier = ArtifactVerifier(runtimeConfig, modelRegistry = modelRegistry),
        interactionPlanner = InteractionPlanner(templateRegistry),
        inferenceModule = inferenceModule,
        policyModule = policyModule,
        runtimeConfig = runtimeConfig,
        networkPolicyClient = PolicyAwareNetworkClient(policyModule),
        modelLifecycleCoordinator = modelLifecycle,
        runtimeBackendProvider = runtimeBackendProvider,
        modelRegistry = modelRegistry,
    )
}

private class StartupInferenceModule(
    private val availableModels: List<String>,
    private val loadModelResult: Boolean = true,
) : InferenceModule {
    var loadCalls: Int = 0

    override fun listAvailableModels(): List<String> = availableModels

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
        return loadModelResult
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        onToken("ok")
    }

    override fun unloadModel() = Unit
}

private class StartupPolicyModule(
    private val allowedEvents: Set<String>,
    private val allowedNetworkActions: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean = allowedNetworkActions.contains(action)

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = allowedEvents.contains(eventType)
}

private class StartupRoutingModule : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String = ModelCatalog.QWEN_3_5_0_8B_Q4

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int = 512
}

private fun startupRuntimeConfig(
    validSha: Boolean,
    requireNativeRuntime: Boolean = false,
): RuntimeConfig {
    val payload0 = "payload-0".encodeToByteArray()
    val payload2 = "payload-2".encodeToByteArray()
    val sha0 = if (validSha) startupSha256(payload0) else "invalid"
    val sha2 = if (validSha) startupSha256(payload2) else "invalid"
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
            ModelCatalog.QWEN_3_5_0_8B_Q4 to sha0,
            ModelCatalog.QWEN_3_5_2B_Q4 to sha2,
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
        requireNativeRuntimeForStartupChecks = requireNativeRuntime,
        prefixCacheEnabled = true,
        prefixCacheStrict = false,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
        streamContractV2Enabled = true,
    )
}

private fun startupSha256(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun startupRegistryWithMinimumReady(minimumReadyCount: Int): ModelRegistry {
    return ModelRegistry(
        metadataByModelId = listOf(
            RuntimeModelMetadata(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                templateProfile = ModelTemplateProfile.CHATML,
                tier = RuntimeModelTier.BASELINE,
                startupRequirement = StartupRequirement.OPTIONAL,
            ),
            RuntimeModelMetadata(
                modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                templateProfile = ModelTemplateProfile.CHATML,
                tier = RuntimeModelTier.BASELINE,
                startupRequirement = StartupRequirement.OPTIONAL,
            ),
        ).associateBy { metadata -> metadata.modelId },
        startupMinimumReadyCount = minimumReadyCount,
    )
}
