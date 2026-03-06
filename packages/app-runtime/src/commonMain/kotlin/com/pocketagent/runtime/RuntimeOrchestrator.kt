package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.DefaultPolicyModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.InMemoryObservabilityModule
import com.pocketagent.core.ObservabilityModule
import com.pocketagent.core.PolicyModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.AdaptiveRoutingPolicy
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RuntimeImageInputModule
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolModule
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeOrchestrator(
    private val conversationModule: ConversationModule = InMemoryConversationModule(),
    private val inferenceModule: InferenceModule = LlamaCppInferenceModule(),
    private val routingModule: RoutingModule = AdaptiveRoutingPolicy(),
    private val policyModule: PolicyModule = DefaultPolicyModule(offlineOnly = true),
    private val observabilityModule: ObservabilityModule = InMemoryObservabilityModule(),
    private val toolModule: ToolModule = SafeLocalToolRuntime(),
    private val memoryModule: MemoryModule = FileBackedMemoryModule.ephemeralRuntimeModule(),
    private val runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
    private val networkPolicyClient: PolicyAwareNetworkClient = PolicyAwareNetworkClient(policyModule),
    private val artifactVerifier: ArtifactVerifier = ArtifactVerifier(runtimeConfig),
    private val diagnosticsRedactor: DiagnosticsRedactor = DiagnosticsRedactor(),
) : RuntimeContainer {
    private val imageInputModule = RuntimeImageInputModule(inferenceModule)
    private val sessionManager = RuntimeSessionManager(conversationModule, memoryModule)
    private val templateRegistry = ModelTemplateRegistry()
    private val interactionPlanner = InteractionPlanner(templateRegistry = templateRegistry)
    private val inferenceExecutor = InferenceExecutor(
        inferenceModule = inferenceModule,
        runtimeConfig = runtimeConfig,
    )
    private val toolLoopCoordinator = ToolLoopCoordinator(toolModule)
    private val responseCache = RuntimeResponseCache(
        maxEntries = runtimeConfig.responseCacheMaxEntries,
        ttlMs = runtimeConfig.responseCacheTtlSec.coerceAtLeast(0L) * 1000L,
    )
    private var routingMode: RoutingMode = RoutingMode.AUTO

    init {
        val nativeInference = inferenceModule as? LlamaCppInferenceModule
        if (nativeInference != null) {
            artifactVerifier.registerRuntimeModelPaths(nativeInference)
        }
    }

    override fun createSession(): SessionId = sessionManager.createSession()

    override fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
    ): ChatResponse {
        requirePolicyEvent(
            eventType = "routing.model_select",
            failureMessage = "Policy module rejected routing event type.",
        )
        val modelId = selectModelId(taskType = taskType, deviceState = deviceState)
        check(artifactVerifier.manager().setActiveModel(modelId)) {
            "Model artifact not registered for runtime model: $modelId"
        }
        artifactVerifier.verifyArtifactOrThrow(modelId)
        interactionPlanner.ensureTemplateAvailable(modelId)?.let { message ->
            throw RuntimeTemplateUnavailableException(message)
        }

        conversationModule.appendUserTurn(sessionId, userText)
        requirePolicyEvent(
            eventType = "memory.write_user_turn",
            failureMessage = "Policy module rejected memory write event type.",
        )
        memoryModule.saveMemoryChunk(
            MemoryChunk(
                id = "mem-${System.currentTimeMillis()}",
                content = userText,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val contextBudget = routingModule.selectContextBudget(taskType, deviceState)
        val promptCharBudget = when (taskType) {
            "long_text" -> MAX_PROMPT_CHARS_LONG
            else -> MAX_PROMPT_CHARS_SHORT
        }
        val memorySnippets = memoryModule.retrieveRelevantMemory(userText, 3).map { it.content }
        val renderedPrompt = interactionPlanner.buildRenderedPrompt(
            modelId = modelId,
            turns = conversationModule.listTurns(sessionId),
            memorySnippets = memorySnippets,
            taskType = taskType,
            deviceState = deviceState,
            promptCharBudget = minOf(contextBudget * 4, promptCharBudget),
        )
        val prompt = renderedPrompt.prompt
        val prefixCacheKey = buildPrefixCacheKey(
            modelId = modelId,
            taskType = taskType,
            prompt = prompt,
            maxTokens = maxTokens,
        )
        val responseCacheKey = buildResponseCacheKey(
            modelId = modelId,
            taskType = taskType,
            prompt = prompt,
            maxTokens = maxTokens,
        )
        requirePolicyEvent(
            eventType = "inference.generate",
            failureMessage = "Policy module rejected inference event type.",
        )
        val startedMs = System.currentTimeMillis()

        responseCache.get(responseCacheKey)?.let { cachedText ->
            if (cachedText.isNotBlank()) {
                emitCachedTokens(cachedText, onToken)
                val firstTokenLatencyMs = 1L
                val totalLatency = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
                requirePolicyEvent(
                    eventType = "observability.record_runtime_metrics",
                    failureMessage = "Policy module rejected diagnostics metric event type.",
                )
                observabilityModule.recordLatencyMetric("inference.first_token_ms", firstTokenLatencyMs.toDouble())
                observabilityModule.recordLatencyMetric("inference.total_ms", totalLatency.toDouble())
                observabilityModule.recordThermalSnapshot(deviceState.thermalLevel)
                conversationModule.appendAssistantTurn(sessionId, cachedText)
                return ChatResponse(
                    sessionId = sessionId,
                    modelId = modelId,
                    text = cachedText,
                    firstTokenLatencyMs = firstTokenLatencyMs,
                    totalLatencyMs = totalLatency,
                    requestId = requestId,
                    finishReason = "cached",
                )
            }
        }

        check(inferenceModule.loadModel(modelId)) {
            "Failed to load runtime model: $modelId"
        }

        val cachePolicy = resolveNativeCachePolicy()
        var firstTokenLatencyMs = -1L
        var finishReason = "completed"
        var responseText = ""
        val timeoutGuard = GenerationTimeoutGuard(
            timeoutMs = requestTimeoutMs,
            onTimeout = {
                if (runtimeConfig.streamContractV2Enabled) {
                    cancelGenerationByRequest(requestId)
                } else {
                    cancelGeneration(sessionId)
                }
            },
        )
        try {
            val executionResult = inferenceExecutor.execute(
                sessionId = sessionId.value,
                requestId = requestId,
                request = InferenceRequest(prompt = prompt, maxTokens = maxTokens),
                cacheKey = prefixCacheKey,
                cachePolicy = cachePolicy,
                stopSequences = renderedPrompt.stopSequences,
                onToken = { token ->
                    if (timeoutGuard.timedOut()) {
                        return@execute
                    }
                    if (firstTokenLatencyMs < 0) {
                        firstTokenLatencyMs = System.currentTimeMillis() - startedMs
                    }
                    onToken(token)
                },
            )
            finishReason = executionResult.finishReason
            responseText = executionResult.text.trim()
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(requestTimeoutMs)
            }
        } catch (error: Throwable) {
            if (timeoutGuard.timedOut()) {
                throw RuntimeGenerationTimeoutException(requestTimeoutMs)
            }
            throw error
        } finally {
            timeoutGuard.finish()
            if (!keepModelLoaded) {
                inferenceModule.unloadModel()
            }
        }
        if (timeoutGuard.timedOut()) {
            throw RuntimeGenerationTimeoutException(requestTimeoutMs)
        }
        check(responseText.isNotBlank()) { "Runtime returned no tokens." }
        if (firstTokenLatencyMs < 0) {
            firstTokenLatencyMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
        }
        val totalLatency = System.currentTimeMillis() - startedMs
        responseCache.put(responseCacheKey, responseText)
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.first_token_ms", firstTokenLatencyMs.toDouble())
        observabilityModule.recordLatencyMetric("inference.total_ms", totalLatency.toDouble())
        observabilityModule.recordThermalSnapshot(deviceState.thermalLevel)
        conversationModule.appendAssistantTurn(sessionId, responseText)

        return ChatResponse(
            sessionId = sessionId,
            modelId = modelId,
            text = responseText,
            firstTokenLatencyMs = firstTokenLatencyMs,
            totalLatencyMs = totalLatency,
            requestId = requestId,
            finishReason = finishReason,
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String {
        if (!policyModule.enforceDataBoundary("tool.execute")) {
            return "Tool error: Policy module rejected tool event type."
        }
        val result = toolLoopCoordinator.executeToolCall(toolName = toolName, jsonArgs = jsonArgs)
        if (result.success) {
            return result.content
        }
        if (result.content.startsWith("TOOL_VALIDATION_ERROR:")) {
            return result.content
        }
        return "Tool error: ${result.content}"
    }

    override fun analyzeImage(
        imagePath: String,
        prompt: String,
    ): String {
        return analyzeImage(
            imagePath = imagePath,
            prompt = prompt,
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
        )
    }

    fun analyzeImage(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState,
    ): String {
        requirePolicyEvent(
            eventType = "routing.image_model_select",
            failureMessage = "Policy module rejected image routing event type.",
        )
        val modelId = selectModelId(taskType = "image", deviceState = deviceState)
        check(artifactVerifier.manager().setActiveModel(modelId)) {
            "Model artifact not registered for image runtime model: $modelId"
        }
        artifactVerifier.verifyArtifactOrThrow(modelId)
        check(inferenceModule.loadModel(modelId)) {
            "Failed to load runtime model for image analysis: $modelId"
        }

        val startedMs = System.currentTimeMillis()
        val imageResult = try {
            requirePolicyEvent(
                eventType = "inference.image_analyze",
                failureMessage = "Policy module rejected image analysis event type.",
            )
            imageInputModule.analyzeImage(
                com.pocketagent.inference.ImageRequest(
                    imagePath = imagePath,
                    prompt = prompt,
                    maxTokens = 128,
                ),
            )
        } finally {
            inferenceModule.unloadModel()
        }

        val totalLatency = System.currentTimeMillis() - startedMs
        requirePolicyEvent(
            eventType = "observability.record_runtime_metrics",
            failureMessage = "Policy module rejected diagnostics metric event type.",
        )
        observabilityModule.recordLatencyMetric("inference.image.total_ms", totalLatency.toDouble())
        observabilityModule.recordThermalSnapshot(deviceState.thermalLevel)
        return imageResult
    }

    override fun exportDiagnostics(): String {
        requirePolicyEvent(
            eventType = "observability.export",
            failureMessage = "Policy module rejected diagnostics export event type.",
        )
        return diagnosticsRedactor.redact(observabilityModule.exportLocalDiagnostics())
    }

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> {
        val checks = mutableListOf<String>()
        val manifestIssues = artifactVerifier.manager().validateManifest()
        if (manifestIssues.isNotEmpty()) {
            checks.add(
                "Artifact manifest invalid: ${manifestIssues.joinToString("; ") { "${it.modelId}@${it.version}:${it.code}" }}. " +
                    "Set ${RuntimeConfig.QWEN_0_8B_SHA256_ENV} and ${RuntimeConfig.QWEN_2B_SHA256_ENV} with SHA-256 values before Stage-2 closure runs.",
            )
            return checks
        }

        val requiredModels = setOf(ModelCatalog.QWEN_3_5_0_8B_Q4, ModelCatalog.QWEN_3_5_2B_Q4)
        requiredModels.forEach { modelId ->
            if (!artifactVerifier.manager().setActiveModel(modelId)) {
                checks.add("Artifact manifest missing required model registration: $modelId.")
                return@forEach
            }
            interactionPlanner.ensureTemplateAvailable(modelId)?.let { templateError ->
                checks.add(templateError)
                return@forEach
            }
            val verification = artifactVerifier.verifyArtifactForModel(modelId)
            if (!verification.passed) {
                checks.add("Artifact verification failed for $modelId: ${artifactVerifier.artifactVerificationFailureMessage(verification)}")
            }
        }
        if (checks.isNotEmpty()) {
            return checks
        }

        val runtimeBackend = runtimeBackend()
        if (runtimeConfig.requireNativeRuntimeForStartupChecks &&
            runtimeBackend != null &&
            runtimeBackend != RuntimeBackend.NATIVE_JNI.name
        ) {
            checks.add(
                "Runtime backend is $runtimeBackend. " +
                    "Native JNI runtime is required for closure-path startup checks. " +
                    "Set ${RuntimeConfig.REQUIRE_NATIVE_RUNTIME_STARTUP_ENV}=0 only for local scaffolding lanes.",
            )
            return checks
        }

        val available = inferenceModule.listAvailableModels().toSet()
        val missing = requiredModels.minus(available)
        if (missing.isNotEmpty()) {
            checks.add("Missing runtime model(s): ${missing.joinToString(", ")}.")
        } else {
            if (!inferenceModule.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4)) {
                checks.add("Failed to load baseline runtime model: ${ModelCatalog.QWEN_3_5_0_8B_Q4}.")
            }
        }
        if (!policyModule.enforceDataBoundary("inference.startup_check")) {
            checks.add("Policy module rejected startup event type.")
        }
        checks.addAll(networkPolicyClient.startupChecks())
        val networkProbe = networkPolicyClient.enforce("runtime.offline_probe")
        if (networkProbe.allowed) {
            checks.add("Network policy wiring invalid: offline-only mode unexpectedly allowed runtime.offline_probe.")
        }
        return checks
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        sessionManager.restoreSession(sessionId, turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = sessionManager.deleteSession(sessionId)

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        return inferenceExecutor.cancelBySession(sessionId.value)
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        return inferenceExecutor.cancelByRequest(requestId)
    }

    fun listSessions(): List<SessionId> = sessionManager.listSessions()

    fun listTurns(sessionId: SessionId): List<Turn> = sessionManager.listTurns(sessionId)

    override fun runtimeBackend(): String? = runtimeBackendEnum()?.name

    fun runtimeBackendEnum(): RuntimeBackend? {
        return (inferenceModule as? LlamaCppInferenceModule)?.runtimeBackend()
    }

    private fun resolveNativeCachePolicy(): CachePolicy {
        if (!runtimeConfig.prefixCacheEnabled) {
            return CachePolicy.OFF
        }
        return if (runtimeConfig.prefixCacheStrict) {
            CachePolicy.PREFIX_KV_REUSE_STRICT
        } else {
            CachePolicy.PREFIX_KV_REUSE
        }
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

    private fun selectModelId(taskType: String, deviceState: DeviceState): String {
        return when (routingMode) {
            RoutingMode.AUTO -> routingModule.selectModel(taskType, deviceState)
            RoutingMode.QWEN_0_8B -> ModelCatalog.QWEN_3_5_0_8B_Q4
            RoutingMode.QWEN_2B -> ModelCatalog.QWEN_3_5_2B_Q4
        }
    }

    companion object {
        const val ENABLE_ADB_FALLBACK_ENV: String = NativeJniLlamaCppBridge.ENABLE_ADB_FALLBACK_ENV
        private const val CACHE_KEY_VERSION = "v1"
        private const val DECODE_PROFILE_STABLE = "sampler:greedy"
        private const val MAX_PREFIX_CACHE_KEY_PROMPT_CHARS: Int = 1024
        private const val MAX_PROMPT_CHARS_SHORT: Int = 1024
        private const val MAX_PROMPT_CHARS_LONG: Int = 2048
    }
}

class RuntimeGenerationTimeoutException(
    val timeoutMs: Long,
) : RuntimeException("Generation timed out after ${(timeoutMs / 1000L).coerceAtLeast(1L)}s.")

class RuntimeGenerationCancelledException(
    val requestId: String,
) : RuntimeException("Generation was cancelled for requestId=$requestId")

class RuntimeGenerationFailureException(
    message: String,
    val errorCode: String? = null,
) : RuntimeException(message)

private class GenerationTimeoutGuard(
    timeoutMs: Long,
    onTimeout: () -> Unit,
) {
    private val timedOutFlag = AtomicBoolean(false)
    private val finishedFlag = AtomicBoolean(false)
    private val timer = Timer("runtime-generation-timeout", true)

    init {
        val safeTimeout = timeoutMs.coerceAtLeast(1L)
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    if (finishedFlag.get()) {
                        return
                    }
                    timedOutFlag.set(true)
                    onTimeout()
                }
            },
            safeTimeout,
        )
    }

    fun timedOut(): Boolean = timedOutFlag.get()

    fun finish() {
        finishedFlag.set(true)
        timer.cancel()
    }
}
