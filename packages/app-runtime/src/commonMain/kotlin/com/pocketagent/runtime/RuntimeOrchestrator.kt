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
import com.pocketagent.inference.RuntimeImageInputModule
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolModule
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
    private val modelRegistry: ModelRegistry = ModelRegistry.default(),
    private val artifactVerifier: ArtifactVerifier = ArtifactVerifier(runtimeConfig, modelRegistry = modelRegistry),
    private val diagnosticsRedactor: DiagnosticsRedactor = DiagnosticsRedactor(),
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
    private val runtimePlanResolver = RuntimePlanResolver()
    private val runtimeResidencyManager = RuntimeResidencyManager(inferenceModule)
    private var routingMode: RoutingMode = RoutingMode.AUTO
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
            prefixCacheDiagnostics?.let {
                append('\n')
                append(it)
            }
        }
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

    override fun touchKeepAlive(): Boolean = runtimeResidencyManager.touchKeepAlive()

    override fun shortenKeepAlive(ttlMs: Long): Boolean = runtimeResidencyManager.shortenKeepAlive(ttlMs)

    override fun onTrimMemory(level: Int): Boolean = runtimeResidencyManager.onTrimMemory(level)

    override fun onAppBackground(): Boolean = runtimeResidencyManager.onAppBackground()

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

    companion object {
        const val ENABLE_ADB_FALLBACK_ENV: String = NativeJniLlamaCppBridge.ENABLE_ADB_FALLBACK_ENV
    }
}

private fun sanitizeMetricSegment(raw: String): String {
    return raw.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }
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
