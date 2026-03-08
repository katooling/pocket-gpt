package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ImageInputResult
import com.pocketagent.inference.ImageInputModule
import com.pocketagent.inference.InferenceModule

internal class ImageAnalyzeUseCase(
    private val policyModule: PolicyModule,
    private val inferenceModule: InferenceModule,
    private val artifactVerifier: ArtifactVerifier,
    private val imageInputModule: ImageInputModule,
    private val observabilityModule: ObservabilityModule,
    private val modelLifecycleCoordinator: ModelLifecycleCoordinator,
    private val routingModeProvider: () -> RoutingMode,
) {
    fun execute(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState,
    ): ImageAnalysisResult {
        if (!policyModule.enforceDataBoundary("routing.image_model_select")) {
            return ImageAnalysisResult.Failure(
                failure = ImageFailure.PolicyDenied(
                    technicalDetail = "Policy module rejected image routing event type.",
                ),
            )
        }
        val startedMs = System.currentTimeMillis()
        var modelLoaded = false
        val output = runCatching {
            val modelId = modelLifecycleCoordinator.selectRunnableModelId(
                routingMode = routingModeProvider(),
                taskType = "image",
                deviceState = deviceState,
            )
            check(artifactVerifier.manager().setActiveModel(modelId)) {
                "Model artifact not registered for image runtime model: $modelId"
            }
            artifactVerifier.verifyArtifactOrThrow(modelId)
            modelLoaded = inferenceModule.loadModel(modelId)
            check(modelLoaded) {
                "Failed to load runtime model for image analysis: $modelId"
            }
            if (!policyModule.enforceDataBoundary("inference.image_analyze")) {
                return@runCatching ImageAnalysisResult.Failure(
                    failure = ImageFailure.PolicyDenied(
                        technicalDetail = "Policy module rejected image analysis event type.",
                    ),
                )
            }

            when (
                val imageResult = imageInputModule.analyzeImageResult(
                    com.pocketagent.inference.ImageRequest(
                        imagePath = imagePath,
                        prompt = prompt,
                        maxTokens = 128,
                    ),
                )
            ) {
                is ImageInputResult.Success -> ImageAnalysisResult.Success(imageResult.content)
                is ImageInputResult.ValidationFailure -> ImageAnalysisResult.Failure(
                    failure = ImageFailure.Validation(
                        code = imageResult.code.lowercase(),
                        userMessage = "That image could not be processed.",
                        technicalDetail = imageResult.detail,
                    ),
                )
                is ImageInputResult.RuntimeFailure -> ImageAnalysisResult.Failure(
                    failure = ImageFailure.Runtime(
                        code = imageResult.code.lowercase(),
                        userMessage = "Image analysis failed.",
                        technicalDetail = imageResult.detail,
                    ),
                )
            }
        }.getOrElse { error ->
            ImageAnalysisResult.Failure(
                failure = ImageFailure.Runtime(
                    code = "image_runtime_error",
                    userMessage = "Image analysis failed.",
                    technicalDetail = error.message ?: "Unknown image runtime error",
                ),
            )
        }
        if (modelLoaded) {
            inferenceModule.unloadModel()
        }

        val totalLatency = System.currentTimeMillis() - startedMs
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.image.total_ms", totalLatency.toDouble())
        observabilityModule.recordThermalSnapshot(deviceState.thermalLevel)
        return output
    }

    private fun requirePolicyEvent(eventType: String, failureMessage: String) {
        check(policyModule.enforceDataBoundary(eventType)) { failureMessage }
    }
}
