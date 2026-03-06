package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import java.util.concurrent.ConcurrentHashMap

class InferenceExecutor(
    private val inferenceModule: InferenceModule,
    private val runtimeConfig: RuntimeConfig,
) {
    private val activeByRequestId = ConcurrentHashMap<String, ActiveGenerationState>()
    private val activeBySessionId = ConcurrentHashMap<String, ActiveGenerationState>()

    fun execute(
        sessionId: String,
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        stopSequences: List<String>,
        onToken: (String) -> Unit,
    ): InferenceExecutionResult {
        val state = ActiveGenerationState(requestId = requestId, sessionId = sessionId)
        activeByRequestId[requestId] = state
        activeBySessionId[sessionId] = state
        val nativeInference = inferenceModule as? LlamaCppInferenceModule
        val streamedText = StringBuilder()
        var stoppedBySequence = false
        var finishReason = "completed"
        var bridgeErrorCode: String? = null
        try {
            if (nativeInference != null) {
                val result = nativeInference.generateStreamWithCache(
                    requestId = requestId,
                    request = request,
                    cacheKey = cacheKey,
                    cachePolicy = cachePolicy,
                    onToken = { token ->
                        streamedText.append(token)
                        val projected = streamedText.toString()
                        if (stopSequences.any { projected.endsWith(it) }) {
                            stoppedBySequence = true
                            nativeInference.cancelGeneration(requestId)
                            return@generateStreamWithCache
                        }
                        onToken(token)
                    },
                )
                finishReason = result.finishReason.name.lowercase()
                bridgeErrorCode = result.errorCode
                if (!result.success) {
                    if (result.cancelled && stoppedBySequence) {
                        finishReason = "stop_sequence"
                    } else if (result.cancelled) {
                        throw RuntimeGenerationCancelledException(requestId = requestId)
                    } else {
                        throw RuntimeGenerationFailureException(
                            message = "llama.cpp runtime generation failed: finish=${result.finishReason} code=${result.errorCode.orEmpty()}",
                            errorCode = result.errorCode,
                        )
                    }
                }
            } else {
                var ignoreFurtherTokens = false
                inferenceModule.generateStream(request) { token ->
                    if (ignoreFurtherTokens) {
                        return@generateStream
                    }
                    streamedText.append(token)
                    val projected = streamedText.toString()
                    if (stopSequences.any { projected.endsWith(it) }) {
                        stoppedBySequence = true
                        ignoreFurtherTokens = true
                        finishReason = "stop_sequence"
                        return@generateStream
                    }
                    onToken(token)
                }
            }
            return InferenceExecutionResult(
                text = streamedText.toString().trimStopSequences(stopSequences),
                finishReason = if (bridgeErrorCode.isNullOrBlank()) finishReason else "$finishReason:${bridgeErrorCode.lowercase()}",
                bridgeErrorCode = bridgeErrorCode,
            )
        } finally {
            activeByRequestId.remove(requestId)
            activeBySessionId.remove(sessionId)
        }
    }

    fun cancelByRequest(requestId: String): Boolean {
        if (!runtimeConfig.streamContractV2Enabled) {
            return false
        }
        val active = activeByRequestId[requestId] ?: return false
        return (inferenceModule as? LlamaCppInferenceModule)?.cancelGeneration(active.requestId) ?: false
    }

    fun cancelBySession(sessionId: String): Boolean {
        val active = activeBySessionId[sessionId] ?: return false
        return (inferenceModule as? LlamaCppInferenceModule)?.cancelGeneration(active.requestId) ?: false
    }
}

data class InferenceExecutionResult(
    val text: String,
    val finishReason: String,
    val bridgeErrorCode: String?,
)

private data class ActiveGenerationState(
    val requestId: String,
    val sessionId: String,
)

private fun String.trimStopSequences(stopSequences: List<String>): String {
    var value = this
    stopSequences.forEach { stop ->
        if (stop.isNotEmpty() && value.endsWith(stop)) {
            value = value.removeSuffix(stop)
        }
    }
    return value
}
