package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.CacheAwareGenerationPort
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.LlamaCppRuntimeBridge
import com.pocketagent.nativebridge.LoadedModelInfo
import com.pocketagent.nativebridge.ManagedRuntimePort
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.nativebridge.ModelLoadingStage
import com.pocketagent.nativebridge.ModelRuntimeMetadata
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import com.pocketagent.nativebridge.RuntimeBackend
import com.pocketagent.nativebridge.RuntimeInferencePortProvider
import com.pocketagent.nativebridge.RuntimeInferencePorts
import com.pocketagent.nativebridge.RuntimeModelRegistryPort
import com.pocketagent.nativebridge.RuntimeResidencyPort
import com.pocketagent.nativebridge.RuntimeResidencyState
import com.pocketagent.nativebridge.RuntimeSessionCachePort
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeOrchestratorLifecycleTest {
    @Test
    fun `reload emits restoring session cache stage before returning to loaded`() {
        val modelId = ModelCatalog.QWEN_3_5_0_8B_Q4
        val modelFile = File.createTempFile("runtime-orchestrator-lifecycle", ".gguf").apply {
            writeText("fake-gguf-payload")
            deleteOnExit()
        }
        val bridge = RecordingLifecycleBridge(modelId = modelId)
        val module = RecordingLifecycleInferenceModule(bridge)
        module.registerModelPath(modelId, modelFile.absolutePath)
        val orchestrator = RuntimeOrchestrator(
            inferenceModule = module,
            runtimeConfig = lifecycleRuntimeConfig(modelId = modelId, file = modelFile),
        )
        val events = mutableListOf<ModelLifecycleEvent>()
        val subscription = orchestrator.observeModelLifecycleEvents { event ->
            synchronized(events) {
                events += event
            }
        }

        try {
            assertTrue(orchestrator.loadModel(modelId = modelId).success)
            assertTrue(orchestrator.offloadModel(reason = "manual").success)
            assertTrue(orchestrator.loadModel(modelId = modelId).success)

            waitForLifecycleEvent(events) { event ->
                event.state == ModelLifecycleState.LOADING &&
                    event.loadingStage == ModelLoadingStage.RESTORING_SESSION_CACHE
            }
            waitForLifecycleEvent(events) { event ->
                event.state == ModelLifecycleState.LOADED &&
                    event.loadingStage == ModelLoadingStage.COMPLETED
            }

            assertTrue(bridge.savedSessionPaths.isNotEmpty())
            assertTrue(bridge.restoredSessionPaths.isNotEmpty())
            assertEquals(ModelLifecycleState.LOADED, orchestrator.currentModelLifecycleEvent()?.state)
        } finally {
            subscription.close()
            modelFile.delete()
        }
    }
}

