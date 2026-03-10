package com.pocketagent.nativebridge

import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

enum class RuntimeBackend {
    NATIVE_JNI,
    REMOTE_ANDROID_SERVICE,
    ADB_FALLBACK,
    UNAVAILABLE,
}

enum class GpuExecutionBackend {
    AUTO,
    HEXAGON,
    OPENCL,
    CPU,
}

enum class CachePolicy(val code: Int) {
    OFF(0),
    PREFIX_KV_REUSE(1),
    PREFIX_KV_REUSE_STRICT(2),
}

data class RuntimeSamplingConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
)

data class RuntimeLoadConfig(
    val nThreads: Int = 0,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val nCtx: Int = 2048,
    val gpuEnabled: Boolean = false,
    val gpuLayers: Int = 0,
    val gpuBackend: GpuExecutionBackend = GpuExecutionBackend.AUTO,
    val strictGpuOffload: Boolean = true,
    val quantizedKvCache: Boolean = true,
    val speculativeEnabled: Boolean = false,
    val speculativeDraftModelId: String? = null,
    val speculativeDraftModelPath: String? = null,
    val speculativeMaxDraftTokens: Int = 6,
    val speculativeMinDraftTokens: Int = 2,
    val speculativeDraftGpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
)

data class RuntimeGenerationConfig(
    val nThreads: Int = 0,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val nCtx: Int = 2048,
    val gpuEnabled: Boolean = false,
    val gpuLayers: Int = 0,
    val gpuBackend: GpuExecutionBackend = GpuExecutionBackend.AUTO,
    val strictGpuOffload: Boolean = true,
    val quantizedKvCache: Boolean = true,
    val sampling: RuntimeSamplingConfig = RuntimeSamplingConfig(),
    val speculativeEnabled: Boolean = false,
    val speculativeDraftModelId: String? = null,
    val speculativeDraftModelPath: String? = null,
    val speculativeMaxDraftTokens: Int = 6,
    val speculativeMinDraftTokens: Int = 2,
    val speculativeDraftGpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val nKeep: Int = 128,
) {
    companion object {
        fun default(): RuntimeGenerationConfig {
            return RuntimeGenerationConfig()
        }
    }

    fun toLoadConfig(): RuntimeLoadConfig {
        return RuntimeLoadConfig(
            nThreads = nThreads,
            nThreadsBatch = nThreadsBatch,
            nBatch = nBatch,
            nUbatch = nUbatch,
            nCtx = nCtx,
            gpuEnabled = gpuEnabled,
            gpuLayers = gpuLayers,
            gpuBackend = gpuBackend,
            strictGpuOffload = strictGpuOffload,
            quantizedKvCache = quantizedKvCache,
            speculativeEnabled = speculativeEnabled,
            speculativeDraftModelId = speculativeDraftModelId,
            speculativeDraftModelPath = speculativeDraftModelPath,
            speculativeMaxDraftTokens = speculativeMaxDraftTokens,
            speculativeMinDraftTokens = speculativeMinDraftTokens,
            speculativeDraftGpuLayers = speculativeDraftGpuLayers,
            useMmap = useMmap,
            useMlock = useMlock,
        )
    }
}

data class LoadedRuntimeKey(
    val modelId: String,
    val modelPath: String?,
    val backend: RuntimeBackend,
    val loadConfig: RuntimeLoadConfig,
)

enum class RuntimeReloadReason {
    INITIAL_LOAD,
    MODEL_CHANGED,
    MODEL_PATH_CHANGED,
    BACKEND_CHANGED,
    GENERATION_CONFIG_CHANGED,
    EXPLICIT_UNLOAD,
}

data class RuntimeResidencyState(
    val key: LoadedRuntimeKey? = null,
    val resident: Boolean = false,
    val residentHit: Boolean = false,
    val residentHitCount: Long = 0L,
    val slotId: String? = null,
    val expiresAtEpochMs: Long? = null,
    val reloadReason: RuntimeReloadReason? = null,
    val lastLoadDurationMs: Long? = null,
    val lastWarmupDurationMs: Long? = null,
    val lastLoadAtEpochMs: Long? = null,
    val lastAccessAtEpochMs: Long? = null,
)

enum class ModelLifecycleState {
    UNLOADED,
    LOADING,
    LOADED,
    OFFLOADING,
    FAILED,
}

enum class ModelLifecycleErrorCode {
    MODEL_FILE_UNAVAILABLE,
    RUNTIME_INCOMPATIBLE,
    BACKEND_INIT_FAILED,
    BUSY_GENERATION,
    CANCELLED_BY_NEWER_REQUEST,
    UNKNOWN,
}

