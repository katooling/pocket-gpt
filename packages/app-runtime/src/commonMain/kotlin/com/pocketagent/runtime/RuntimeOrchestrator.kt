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
import com.pocketagent.inference.ArtifactVerificationStatus
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.RuntimeImageInputModule
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    private val modelRegistry: ModelRegistry = ModelRegistry.default(),
    private val artifactVerifier: ArtifactVerifier = ArtifactVerifier(runtimeConfig, modelRegistry = modelRegistry),
    private val diagnosticsRedactor: DiagnosticsRedactor = DiagnosticsRedactor(),
    private val memoryBudgetTracker: MemoryBudgetTracker? = null,
    private val recommendedGpuLayers: (String, PerformanceRuntimeConfig) -> Int? = { _, _ -> null },
) : RuntimeContainer {
    private val imageInputModule = RuntimeImageInputModule(inferenceModule)
    private val sessionManager = RuntimeSessionManager(conversationModule, memoryModule)
    private val templateRegistry = ModelTemplateRegistry(
        profileByModelId = ModelTemplateRegistry.defaultProfiles(modelRegistry = modelRegistry),
    )
    private val interactionPlanner = InteractionPlanner(templateRegistry = templateRegistry)
    private val inferenceExecutor = InferenceExecutor(
        inferenceModule = inferenceModule,
        runtimeConfig = runtimeConfig,
    )
    private val toolLoopCoordinator = ToolLoopCoordinator(toolModule)
    private val runtimePlanResolver = RuntimePlanResolver(
        memoryBudgetTracker = memoryBudgetTracker,
        recommendedGpuLayers = recommendedGpuLayers,
    )
    private val sessionCacheManager = SessionCacheManager(
        cacheDir = File(
            System.getProperty("java.io.tmpdir").orEmpty().ifBlank { "." },
            "pocketgpt-session-cache",
        ),
    )
    private val lastResolvedRuntimePlan = AtomicReference<ResolvedRuntimePlan?>(null)
    private val runtimeResidencyManager = RuntimeResidencyManager(
        inferenceModule = inferenceModule,
        onAfterLoad = ::restoreSessionCache,
        onBeforeUnload = ::saveSessionCache,
    )
    private var routingMode: RoutingMode = RoutingMode.AUTO
    private val modelLifecycleLock = ReentrantLock()
    private val pendingLoadModelId = AtomicReference<String?>(null)
    private val modelLifecycleCoordinator = ModelLifecycleCoordinator(
        inferenceModule = inferenceModule,
        routingModule = routingModule,
        runtimeConfig = runtimeConfig,
    )
    private val sendMessageUseCase = SendMessageUseCase(
        conversationModule = conversationModule,
        routingModule = routingModule,
        policyModule = policyModule,
        observabilityModule = observabilityModule,
        memoryModule = memoryModule,
        inferenceModule = inferenceModule,
        runtimeConfig = runtimeConfig,
        artifactVerifier = artifactVerifier,
        interactionPlanner = interactionPlanner,
        inferenceExecutor = inferenceExecutor,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        runtimePlanResolver = runtimePlanResolver,
        runtimeResidencyManager = runtimeResidencyManager,
        cancelByRequest = ::cancelGenerationByRequest,
        cancelBySession = ::cancelGeneration,
    )
    private val toolExecutionUseCase = ToolExecutionUseCase(
        policyModule = policyModule,
        toolLoopCoordinator = toolLoopCoordinator,
    )
    private val imageAnalyzeUseCase = ImageAnalyzeUseCase(
        policyModule = policyModule,
        inferenceModule = inferenceModule,
        artifactVerifier = artifactVerifier,
        imageInputModule = imageInputModule,
        observabilityModule = observabilityModule,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        routingModeProvider = { routingMode },
    )
    private val diagnosticsUseCase = DiagnosticsUseCase(
        policyModule = policyModule,
        observabilityModule = observabilityModule,
        diagnosticsRedactor = diagnosticsRedactor,
    )
    private val startupChecksUseCase = StartupChecksUseCase(
        artifactVerifier = artifactVerifier,
        interactionPlanner = interactionPlanner,
        inferenceModule = inferenceModule,
        policyModule = policyModule,
        runtimeConfig = runtimeConfig,
        networkPolicyClient = networkPolicyClient,
        modelLifecycleCoordinator = modelLifecycleCoordinator,
        runtimeBackendProvider = ::runtimeBackend,
        modelRegistry = modelRegistry,
    )
    private val runtimeWarmupCoordinator = RuntimeWarmupCoordinator(
        inferenceModule = inferenceModule,
        artifactVerifier = artifactVerifier,
        observabilityModule = observabilityModule,
        runtimeResidencyManager = runtimeResidencyManager,
        runtimePlanResolver = runtimePlanResolver,
    )

    init {
        val nativeInference = inferenceModule as? LlamaCppInferenceModule
        if (nativeInference != null) {
            artifactVerifier.registerRuntimeModelPaths(nativeInference)
        }
    }

    override fun createSession(): SessionId = sessionManager.createSession()

    override fun sendChatMessages(
        sessionId: SessionId,
        messages: List<InteractionMessage>,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
        previousResponseId: String?,
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        val latestUserText = messages
            .asReversed()
            .firstOrNull { message -> message.role == InteractionRole.USER }
            ?.parts
            ?.joinToString(separator = "\n") { part ->
                when (part) {
                    is InteractionContentPart.Text -> part.text
                }
            }
            ?.trim()
            .orEmpty()
        return sendMessageUseCase.execute(
            SendMessageUseCase.Request(
                sessionId = sessionId,
                userText = latestUserText,
                messages = messages,
                taskType = taskType,
                deviceState = deviceState,
                maxTokens = maxTokens,
                keepModelLoaded = keepModelLoaded,
                onToken = onToken,
                requestTimeoutMs = requestTimeoutMs,
                requestId = requestId,
                previousResponseId = previousResponseId,
                performanceConfig = performanceConfig,
                residencyPolicy = residencyPolicy,
                routingMode = routingMode,
            ),
        )
    }

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
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        return sendMessageUseCase.execute(
            SendMessageUseCase.Request(
                sessionId = sessionId,
                userText = userText,
                messages = emptyList(),
                taskType = taskType,
                deviceState = deviceState,
                maxTokens = maxTokens,
                keepModelLoaded = keepModelLoaded,
                onToken = onToken,
                requestTimeoutMs = requestTimeoutMs,
                requestId = requestId,
                previousResponseId = null,
                performanceConfig = performanceConfig,
                residencyPolicy = residencyPolicy,
                routingMode = routingMode,
            ),
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String {
        return runToolDetailed(toolName = toolName, jsonArgs = jsonArgs).toLegacyString()
    }

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return toolExecutionUseCase.execute(toolName = toolName, jsonArgs = jsonArgs)
    }

    override fun analyzeImage(
        imagePath: String,
        prompt: String,
    ): String {
        return legacyImageResponse(
            analyzeImageDetailed(
                imagePath = imagePath,
                prompt = prompt,
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
            ),
        )
    }

    override fun analyzeImageDetailed(
        imagePath: String,
        prompt: String,
    ): ImageAnalysisResult {
        return analyzeImageDetailed(
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
        return legacyImageResponse(
            analyzeImageDetailed(
                imagePath = imagePath,
                prompt = prompt,
                deviceState = deviceState,
            ),
        )
    }

    fun analyzeImageDetailed(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState,
    ): ImageAnalysisResult {
        return imageAnalyzeUseCase.execute(
            imagePath = imagePath,
            prompt = prompt,
            deviceState = deviceState,
        )
    }

    private fun legacyImageResponse(result: ImageAnalysisResult): String {
        return when (result) {
            is ImageAnalysisResult.Success -> result.content
            is ImageAnalysisResult.Failure -> {
                val failure = result.failure
                if (failure is ImageFailure.PolicyDenied || (failure is ImageFailure.Runtime && failure.code == "image_runtime_error")) {
                    throw IllegalStateException(failure.technicalDetail ?: failure.userMessage)
                }
                result.toLegacyString()
            }
        }
    }

    override fun exportDiagnostics(): String {
        val nativeInference = inferenceModule as? LlamaCppInferenceModule
        val nativeResidencyState = nativeInference?.residencyState()
        val prefixCacheDiagnostics = nativeInference?.prefixCacheDiagnosticsLine()
        return buildString {
            append(diagnosticsUseCase.export())
            append('\n')
            append(runtimeResidencyManager.diagnosticsLine(nativeResidencyState))
            memoryBudgetTracker?.let {
                append('\n')
                append(it.diagnosticsLine())
            }
            prefixCacheDiagnostics?.let {
                append('\n')
                append(it)
            }
        }
    }

    override fun exportDiagnosticsJson(): String? {
        val nativeInference = inferenceModule as? LlamaCppInferenceModule
        val nativeResidencyState = nativeInference?.residencyState()
        val lifecycleEvent = nativeInference?.currentModelLifecycleState()
        val sessionCacheDiagnostics = sessionCacheManager.diagnostics()
        val lastPlan = lastResolvedRuntimePlan.get()
        return buildString {
            append('{')
            append("\"residency\":")
            append(
                jsonObject(
                    "residentModelId" to runtimeResidencyManager.loadedModelId(),
                    "queueDepth" to runtimeResidencyManager.queueDepth(),
                    "state" to nativeResidencyState?.toString(),
                    "diagnostics" to runtimeResidencyManager.diagnosticsLine(nativeResidencyState),
                ),
            )
            append(",\"memoryBudget\":")
            append(
                jsonObject(
                    "availableMemoryCeilingMb" to memoryBudgetTracker?.availableMemoryCeilingMb,
                    "largestSuccessfulLoadMb" to memoryBudgetTracker?.largestSuccessfulLoadMb,
                    "lastUpdatedAtEpochMs" to memoryBudgetTracker?.lastUpdatedAtEpochMs,
                ),
            )
            append(",\"lifecycle\":")
            append(
                jsonObject(
                    "state" to lifecycleEvent?.state?.name,
                    "modelId" to lifecycleEvent?.modelId,
                    "modelVersion" to lifecycleEvent?.modelVersion,
                    "loadingStage" to lifecycleEvent?.loadingStage?.name,
                    "loadingProgress" to lifecycleEvent?.loadingProgress,
                    "detail" to lifecycleEvent?.loadingDetail,
                    "errorCode" to lifecycleEvent?.error?.code?.name,
                    "errorDetail" to lifecycleEvent?.error?.detail,
                ),
            )
            append(",\"sessionCache\":")
            append(
                jsonObject(
                    "entryCount" to sessionCacheDiagnostics.entryCount,
                    "totalBytes" to sessionCacheDiagnostics.totalBytes,
                    "maxTotalBytes" to sessionCacheDiagnostics.maxTotalBytes,
                ),
            )
            append(",\"lastLoadPlan\":")
            append(
                jsonObject(
                    "modelId" to lastPlan?.modelId,
                    "estimatedMemoryMb" to lastPlan?.estimatedMemoryMb,
                    "loadBlockedReason" to lastPlan?.loadBlockedReason,
                    "diagnostics" to lastPlan?.diagnostics?.joinToString(separator = "|"),
                    "sessionCacheKey" to lastPlan?.sessionCacheKey,
                ),
            )
            append('}')
        }
    }

    override fun currentModelLifecycleEvent(): ModelLifecycleEvent? {
        return (inferenceModule as? LlamaCppInferenceModule)?.currentModelLifecycleState()
    }

    override fun observeModelLifecycleEvents(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        val nativeInference = inferenceModule as? LlamaCppInferenceModule ?: return AutoCloseable { }
        return nativeInference.observeModelLifecycleState(listener)
    }

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> {
        return startupChecksUseCase.run()
    }

    override fun warmupActiveModel(): WarmupResult {
        return runtimeWarmupCoordinator.warmup()
    }

    override fun evictResidentModel(reason: String): Boolean {
        runtimeResidencyManager.unload(reason)
        observabilityModule.recordLatencyMetric(
            "inference.resident_eviction.${sanitizeMetricSegment(reason)}",
            1.0,
        )
        return true
    }

    override fun loadModel(modelId: String, modelVersion: String?): RuntimeModelLifecycleCommandResult {
        // Pre-flight checks (outside lock) — fast rejection without blocking other operations.
        val availableModels = inferenceModule.listAvailableModels().toSet()
        if (!availableModels.contains(modelId)) {
            return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                detail = "model_not_available:$modelId",
            )
        }
        if (!artifactVerifier.manager().setActiveModel(modelId)) {
            return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                detail = "model_unregistered:$modelId",
            )
        }
        val verification = artifactVerifier.verifyArtifactForModel(modelId)
        if (!verification.passed) {
            val detail = artifactVerifier.artifactVerificationFailureMessage(verification)
            val code = if (verification.status == ArtifactVerificationStatus.RUNTIME_INCOMPATIBLE) {
                ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE
            } else {
                ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE
            }
            return RuntimeModelLifecycleCommandResult.rejected(
                code = code,
                detail = detail.ifBlank { "artifact_verification_failed" },
            )
        }
        interactionPlanner.ensureTemplateAvailable(modelId)?.let { templateError ->
            return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE,
                detail = templateError,
            )
        }
        val nativeInference = inferenceModule as? LlamaCppInferenceModule
            ?: return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.BACKEND_INIT_FAILED,
                detail = "runtime_load_requires_native_bridge",
            )

        // Register intent for last-one-wins tracking.
        pendingLoadModelId.set(modelId)

        // Acquire lifecycle lock — serializes all load/unload operations.
        modelLifecycleLock.lock()
        try {
            // Last-one-wins check: if a newer load was requested while we waited, abort.
            if (pendingLoadModelId.get() != modelId) {
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "superseded_by_pending_load:${pendingLoadModelId.get()}",
                )
            }

            // Stop-Await-Release: cancel active generations and wait for them to finish.
            if (!inferenceExecutor.isIdle()) {
                inferenceExecutor.cancelAllAndAwaitIdle(timeoutMs = STOP_AWAIT_TIMEOUT_MS)
            }

            // Unload previous model if one is resident.
            if (runtimeResidencyManager.loadedModelId() != null) {
                runtimeResidencyManager.unload(reason = "model_switch")
                awaitNativeCleanup(nativeInference)
            }

            // Re-check last-one-wins after awaiting + unload.
            if (pendingLoadModelId.get() != modelId) {
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
                    detail = "superseded_after_unload:${pendingLoadModelId.get()}",
                )
            }

            val performanceConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                gpuEnabled = nativeInference.supportsGpuOffload(),
            )
            val runtimePlan = runtimePlanResolver.resolve(
                sessionId = "manual-load",
                modelId = modelId,
                taskType = "manual_load",
                stopSequences = emptyList(),
                requestConfig = performanceConfig,
                residencyPolicy = ModelResidencyPolicy(),
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
                nativeInference = nativeInference,
            )
            lastResolvedRuntimePlan.set(runtimePlan)
            runtimePlan.loadBlockedReason?.let { blockedReason ->
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.OUT_OF_MEMORY,
                    detail = blockedReason,
                    loadedModel = loadedModel(),
                )
            }
            nativeInference.setRuntimeGenerationConfig(runtimePlan.generationConfig)
            val loaded = nativeInference.loadModel(
                modelId = modelId,
                modelVersion = modelVersion,
                strictGpuOffload = runtimePlan.generationConfig.strictGpuOffload,
            )
            if (!loaded) {
                val bridgeError = nativeInference.lastBridgeError()
                return RuntimeModelLifecycleCommandResult.rejected(
                    code = mapBridgeLifecycleCode(bridgeError?.code),
                    detail = bridgeError?.detail,
                    loadedModel = loadedModel(),
                )
            }
            runtimeResidencyManager.attachResidentSlot(
                modelId = modelId,
                slotId = runtimePlan.prefixCacheSlotId,
                keepAliveMs = runtimePlan.keepAliveMs,
                sessionCacheKey = runtimePlan.sessionCacheKey,
            )
            return RuntimeModelLifecycleCommandResult.applied(
                loadedModel = RuntimeLoadedModel(
                    modelId = modelId,
                    modelVersion = modelVersion,
                ),
            )
        } finally {
            pendingLoadModelId.compareAndSet(modelId, null)
            modelLifecycleLock.unlock()
        }
    }

    override fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        modelLifecycleLock.lock()
        try {
            // Stop-Await-Release: cancel active generations before offloading.
            if (!inferenceExecutor.isIdle()) {
                inferenceExecutor.cancelAllAndAwaitIdle(timeoutMs = STOP_AWAIT_TIMEOUT_MS)
            }
            val currentlyLoaded = loadedModel()
            return when (runtimeResidencyManager.requestUnload(reason = reason)) {
                RuntimeUnloadDisposition.UNLOADED,
                RuntimeUnloadDisposition.NO_RESIDENT_MODEL,
                -> RuntimeModelLifecycleCommandResult.applied(loadedModel = null)

                RuntimeUnloadDisposition.QUEUED -> RuntimeModelLifecycleCommandResult.queued(
                    loadedModel = currentlyLoaded,
                    detail = "offload_queued_while_generation_active",
                )
            }
        } finally {
            modelLifecycleLock.unlock()
        }
    }

    override fun loadedModel(): RuntimeLoadedModel? {
        val modelId = runtimeResidencyManager.loadedModelId() ?: return null
        return RuntimeLoadedModel(modelId = modelId, modelVersion = artifactVerifier.manager().getActiveModelVersion())
    }

    override fun activeGenerationCount(): Int = runtimeResidencyManager.queueDepth()

    override fun touchKeepAlive(): Boolean = runtimeResidencyManager.touchKeepAlive()

    override fun shortenKeepAlive(ttlMs: Long): Boolean = runtimeResidencyManager.shortenKeepAlive(ttlMs)

    override fun onTrimMemory(level: Int): Boolean = runtimeResidencyManager.onTrimMemory(level)

    override fun onAppBackground(): Boolean = runtimeResidencyManager.onAppBackground()

    override fun onAppForeground(): Boolean {
        if (!runtimeResidencyManager.wasAutoReleased) {
            return false
        }
        val modelId = runtimeResidencyManager.lastAutoReleasedModelId ?: return false
        runtimeResidencyManager.clearAutoReleasedState()
        val result = loadModel(modelId = modelId)
        return result.success
    }

    override fun addAutoReleaseDisableReason(reason: String) {
        runtimeResidencyManager.addAutoReleaseDisableReason(reason)
    }

    override fun removeAutoReleaseDisableReason(reason: String) {
        runtimeResidencyManager.removeAutoReleaseDisableReason(reason)
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

    override fun supportsGpuOffload(): Boolean {
        return (inferenceModule as? LlamaCppInferenceModule)?.supportsGpuOffload() ?: false
    }

    fun runtimeBackendEnum(): RuntimeBackend? {
        return (inferenceModule as? LlamaCppInferenceModule)?.runtimeBackend()
    }

    private fun saveSessionCache(slot: ResidentRuntimeSlot, reason: String) {
        val cacheKey = slot.sessionCacheKey?.takeIf { it.isNotBlank() } ?: return
        val nativeInference = inferenceModule as? LlamaCppInferenceModule ?: return
        if (reason == "reconcile_missing_file" || reason == "reconcile_missing_version") {
            sessionCacheManager.evict(cacheKey)
            return
        }
        sessionCacheManager.save(
            serializer = object : SessionCacheSerializer {
                override fun saveSessionCache(filePath: String): Boolean = nativeInference.saveSessionCache(filePath)
            },
            cacheKey = cacheKey,
        )
    }

    private fun restoreSessionCache(slot: ResidentRuntimeSlot) {
        val cacheKey = slot.sessionCacheKey?.takeIf { it.isNotBlank() } ?: return
        val nativeInference = inferenceModule as? LlamaCppInferenceModule ?: return
        sessionCacheManager.restore(
            serializer = object : SessionCacheSerializer {
                override fun saveSessionCache(filePath: String): Boolean = nativeInference.saveSessionCache(filePath)
                override fun loadSessionCache(filePath: String): Boolean = nativeInference.loadSessionCache(filePath)
            },
            cacheKey = cacheKey,
        )
    }

    private fun awaitNativeCleanup(nativeInference: LlamaCppInferenceModule) {
        val deadlineMs = System.currentTimeMillis() + NATIVE_CLEANUP_TIMEOUT_MS
        var lastRssMb = nativeInference.currentRssMb()
        var stableReads = 0
        while (System.currentTimeMillis() <= deadlineMs) {
            val released = nativeInference.isRuntimeReleased()
            val currentRssMb = nativeInference.currentRssMb()
            if (released) {
                if (currentRssMb == null || lastRssMb == null) {
                    return
                }
                stableReads = if (abs(currentRssMb - lastRssMb) <= NATIVE_CLEANUP_STABLE_DELTA_MB) {
                    stableReads + 1
                } else {
                    0
                }
                if (stableReads >= NATIVE_CLEANUP_REQUIRED_STABLE_READS) {
                    return
                }
            }
            lastRssMb = currentRssMb ?: lastRssMb
            Thread.sleep(NATIVE_CLEANUP_POLL_INTERVAL_MS)
        }
        observabilityModule.recordLatencyMetric("inference.native_cleanup_timeout", 1.0)
    }

    companion object {
        const val ENABLE_ADB_FALLBACK_ENV: String = NativeJniLlamaCppBridge.ENABLE_ADB_FALLBACK_ENV
    }
}

