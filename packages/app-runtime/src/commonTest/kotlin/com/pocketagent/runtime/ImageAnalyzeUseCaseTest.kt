package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ImageInputModule
import com.pocketagent.inference.ImageInputResult
import com.pocketagent.inference.ImageRequest
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageAnalyzeUseCaseTest {
    @Test
    fun `returns policy denied failure before model selection when routing event is blocked`() {
        val inference = ImageRecordingInferenceModule()
        val useCase = buildUseCase(
            inference = inference,
            policy = ImageEventPolicyModule(allowedEvents = setOf("observability.record_runtime_metrics")),
            imageInput = StaticImageInputModule(ImageInputResult.Success("ok")),
        )

        val result = useCase.execute(
            imagePath = "/tmp/img.jpg",
            prompt = "describe",
            deviceState = DEVICE_STATE,
        )

        assertTrue(result is ImageAnalysisResult.Failure)
        assertTrue((result as ImageAnalysisResult.Failure).failure is ImageFailure.PolicyDenied)
        assertEquals(0, inference.loadCalls)
        assertEquals(0, inference.unloadCalls)
    }

    @Test
    fun `maps image validation failure to typed validation contract`() {
        val useCase = buildUseCase(
            inference = ImageRecordingInferenceModule(),
            policy = ImageEventPolicyModule(
                allowedEvents = setOf(
                    "routing.image_model_select",
                    "inference.image_analyze",
                    "observability.record_runtime_metrics",
                ),
            ),
            imageInput = StaticImageInputModule(
                ImageInputResult.ValidationFailure(
                    code = "UNSUPPORTED_EXTENSION",
                    detail = "extension 'tiff' is not supported",
                ),
            ),
        )

        val result = useCase.execute("/tmp/img.tiff", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Failure)
        val failure = (result as ImageAnalysisResult.Failure).failure
        assertTrue(failure is ImageFailure.Validation)
        assertEquals("unsupported_extension", failure.code)
        assertEquals("extension 'tiff' is not supported", failure.technicalDetail)
    }

    @Test
    fun `success path loads and unloads model and records observability`() {
        val inference = ImageRecordingInferenceModule()
        val observability = RecordingObservabilityModule()
        val useCase = buildUseCase(
            inference = inference,
            policy = ImageEventPolicyModule(
                allowedEvents = setOf(
                    "routing.image_model_select",
                    "inference.image_analyze",
                    "observability.record_runtime_metrics",
                ),
            ),
            imageInput = StaticImageInputModule(ImageInputResult.Success("image summary")),
            observability = observability,
        )

        val result = useCase.execute("/tmp/img.jpg", "describe", DEVICE_STATE)

        assertTrue(result is ImageAnalysisResult.Success)
        assertEquals("image summary", (result as ImageAnalysisResult.Success).content)
        assertEquals(1, inference.loadCalls)
        assertEquals(1, inference.unloadCalls)
        assertTrue(observability.metrics.any { it.first == "inference.image.total_ms" })
    }
}

private val DEVICE_STATE = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8)

    private fun buildUseCase(
    inference: ImageRecordingInferenceModule,
    policy: ImageEventPolicyModule,
    imageInput: ImageInputModule,
    observability: RecordingObservabilityModule = RecordingObservabilityModule(),
): ImageAnalyzeUseCase {
    val runtimeConfig = imageRuntimeConfig()
    val modelLifecycleCoordinator = ModelLifecycleCoordinator(
        inferenceModule = inference,
        routingModule = ImageStaticRoutingModule(),
        runtimeConfig = runtimeConfig,
    )
    return ImageAnalyzeUseCase(
        policyModule = policy,
        inferenceModule = inference,
        artifactVerifier = ArtifactVerifier(runtimeConfig),
        imageInputModule = imageInput,
        observabilityModule = observability,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        routingModeProvider = { RoutingMode.AUTO },
    )
}

private class StaticImageInputModule(
    private val result: ImageInputResult,
) : ImageInputModule {
    override fun analyzeImage(request: ImageRequest): String = "legacy"

    override fun analyzeImageResult(request: ImageRequest): ImageInputResult = result
}

private class ImageRecordingInferenceModule : InferenceModule {
    var loadCalls: Int = 0
    var unloadCalls: Int = 0

    override fun listAvailableModels(): List<String> {
        return listOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN_3_5_2B_Q4)
    }

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
        return true
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        onToken("unused")
    }

    override fun unloadModel() {
        unloadCalls += 1
    }
}

private class RecordingObservabilityModule : ObservabilityModule {
    val metrics = mutableListOf<Pair<String, Double>>()
    var thermalSnapshots = mutableListOf<Int>()

    override fun recordLatencyMetric(name: String, valueMs: Double) {
        metrics += name to valueMs
    }

    override fun recordThermalSnapshot(level: Int) {
        thermalSnapshots += level
    }

    override fun exportLocalDiagnostics(): String = "diag"
}

private class ImageEventPolicyModule(
    private val allowedEvents: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean = false

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = allowedEvents.contains(eventType)
}

private class ImageStaticRoutingModule : RoutingModule {
    override fun selectModel(taskType: String, deviceState: DeviceState): String = ModelCatalog.QWEN_3_5_0_8B_Q4

    override fun selectContextBudget(taskType: String, deviceState: DeviceState): Int = 512
}

private fun imageRuntimeConfig(): RuntimeConfig {
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
            ModelCatalog.QWEN_3_5_0_8B_Q4 to imageSha256(payload0),
            ModelCatalog.QWEN_3_5_2B_Q4 to imageSha256(payload2),
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
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
        streamContractV2Enabled = true,
    )
}

private fun imageSha256(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
