package com.pocketagent.android

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
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.RoutingModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.runtime.PolicyAwareNetworkClient
import com.pocketagent.runtime.RuntimeConfig
import com.pocketagent.runtime.RuntimeOrchestrator
import com.pocketagent.tools.SafeLocalToolRuntime
import com.pocketagent.tools.ToolModule

class AndroidMvpContainer(
    private val conversationModule: ConversationModule = InMemoryConversationModule(),
    private val inferenceModule: InferenceModule = LlamaCppInferenceModule(),
    private val routingModule: RoutingModule = AdaptiveRoutingPolicy(),
    private val policyModule: PolicyModule = DefaultPolicyModule(offlineOnly = true),
    private val observabilityModule: ObservabilityModule = InMemoryObservabilityModule(),
    private val toolModule: ToolModule = SafeLocalToolRuntime(),
    private val memoryModule: MemoryModule = FileBackedMemoryModule.ephemeralRuntimeModule(),
    private val artifactPayloadByModelId: Map<String, ByteArray> = RuntimeConfig.fromEnvironment().artifactPayloadByModelId,
    private val artifactFilePathByModelId: Map<String, String> = RuntimeConfig.fromEnvironment().artifactFilePathByModelId,
    private val artifactSha256ByModelId: Map<String, String> = RuntimeConfig.fromEnvironment().artifactSha256ByModelId,
    private val artifactProvenanceIssuerByModelId: Map<String, String> = RuntimeConfig.fromEnvironment().artifactProvenanceIssuerByModelId,
    private val artifactProvenanceSignatureByModelId: Map<String, String> = RuntimeConfig.fromEnvironment().artifactProvenanceSignatureByModelId,
    private val runtimeCompatibilityTag: String = RuntimeConfig.fromEnvironment().runtimeCompatibilityTag,
    private val requireNativeRuntimeForStartupChecks: Boolean = RuntimeConfig.fromEnvironment().requireNativeRuntimeForStartupChecks,
    private val prefixCacheEnabled: Boolean = RuntimeConfig.fromEnvironment().prefixCacheEnabled,
    private val prefixCacheStrict: Boolean = RuntimeConfig.fromEnvironment().prefixCacheStrict,
    private val responseCacheTtlSec: Long = RuntimeConfig.fromEnvironment().responseCacheTtlSec,
    private val responseCacheMaxEntries: Int = RuntimeConfig.fromEnvironment().responseCacheMaxEntries,
    private val networkPolicyClient: PolicyAwareNetworkClient = PolicyAwareNetworkClient(policyModule),
) {
    private val runtimeConfig = RuntimeConfig(
        artifactPayloadByModelId = artifactPayloadByModelId,
        artifactFilePathByModelId = artifactFilePathByModelId,
        artifactSha256ByModelId = artifactSha256ByModelId,
        artifactProvenanceIssuerByModelId = artifactProvenanceIssuerByModelId,
        artifactProvenanceSignatureByModelId = artifactProvenanceSignatureByModelId,
        runtimeCompatibilityTag = runtimeCompatibilityTag,
        requireNativeRuntimeForStartupChecks = requireNativeRuntimeForStartupChecks,
        prefixCacheEnabled = prefixCacheEnabled,
        prefixCacheStrict = prefixCacheStrict,
        responseCacheTtlSec = responseCacheTtlSec,
        responseCacheMaxEntries = responseCacheMaxEntries,
    )

    private val orchestrator = RuntimeOrchestrator(
        conversationModule = conversationModule,
        inferenceModule = inferenceModule,
        routingModule = routingModule,
        policyModule = policyModule,
        observabilityModule = observabilityModule,
        toolModule = toolModule,
        memoryModule = memoryModule,
        runtimeConfig = runtimeConfig,
        networkPolicyClient = networkPolicyClient,
    )

    fun createSession(): SessionId = orchestrator.createSession()

    fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int = 128,
        keepModelLoaded: Boolean = false,
    ): ChatResponse {
        return sendUserMessage(
            sessionId = sessionId,
            userText = userText,
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            keepModelLoaded = keepModelLoaded,
            onToken = {},
        )
    }

    fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int = 128,
        keepModelLoaded: Boolean = false,
        onToken: (String) -> Unit,
    ): ChatResponse {
        return orchestrator.sendUserMessage(
            sessionId = sessionId,
            userText = userText,
            taskType = taskType,
            deviceState = deviceState,
            maxTokens = maxTokens,
            onToken = onToken,
            keepModelLoaded = keepModelLoaded,
        )
    }

    fun runTool(toolName: String, jsonArgs: String): String = orchestrator.runTool(toolName, jsonArgs)

    fun analyzeImage(
        imagePath: String,
        prompt: String,
        deviceState: DeviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
    ): String {
        return orchestrator.analyzeImage(
            imagePath = imagePath,
            prompt = prompt,
            deviceState = deviceState,
        )
    }

    fun runStartupChecks(): List<String> = orchestrator.runStartupChecks()

    fun exportDiagnostics(): String = orchestrator.exportDiagnostics()

    fun listSessions(): List<SessionId> = orchestrator.listSessions()

    fun listTurns(sessionId: SessionId): List<Turn> = orchestrator.listTurns(sessionId)

    fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        orchestrator.restoreSession(sessionId = sessionId, turns = turns)
    }

    fun deleteSession(sessionId: SessionId): Boolean = orchestrator.deleteSession(sessionId)

    fun setRoutingMode(mode: RoutingMode) {
        orchestrator.setRoutingMode(mode)
    }

    fun getRoutingMode(): RoutingMode = orchestrator.getRoutingMode()

    fun runtimeBackend(): RuntimeBackend? = orchestrator.runtimeBackendEnum()

    companion object {
        const val QWEN_0_8B_SHA256_ENV: String = RuntimeConfig.QWEN_0_8B_SHA256_ENV
        const val QWEN_2B_SHA256_ENV: String = RuntimeConfig.QWEN_2B_SHA256_ENV
        const val QWEN_0_8B_SIDELOAD_PATH_ENV: String = RuntimeConfig.QWEN_0_8B_SIDELOAD_PATH_ENV
        const val QWEN_2B_SIDELOAD_PATH_ENV: String = RuntimeConfig.QWEN_2B_SIDELOAD_PATH_ENV
        const val QWEN_0_8B_PROVENANCE_SIG_ENV: String = RuntimeConfig.QWEN_0_8B_PROVENANCE_SIG_ENV
        const val QWEN_2B_PROVENANCE_SIG_ENV: String = RuntimeConfig.QWEN_2B_PROVENANCE_SIG_ENV
        const val MODEL_PROVENANCE_ISSUER_ENV: String = RuntimeConfig.MODEL_PROVENANCE_ISSUER_ENV
        const val MODEL_RUNTIME_COMPATIBILITY_ENV: String = RuntimeConfig.MODEL_RUNTIME_COMPATIBILITY_ENV
        const val REQUIRE_NATIVE_RUNTIME_STARTUP_ENV: String = RuntimeConfig.REQUIRE_NATIVE_RUNTIME_STARTUP_ENV
        const val PREFIX_CACHE_ENABLED_ENV: String = RuntimeConfig.PREFIX_CACHE_ENABLED_ENV
        const val PREFIX_CACHE_STRICT_ENV: String = RuntimeConfig.PREFIX_CACHE_STRICT_ENV
        const val RESPONSE_CACHE_TTL_SEC_ENV: String = RuntimeConfig.RESPONSE_CACHE_TTL_SEC_ENV
        const val RESPONSE_CACHE_MAX_ENTRIES_ENV: String = RuntimeConfig.RESPONSE_CACHE_MAX_ENTRIES_ENV
        const val ENABLE_ADB_FALLBACK_ENV: String = NativeJniLlamaCppBridge.ENABLE_ADB_FALLBACK_ENV

        fun baselineModels(): List<String> = ModelCatalog.baselineModels()
    }
}