private const val STOP_AWAIT_TIMEOUT_MS = 5_000L
private const val NATIVE_CLEANUP_POLL_INTERVAL_MS = 50L
private const val NATIVE_CLEANUP_TIMEOUT_MS = 2_000L
private const val NATIVE_CLEANUP_STABLE_DELTA_MB = 4.0
private const val NATIVE_CLEANUP_REQUIRED_STABLE_READS = 2

private fun mapBridgeLifecycleCode(errorCode: String?): ModelLifecycleErrorCode {
    val normalized = errorCode?.trim()?.uppercase().orEmpty()
    return when {
        normalized.contains("OUT_OF_MEMORY") ||
            normalized.contains("OOM") ||
            normalized.contains("ENOMEM") ->
            ModelLifecycleErrorCode.OUT_OF_MEMORY

        normalized.contains("MODEL") || normalized.contains("FILE") || normalized.contains("PATH") ->
            ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE

        normalized.contains("COMPAT") || normalized.contains("PROVENANCE") || normalized.contains("CHECKSUM") ->
            ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE

        normalized.contains("BUSY") -> ModelLifecycleErrorCode.BUSY_GENERATION

        normalized.contains("CANCELLED_NEWER_REQUEST") ->
            ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST

        normalized.contains("JNI") ||
            normalized.contains("RUNTIME") ||
            normalized.contains("BACKEND") ->
            ModelLifecycleErrorCode.BACKEND_INIT_FAILED

        else -> ModelLifecycleErrorCode.UNKNOWN
    }
}

private fun sanitizeMetricSegment(raw: String): String {
    return raw.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }
}

private fun jsonObject(vararg fields: Pair<String, Any?>): String {
    return fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${jsonEscape(key)}\":${jsonValue(value)}"
    }
}

private fun jsonValue(value: Any?): String {
    return when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> "\"${jsonEscape(value.toString())}\""
    }
}

private fun jsonEscape(value: String): String {
    return buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
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

internal class GenerationTimeoutGuard(
    timeoutMs: Long,
    onTimeout: () -> Unit,
) {
    private val timedOutFlag = AtomicBoolean(false)
    private val finishedFlag = AtomicBoolean(false)
    private val timeoutJob = CoroutineScope(Dispatchers.Default).let { scope ->
        scope.launch {
            delay(timeoutMs.coerceAtLeast(1L))
            if (finishedFlag.get()) {
                return@launch
            }
            timedOutFlag.set(true)
            onTimeout()
        }
    }

    fun timedOut(): Boolean = timedOutFlag.get()

    fun finish() {
        finishedFlag.set(true)
        timeoutJob.cancel()
    }
}
