package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import java.security.MessageDigest

internal class SendMessageUseCase(
    private val conversationModule: ConversationModule,
    private val routingModule: RoutingModule,
    private val policyModule: PolicyModule,
    private val observabilityModule: ObservabilityModule,
    private val memoryModule: MemoryModule,
    private val inferenceModule: InferenceModule,
    private val runtimeConfig: RuntimeConfig,
    private val artifactVerifier: ArtifactVerifier,
    private val interactionPlanner: InteractionPlanner,
    private val inferenceExecutor: InferenceExecutor,
    private val responseCache: RuntimeResponseCache,
    private val modelLifecycleCoordinator: ModelLifecycleCoordinator,
    private val cancelIdleUnload: () -> Unit,
    private val unloadNow: () -> Unit,
    private val scheduleIdleUnload: (Long) -> Unit,
    private val cancelByRequest: (String) -> Boolean,
    private val cancelBySession: (SessionId) -> Boolean,
) {
    data class Request(
        val sessionId: SessionId,
        val userText: String,
        val messages: List<InteractionMessage> = emptyList(),
        val taskType: String,
        val deviceState: DeviceState,
        val maxTokens: Int,
        val keepModelLoaded: Boolean,
        val onToken: (String) -> Unit,
        val requestTimeoutMs: Long,
        val requestId: String,
        val previousResponseId: String? = null,
        val performanceConfig: PerformanceRuntimeConfig,
        val residencyPolicy: ModelResidencyPolicy,
        val routingMode: RoutingMode,
    )

    fun execute(request: Request): ChatResponse {
        val latestUserText = request.messages.latestUserMessageText().ifBlank { request.userText }
        cancelIdleUnload()
        requirePolicyEvent(
            eventType = "routing.model_select",
            failureMessage = "Policy module rejected routing event type.",
        )
        val modelId = modelLifecycleCoordinator.selectRunnableModelId(
            routingMode = request.routingMode,
            taskType = request.taskType,
            deviceState = request.deviceState,
        )
        check(artifactVerifier.manager().setActiveModel(modelId)) {
            "Model artifact not registered for runtime model: $modelId"
        }
        artifactVerifier.verifyArtifactOrThrow(modelId)
        interactionPlanner.ensureTemplateAvailable(modelId)?.let { message ->
            throw RuntimeTemplateUnavailableException(message)
        }

        conversationModule.appendUserTurn(request.sessionId, latestUserText)
        requirePolicyEvent(
            eventType = "memory.write_user_turn",
            failureMessage = "Policy module rejected memory write event type.",
        )
        memoryModule.saveMemoryChunk(
            MemoryChunk(
                id = "mem-${System.currentTimeMillis()}",
                content = latestUserText,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val contextBudget = routingModule.selectContextBudget(request.taskType, request.deviceState)
        val promptCharBudget = when (request.taskType) {
            "long_text" -> MAX_PROMPT_CHARS_LONG
            else -> MAX_PROMPT_CHARS_SHORT
        }
        val memorySnippets = memoryModule.retrieveRelevantMemory(latestUserText, 3).map { it.content }
        val transcriptMessages = if (request.messages.isNotEmpty()) {
            request.messages
        } else {
            conversationModule.listTurns(request.sessionId).map { turn -> turn.toInteractionMessage() }
        }
        val renderedPrompt = interactionPlanner.buildRenderedPrompt(
            modelId = modelId,
            messages = transcriptMessages,
            memorySnippets = memorySnippets,
            taskType = request.taskType,
            deviceState = request.deviceState,
            promptCharBudget = minOf(contextBudget * 4, promptCharBudget),
        )
        val prompt = renderedPrompt.prompt
        val prefixCacheKey = buildPrefixCacheKey(
            modelId = modelId,
            taskType = request.taskType,
            prompt = prompt,
            maxTokens = request.maxTokens,
        )
        val responseCacheKey = buildResponseCacheKey(
            modelId = modelId,
            taskType = request.taskType,
            prompt = prompt,
            maxTokens = request.maxTokens,
        )
        requirePolicyEvent(
            eventType = "inference.generate",
            failureMessage = "Policy module rejected inference event type.",
        )
        val startedMs = System.currentTimeMillis()

        val effectivePerformanceConfig = request.performanceConfig.withThermalAdaptiveOverrides(request.deviceState)
        val thermalThrottled = effectivePerformanceConfig != request.performanceConfig

        responseCache.get(responseCacheKey)?.let { cachedText ->
            if (cachedText.isNotBlank()) {
                emitCachedTokens(cachedText, request.onToken)
                val firstTokenLatencyMs = 1L
                val totalLatency = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
                requirePolicyEvent(
                    eventType = "observability.record_runtime_metrics",
                    failureMessage = "Policy module rejected diagnostics metric event type.",
                )
                observabilityModule.recordLatencyMetric("inference.first_token_ms", firstTokenLatencyMs.toDouble())
                observabilityModule.recordLatencyMetric("inference.total_ms", totalLatency.toDouble())
                observabilityModule.recordLatencyMetric(
                    "inference.profile",
                    effectivePerformanceConfig.profile.ordinal.toDouble(),
                )
                observabilityModule.recordLatencyMetric(
                    "inference.gpu_mode",
                    if (effectivePerformanceConfig.gpuEnabled && effectivePerformanceConfig.gpuLayers > 0) 1.0 else 0.0,
                )
                observabilityModule.recordLatencyMetric(
                    "inference.thermal_throttled",
                    if (thermalThrottled) 1.0 else 0.0,
                )
                observabilityModule.recordThermalSnapshot(request.deviceState.thermalLevel)
                conversationModule.appendAssistantTurn(request.sessionId, cachedText)
                return ChatResponse(
                    sessionId = request.sessionId,
                    modelId = modelId,
                    text = cachedText,
                    firstTokenLatencyMs = firstTokenLatencyMs,
                    totalLatencyMs = totalLatency,
                    requestId = request.requestId,
                    finishReason = "cached",
                    runtimeStats = RuntimeExecutionStats(),
                )
            }
        }

        val nativeInference = inferenceModule as? LlamaCppInferenceModule
        nativeInference?.setRuntimeGenerationConfig(
            effectivePerformanceConfig.toRuntimeGenerationConfig(),
        )

        check(inferenceModule.loadModel(modelId)) {
            "Failed to load runtime model: $modelId"
        }
        nativeInference?.residencyState()?.let { state ->
            recordResidencyMetrics(
                observabilityModule = observabilityModule,
                residencyState = state,
                loadDurationMs = state.lastLoadDurationMs,
                thermalThrottled = thermalThrottled,
            )
        }

        val cachePolicy = modelLifecycleCoordinator.resolveNativeCachePolicy()
        var firstTokenLatencyMs = -1L
        var finishReason = "completed"
        var responseText = ""
        var executionResult: InferenceExecutionResult? = null
        val timeoutGuard = GenerationTimeoutGuard(
            timeoutMs = request.requestTimeoutMs,
            onTimeout = {
                if (runtimeConfig.streamContractV2Enabled) {
                    cancelByRequest(request.requestId)
                } else {
                    cancelBySession(request.sessionId)
                }
            },
        )
        try {
            executionResult = inferenceExecutor.execute(
                sessionId = request.sessionId.value,
                requestId = request.requestId,
                request = InferenceRequest(prompt = prompt, maxTokens = request.maxTokens),
                cacheKey = prefixCacheKey,
                cachePolicy = cachePolicy,
                stopSequences = renderedPrompt.stopSequences,
                onToken = { token ->
                    if (timeoutGuard.timedOut()) {
                        return@execute
                    }
                    if (firstTokenLatencyMs < 0) {
                        firstTokenLatencyMs = System.currentTimeMillis() - startedMs
                        timeoutGuard.finish()
                    }
                    request.onToken(token)
                },
            )
            finishReason = executionResult.finishReason
            responseText = executionResult.text.trim()
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(request.requestTimeoutMs)
            }
        } catch (error: Throwable) {
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(request.requestTimeoutMs)
            }
            throw error
        } finally {
            timeoutGuard.finish()
            if (!request.keepModelLoaded || !request.residencyPolicy.keepLoadedWhileAppForeground) {
                unloadNow()
            } else {
                scheduleIdleUnload(request.residencyPolicy.idleUnloadTtlMs)
            }
        }
        if (timeoutGuard.timedOut()) {
            throw RuntimeGenerationTimeoutException(request.requestTimeoutMs)
        }
        check(responseText.isNotBlank()) { "Runtime returned no tokens." }
        if (firstTokenLatencyMs < 0) {
            firstTokenLatencyMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
        }
        val totalLatency = System.currentTimeMillis() - startedMs
        val prefillMs = executionResult?.prefillMs ?: firstTokenLatencyMs
        val decodeMs = executionResult?.decodeMs ?: (totalLatency - prefillMs).coerceAtLeast(0L)
        val tokenCount = executionResult?.tokenCount ?: 0
        val peakRssMb = executionResult?.peakRssMb
        val tokensPerSec = executionResult?.tokensPerSec
            ?: if (tokenCount > 0 && decodeMs > 0) {
                tokenCount.toDouble() / (decodeMs.toDouble() / 1000.0)
            } else {
                0.0
            }
        responseCache.put(responseCacheKey, responseText)
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.first_token_ms", firstTokenLatencyMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.total_ms", totalLatency.toDouble())
        observabilityModule.recordLatencyMetric("inference.prefill_ms", prefillMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.decode_ms", decodeMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.tokens_per_sec", tokensPerSec)
        peakRssMb?.let { observabilityModule.recordLatencyMetric("inference.peak_rss_mb", it) }
        observabilityModule.recordLatencyMetric("inference.profile", effectivePerformanceConfig.profile.ordinal.toDouble())
        observabilityModule.recordLatencyMetric(
            "inference.gpu_mode",
            if (effectivePerformanceConfig.gpuEnabled && effectivePerformanceConfig.gpuLayers > 0) 1.0 else 0.0,
        )
        observabilityModule.recordLatencyMetric(
            "inference.thermal_throttled",
            if (thermalThrottled) 1.0 else 0.0,
        )
        observabilityModule.recordThermalSnapshot(request.deviceState.thermalLevel)
        conversationModule.appendAssistantTurn(request.sessionId, responseText)

        return ChatResponse(
            sessionId = request.sessionId,
            modelId = modelId,
            text = responseText,
            firstTokenLatencyMs = firstTokenLatencyMs,
            totalLatencyMs = totalLatency,
            requestId = request.requestId,
            finishReason = finishReason,
            runtimeStats = RuntimeExecutionStats(
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                tokensPerSec = tokensPerSec,
                peakRssMb = peakRssMb,
            ),
        )
    }

    private fun buildPrefixCacheKey(
        modelId: String,
        taskType: String,
        prompt: String,
        maxTokens: Int,
    ): String {
        val normalizedPrefix = prompt
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_PREFIX_CACHE_KEY_PROMPT_CHARS)
        val keyMaterial = buildList {
            add(CACHE_KEY_VERSION)
            add(modelId)
            add(runtimeConfig.runtimeCompatibilityTag)
            add(taskType)
            add(runtimeConfig.artifactSha256ByModelId[modelId].orEmpty())
            add(runtimeConfig.artifactProvenanceSignatureByModelId[modelId].orEmpty())
            add("max_tokens=$maxTokens")
            add("decode_profile=$DECODE_PROFILE_STABLE")
            add(normalizedPrefix)
        }.joinToString("|")
        return sha256Hex(keyMaterial)
    }

    private fun buildResponseCacheKey(
        modelId: String,
        taskType: String,
        prompt: String,
        maxTokens: Int,
    ): String {
        val keyMaterial = buildList {
            add(CACHE_KEY_VERSION)
            add(modelId)
            add(runtimeConfig.runtimeCompatibilityTag)
            add(taskType)
            add(runtimeConfig.artifactSha256ByModelId[modelId].orEmpty())
            add(runtimeConfig.artifactProvenanceSignatureByModelId[modelId].orEmpty())
            add("max_tokens=$maxTokens")
            add("decode_profile=$DECODE_PROFILE_STABLE")
            add(prompt)
        }.joinToString("|")
        return sha256Hex(keyMaterial)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun emitCachedTokens(text: String, onToken: (String) -> Unit) {
        text.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { token -> onToken("$token ") }
    }

    private fun requirePolicyEvent(eventType: String, failureMessage: String) {
        check(policyModule.enforceDataBoundary(eventType)) { failureMessage }
    }

    companion object {
        private const val CACHE_KEY_VERSION = "v1"
        private const val DECODE_PROFILE_STABLE = "sampler:greedy"
        private const val MAX_PREFIX_CACHE_KEY_PROMPT_CHARS: Int = 1024
        private const val MAX_PROMPT_CHARS_SHORT: Int = 1024
        private const val MAX_PROMPT_CHARS_LONG: Int = 2048
    }
}

private fun List<InteractionMessage>.latestUserMessageText(): String {
    return asReversed()
        .firstOrNull { message -> message.role == InteractionRole.USER }
        ?.parts
        ?.joinToString(separator = "\n") { part ->
            when (part) {
                is InteractionContentPart.Text -> part.text
            }
        }
        ?.trim()
        .orEmpty()
}

private fun com.pocketagent.core.Turn.toInteractionMessage(): InteractionMessage {
    val interactionRole = when (role.trim().lowercase()) {
        "system" -> InteractionRole.SYSTEM
        "assistant" -> InteractionRole.ASSISTANT
        "tool" -> InteractionRole.TOOL
        else -> InteractionRole.USER
    }
    return InteractionMessage(
        id = "turn-${timestampEpochMs}-${interactionRole.name.lowercase()}",
        role = interactionRole,
        parts = listOf(InteractionContentPart.Text(content)),
        metadata = mapOf("timestampEpochMs" to timestampEpochMs.toString()),
    )
}
