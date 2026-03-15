package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ChatToolCall
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.core.SessionId
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.runtimeInferencePorts

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
    private val modelLifecycleCoordinator: ModelLifecycleCoordinator,
    private val runtimePlanResolver: RuntimePlanResolver,
    private val runtimeResidencyManager: RuntimeResidencyManager,
    private val cancelByRequest: (String) -> Boolean,
    private val cancelBySession: (SessionId) -> Boolean,
    private val runtimeInferencePorts: RuntimeInferencePorts = inferenceModule.runtimeInferencePorts(),
) {
    data class Request(
        val sessionId: SessionId,
        val userText: String,
        val messages: List<InteractionMessage> = emptyList(),
        val taskType: String,
        val executionContext: RuntimeRequestContext,
        val onToken: (String) -> Unit,
        val routingMode: RoutingMode,
    )

    fun execute(request: Request): ChatResponse {
        val executionContext = request.executionContext
        val latestUserText = request.messages.latestUserMessageText().ifBlank { request.userText }
        requirePolicyEvent(
            eventType = "routing.model_select",
            failureMessage = "Policy module rejected routing event type.",
        )
        val modelId = modelLifecycleCoordinator.selectRunnableModelId(
            routingMode = request.routingMode,
            taskType = request.taskType,
            deviceState = executionContext.deviceState,
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

        val contextBudget = routingModule.selectContextBudget(request.taskType, executionContext.deviceState)
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
            deviceState = executionContext.deviceState,
            promptCharBudget = minOf(contextBudget * 4, promptCharBudget),
        )
        val prompt = renderedPrompt.prompt
        val managedRuntime = runtimeInferencePorts.managedRuntime
        val cacheAwareGeneration = runtimeInferencePorts.cacheAwareGeneration
        val modelRegistry = runtimeInferencePorts.modelRegistry
        val residencyPort = runtimeInferencePorts.residency
        val runtimePlan = runtimePlanResolver.resolve(
            sessionId = request.sessionId.value,
            modelId = modelId,
            taskType = request.taskType,
            stopSequences = renderedPrompt.stopSequences,
            requestConfig = executionContext.performanceConfig,
            residencyPolicy = executionContext.residencyPolicy,
            deviceState = executionContext.deviceState,
            runtimeInferencePorts = runtimeInferencePorts,
        )
        runtimePlan.loadBlockedReason?.let { blockedReason ->
            throw RuntimeModelLoadPlanningException(
                message = blockedReason,
                estimatedMemoryMb = runtimePlan.estimatedMemoryMb,
            )
        }
        val prefixCacheKey = buildPrefixCacheKey(
            slotId = runtimePlan.prefixCacheSlotId,
            modelId = modelId,
        )
        requirePolicyEvent(
            eventType = "inference.generate",
            failureMessage = "Policy module rejected inference event type.",
        )
        val startedMs = System.currentTimeMillis()
        val thinkingFilter = ThinkingBlockFilter()

        val effectivePerformanceConfig = runtimePlan.effectiveConfig
        val thermalThrottled = effectivePerformanceConfig != executionContext.performanceConfig

        managedRuntime?.setRuntimeGenerationConfig(runtimePlan.generationConfig)

        check(
            runtimeResidencyManager.ensureLoaded(
                modelId = modelId,
                slotId = runtimePlan.prefixCacheSlotId,
                keepAliveMs = runtimePlan.keepAliveMs,
                sessionCacheKey = runtimePlan.sessionCacheKey,
            ),
        ) {
            "Failed to load runtime model: $modelId"
        }
        residencyPort?.residencyState()?.let { state ->
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
        var parsedToolCalls: List<InteractionToolCall> = emptyList()
        var executionResult: InferenceExecutionResult? = null
        val timeoutGuard = GenerationTimeoutGuard(
            timeoutMs = executionContext.requestTimeoutMs,
            onTimeout = {
                if (runtimeConfig.streamContractV2Enabled) {
                    cancelByRequest(executionContext.requestId)
                } else {
                    cancelBySession(request.sessionId)
                }
            },
        )
        runtimeResidencyManager.onGenerationStarted()
        try {
            executionResult = inferenceExecutor.execute(
                sessionId = request.sessionId.value,
                requestId = executionContext.requestId,
                request = InferenceRequest(prompt = prompt, maxTokens = executionContext.maxTokens),
                cacheKey = prefixCacheKey,
                cachePolicy = cachePolicy,
                stopSequences = renderedPrompt.stopSequences,
                onToken = { token ->
                    if (timeoutGuard.timedOut()) {
                        return@execute
                    }
                    val visibleText = thinkingFilter.filterToken(token)
                    if (visibleText.isNotEmpty()) {
                        if (firstTokenLatencyMs < 0) {
                            firstTokenLatencyMs = System.currentTimeMillis() - startedMs
                            timeoutGuard.finish()
                        }
                        request.onToken(visibleText)
                    }
                },
            )
            finishReason = executionResult.finishReason
            val flushed = thinkingFilter.flush()
            if (flushed.isNotEmpty()) {
                request.onToken(flushed)
            }
            val cleanedText = ThinkingBlockFilter.stripThinkingBlocks(executionResult.text)
            val toolCallResult = ToolCallParser.parse(cleanedText)
            parsedToolCalls = toolCallResult.toolCalls
            responseText = toolCallResult.textWithoutToolCalls.trim()
            if (parsedToolCalls.isNotEmpty()) {
                finishReason = "tool_calls"
            }
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(executionContext.requestTimeoutMs)
            }
        } catch (error: RuntimeException) {
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(executionContext.requestTimeoutMs)
            }
            throw error
        } finally {
            timeoutGuard.finish()
            if (!executionContext.keepModelLoaded || !executionContext.residencyPolicy.keepLoadedWhileAppForeground) {
                runtimeResidencyManager.unload(reason = "request_policy")
                runtimeResidencyManager.onGenerationFinished(slotId = null, keepAliveMs = null)
            } else {
                runtimeResidencyManager.onGenerationFinished(
                    slotId = runtimePlan.prefixCacheSlotId,
                    keepAliveMs = runtimePlan.keepAliveMs,
                )
            }
        }
        if (timeoutGuard.timedOut()) {
            throw RuntimeGenerationTimeoutException(executionContext.requestTimeoutMs)
        }
        check(responseText.isNotBlank() || finishReason == "tool_calls") { "Runtime returned no tokens." }
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
        cacheAwareGeneration?.actualGpuLayers()?.let {
            observabilityModule.recordLatencyMetric("inference.actual_applied_gpu_layers", it.toDouble())
        }
        cacheAwareGeneration?.actualDraftGpuLayers()?.let {
            observabilityModule.recordLatencyMetric("inference.actual_applied_draft_gpu_layers", it.toDouble())
        }
        cacheAwareGeneration?.lastGpuLoadRetryCount()?.let {
            observabilityModule.recordLatencyMetric("inference.gpu_load_retry_count", it.toDouble())
        }
        observabilityModule.recordThermalSnapshot(executionContext.deviceState.thermalLevel)
        conversationModule.appendAssistantTurn(request.sessionId, responseText)

        val actualGpuLayers = cacheAwareGeneration?.actualGpuLayers() ?: runtimePlan.effectiveConfig.gpuLayers
        val actualDraftGpuLayers = cacheAwareGeneration?.actualDraftGpuLayers()
            ?: runtimePlan.effectiveConfig.speculativeDraftGpuLayers
        return ChatResponse(
            sessionId = request.sessionId,
            modelId = modelId,
            text = responseText,
            firstTokenLatencyMs = firstTokenLatencyMs,
            totalLatencyMs = totalLatency,
            requestId = executionContext.requestId,
            finishReason = finishReason,
            runtimeStats = RuntimeExecutionStats(
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                tokensPerSec = tokensPerSec,
                peakRssMb = peakRssMb,
                appliedGpuLayers = actualGpuLayers,
                appliedDraftGpuLayers = actualDraftGpuLayers,
                gpuLoadRetryCount = cacheAwareGeneration?.lastGpuLoadRetryCount(),
                modelLayerCount = modelRegistry?.cachedModelLayerCount(modelId),
                estimatedMaxGpuLayers = modelRegistry?.cachedEstimatedMaxGpuLayers(
                    modelId = modelId,
                    nCtx = runtimePlan.generationConfig.nCtx,
                ),
                estimatedMemoryMb = runtimePlan.estimatedMemoryMb,
            ),
        )
    }
    private fun buildPrefixCacheKey(
        slotId: String,
        modelId: String,
    ): String {
        val keyMaterial = buildList {
            add(CACHE_KEY_VERSION)
            add(slotId)
            add(modelId)
            add(runtimeConfig.runtimeCompatibilityTag)
            add(runtimeConfig.artifactSha256ByModelId[modelId].orEmpty())
            add(runtimeConfig.artifactProvenanceSignatureByModelId[modelId].orEmpty())
        }.joinToString("|")
        return sha256Hex(keyMaterial)
    }

    private fun requirePolicyEvent(eventType: String, failureMessage: String) {
        check(policyModule.enforceDataBoundary(eventType)) { failureMessage }
    }

    companion object {
        private const val CACHE_KEY_VERSION = "v1"
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