private class RecordingLifecycleInferenceModule(
    private val bridge: RecordingLifecycleBridge,
) : InferenceModule,
    RuntimeInferencePortProvider,
    ManagedRuntimePort,
    CacheAwareGenerationPort,
    RuntimeModelRegistryPort,
    RuntimeResidencyPort,
    RuntimeSessionCachePort {
    private var activeModelId: String? = null
    private val modelPathById = mutableMapOf<String, String>()
    private val modelMetadataById = mutableMapOf<String, ModelRuntimeMetadata>()
    private var runtimeGenerationConfig: RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    private var runtimeResidencyState: RuntimeResidencyState = RuntimeResidencyState()

    override fun runtimeInferencePorts(): RuntimeInferencePorts {
        return RuntimeInferencePorts(
            managedRuntime = this,
            cacheAwareGeneration = this,
            modelRegistry = this,
            residency = this,
            sessionCache = this,
        )
    }

    override fun listAvailableModels(): List<String> = bridge.listAvailableModels()

    override fun loadModel(modelId: String): Boolean {
        return loadModel(modelId = modelId, modelVersion = null, strictGpuOffload = runtimeGenerationConfig.strictGpuOffload)
    }

    override fun loadModel(modelId: String, modelVersion: String?, strictGpuOffload: Boolean): Boolean {
        val loaded = bridge.loadModel(
            modelId = modelId,
            modelPath = modelPathById[modelId],
            options = com.pocketagent.nativebridge.ModelLoadOptions(
                modelVersion = modelVersion,
                strictGpuOffload = strictGpuOffload,
            ),
        )
        if (loaded) {
            activeModelId = modelId
            runtimeResidencyState = runtimeResidencyState.copy(resident = true, lastAccessAtEpochMs = System.currentTimeMillis())
        }
        return loaded
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        generateStreamWithCache(
            requestId = "legacy",
            request = request,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            onToken = onToken,
        )
    }

    override fun generateStreamWithCache(
        requestId: String,
        request: InferenceRequest,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        return bridge.generate(
            requestId = requestId,
            prompt = request.prompt,
            maxTokens = request.maxTokens,
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
            onToken = onToken,
        )
    }

    override fun unloadModel() {
        bridge.offloadModel(reason = "explicit_unload")
        activeModelId = null
        runtimeResidencyState = runtimeResidencyState.copy(resident = false, lastAccessAtEpochMs = System.currentTimeMillis())
    }

    override fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {
        runtimeGenerationConfig = config
    }

    override fun supportsGpuOffload(): Boolean = false

    override fun runtimeBackend(): RuntimeBackend = bridge.runtimeBackend()

    override fun lastBridgeError(): BridgeError? = bridge.lastError()

    override fun currentModelLifecycleState(): ModelLifecycleEvent = bridge.currentModelLifecycleState()

    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        return bridge.observeModelLifecycleState(listener)
    }

    override fun currentRssMb(): Double? = null

    override fun isRuntimeReleased(): Boolean = false

    override fun cancelGeneration(requestId: String): Boolean = false

    override fun actualGpuLayers(): Int? = null

    override fun actualDraftGpuLayers(): Int? = null

    override fun lastGpuLoadRetryCount(): Int? = null

    override fun registerModelPath(modelId: String, absolutePath: String) {
        modelPathById[modelId] = absolutePath
    }

    override fun registeredModelPath(modelId: String): String? = modelPathById[modelId]

    override fun registerModelMetadata(modelId: String, metadata: ModelRuntimeMetadata) {
        modelMetadataById[modelId] = metadata
    }

    override fun cachedModelMetadata(modelId: String): ModelRuntimeMetadata? = modelMetadataById[modelId]

    override fun cachedModelLayerCount(modelId: String): Int? = modelMetadataById[modelId]?.layerCount

    override fun cachedModelSizeBytes(modelId: String): Long? = modelMetadataById[modelId]?.sizeBytes

    override fun cachedEstimatedMaxGpuLayers(modelId: String, nCtx: Int): Int? = null

    override fun updateResidencySlot(slotId: String?, expiresAtEpochMs: Long?) {
        runtimeResidencyState = runtimeResidencyState.copy(
            slotId = slotId,
            expiresAtEpochMs = expiresAtEpochMs,
            lastAccessAtEpochMs = System.currentTimeMillis(),
        )
    }

    override fun residencyState(): RuntimeResidencyState = runtimeResidencyState

    override fun prefixCacheDiagnosticsLine(): String? = null

    override fun recordWarmup(durationMs: Long) = Unit

    override fun saveSessionCache(filePath: String): Boolean {
        return if (activeModelId != null) bridge.saveSessionCache(filePath) else false
    }

    override fun loadSessionCache(filePath: String): Boolean {
        return if (activeModelId != null) bridge.loadSessionCache(filePath) else false
    }
}