data class ModelLifecycleError(
    val code: ModelLifecycleErrorCode,
    val detail: String? = null,
)

data class ModelLoadOptions(
    val modelVersion: String? = null,
    val strictGpuOffload: Boolean = true,
)

data class LoadedModelInfo(
    val modelId: String,
    val modelPath: String?,
    val modelVersion: String? = null,
)

data class ModelLifecycleEvent(
    val state: ModelLifecycleState,
    val modelId: String? = null,
    val modelVersion: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val error: ModelLifecycleError? = null,
)

enum class GenerationFinishReason {
    COMPLETED,
    MAX_TOKENS,
    CANCELLED,
    CALLBACK_ERROR,
    UTF8_STREAM_ERROR,
    ERROR,
}

data class GenerationResult(
    val finishReason: GenerationFinishReason,
    val tokenCount: Int,
    val firstTokenMs: Long,
    val totalMs: Long,
    val cancelled: Boolean,
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    val tokensPerSec: Double? = null,
    val peakRssMb: Double? = null,
    val errorCode: String? = null,
) {
    val success: Boolean
        get() = finishReason == GenerationFinishReason.COMPLETED || finishReason == GenerationFinishReason.MAX_TOKENS
}

interface LlamaCppRuntimeBridge {
    fun isReady(): Boolean
    fun listAvailableModels(): List<String>
    fun loadModel(modelId: String): Boolean = loadModel(modelId, null, ModelLoadOptions())
    fun loadModel(modelId: String, modelPath: String?): Boolean
    fun loadModel(modelId: String, modelPath: String?, options: ModelLoadOptions): Boolean =
        loadModel(modelId, modelPath)
    fun setRuntimeGenerationConfig(config: RuntimeGenerationConfig) {}
    fun getRuntimeGenerationConfig(): RuntimeGenerationConfig = RuntimeGenerationConfig.default()
    fun supportsGpuOffload(): Boolean = false
    fun modelLayerCount(): Int? = null
    fun modelSizeBytes(): Long? = null
    fun estimateMaxGpuLayers(nCtx: Int): Int? = null
    fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): GenerationResult =
        generate(
            requestId = "legacy",
            prompt = prompt,
            maxTokens = maxTokens,
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            onToken = onToken,
        )
    fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult
    fun cancelGeneration(): Boolean = false
    fun cancelGeneration(requestId: String): Boolean = cancelGeneration()
    fun unloadModel()
    fun offloadModel(reason: String): Boolean {
        unloadModel()
        return true
    }
    fun getLoadedModel(): LoadedModelInfo? = null
    fun currentModelLifecycleState(): ModelLifecycleEvent {
        val loaded = getLoadedModel()
        return if (loaded != null) {
            ModelLifecycleEvent(
                state = ModelLifecycleState.LOADED,
                modelId = loaded.modelId,
                modelVersion = loaded.modelVersion,
            )
        } else {
            ModelLifecycleEvent(state = ModelLifecycleState.UNLOADED)
        }
    }
    fun observeModelLifecycleState(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        listener(currentModelLifecycleState())
        return AutoCloseable { }
    }
    fun runtimeBackend(): RuntimeBackend
    fun lastError(): BridgeError? = null
    fun backendDiagnosticsJson(): String? = null
    fun setBackendProfile(profile: String) {}
    fun prefixCacheDiagnosticsLine(): String? = null
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface CommandRunner {
    fun run(command: List<String>): CommandResult
}

internal class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>): CommandResult {
        return runCatching {
            val process = ProcessBuilder(command).start()
            val stdoutBytes = ByteArrayOutputStream()
            val stderrBytes = ByteArrayOutputStream()
            val stdoutDrain = thread(name = "process-command-runner-stdout", start = true) {
                process.inputStream.use { input -> input.copyTo(stdoutBytes) }
            }
            val stderrDrain = thread(name = "process-command-runner-stderr", start = true) {
                process.errorStream.use { input -> input.copyTo(stderrBytes) }
            }
            val exitCode = process.waitFor()
            stdoutDrain.join()
            stderrDrain.join()
            CommandResult(
                exitCode = exitCode,
                stdout = stdoutBytes.toString(Charsets.UTF_8.name()),
                stderr = stderrBytes.toString(Charsets.UTF_8.name()),
            )
        }.getOrElse { error ->
            CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = error.message ?: error::class.simpleName.orEmpty(),
            )
        }
    }
}