private class RecordingLifecycleBridge(
    private val modelId: String,
) : LlamaCppRuntimeBridge {
    private val observers = mutableMapOf<Int, (ModelLifecycleEvent) -> Unit>()
    private var nextObserverId: Int = 1
    private var loadedModel: LoadedModelInfo? = null
    private var currentEvent: ModelLifecycleEvent = ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED)
    val savedSessionPaths = mutableListOf<String>()
    val restoredSessionPaths = mutableListOf<String>()

    override fun isReady(): Boolean = true

    override fun listAvailableModels(): List<String> = listOf(modelId)

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        return loadModel(modelId, modelPath, com.pocketagent.nativebridge.ModelLoadOptions())
    }

    override fun loadModel(modelId: String, modelPath: String?, options: com.pocketagent.nativebridge.ModelLoadOptions): Boolean {
        emit(
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADING,
                modelId = modelId,
                modelVersion = options.modelVersion,
                loadingStage = ModelLoadingStage.LOADING_MODEL,
                loadingProgress = 0.5f,
            ),
        )
        loadedModel = LoadedModelInfo(modelId = modelId, modelPath = modelPath, modelVersion = options.modelVersion)
        emit(
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADED,
                modelId = modelId,
                modelVersion = options.modelVersion,
                loadingStage = ModelLoadingStage.COMPLETED,
                loadingProgress = 1.0f,
            ),
        )
        return true
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        onToken("ok")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 1,
            firstTokenMs = 1L,
            totalMs = 2L,
            cancelled = false,
        )
    }

    override fun offloadModel(reason: String): Boolean {
        emit(
            ModelLifecycleEvent(
                state = ModelLifecycleState.OFFLOADING,
                modelId = loadedModel?.modelId,
                modelVersion = loadedModel?.modelVersion,
            ),
        )
        loadedModel = null
        emit(ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED))
        return true
    }

    override fun unloadModel() {
        offloadModel("legacy")
    }

    override fun getLoadedModel(): LoadedModelInfo? = loadedModel

    override fun currentModelLifecycleState(): ModelLifecycleEvent = currentEvent

    override fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        val observerId = nextObserverId++
        observers[observerId] = listener
        listener(currentEvent)
        return AutoCloseable { observers.remove(observerId) }
    }

    override fun runtimeBackend(): RuntimeBackend = RuntimeBackend.NATIVE_JNI

    override fun lastError(): BridgeError? = null

    override fun saveSessionCache(filePath: String): Boolean {
        savedSessionPaths += filePath
        File(filePath).writeText("cached-session")
        return true
    }

    override fun loadSessionCache(filePath: String): Boolean {
        restoredSessionPaths += filePath
        return File(filePath).exists()
    }

    private fun emit(event: ModelLifecycleEvent) {
        currentEvent = event
        observers.values.toList().forEach { observer -> observer(event) }
    }
}

private fun lifecycleRuntimeConfig(modelId: String, file: File): RuntimeConfig {
    val payloadSha = sha256Hex(file.readBytes())
    return RuntimeConfig(
        artifactPayloadByModelId = emptyMap(),
        artifactFilePathByModelId = mapOf(modelId to file.absolutePath),
        artifactSha256ByModelId = mapOf(modelId to payloadSha),
        artifactProvenanceIssuerByModelId = mapOf(modelId to "internal-release"),
        artifactProvenanceSignatureByModelId = mapOf(modelId to "sig-0"),
        runtimeCompatibilityTag = "android-arm64-v8a",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = true,
        prefixCacheStrict = false,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
    )
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun waitForLifecycleEvent(
    events: List<ModelLifecycleEvent>,
    timeoutMs: Long = 1_000L,
    predicate: (ModelLifecycleEvent) -> Boolean,
) {
    val startedAt = System.currentTimeMillis()
    while ((System.currentTimeMillis() - startedAt) < timeoutMs) {
        val matched = synchronized(events) { events.any(predicate) }
        if (matched) {
            return
        }
        Thread.sleep(10L)
    }
    error("Expected lifecycle event was not observed within ${timeoutMs}ms")
}
