package com.pocketagent.android

import com.pocketagent.inference.ModelCatalog

interface LlamaCppRuntimeBridge {
    fun isReady(): Boolean
    fun listAvailableModels(): List<String>
    fun loadModel(modelId: String): Boolean
    fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean
    fun unloadModel()
}

class AndroidLlamaCppRuntimeBridge(
    private val libraryName: String = "pocket_llama",
    private val nativeApi: NativeApi = JniNativeApi(),
    private val libraryLoader: (String) -> Unit = System::loadLibrary,
    private val supportedModels: Set<String> = setOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    ),
    private val fallbackBridge: LlamaCppRuntimeBridge = AdbDeviceLlamaCppRuntimeBridge(),
    private val fallbackEnabled: Boolean = true,
) : LlamaCppRuntimeBridge {
    private var initialized = false
    private var runtimeReady = false
    private var usingFallback = false

    override fun isReady(): Boolean {
        ensureRuntimeInitialized()
        return runtimeReady
    }

    override fun listAvailableModels(): List<String> = supportedModels.sorted()

    override fun loadModel(modelId: String): Boolean {
        ensureRuntimeInitialized()
        if (!runtimeReady || !supportedModels.contains(modelId)) {
            return false
        }
        if (usingFallback) {
            return fallbackBridge.loadModel(modelId)
        }
        return runCatching { nativeApi.loadModel(modelId) }.getOrDefault(false)
    }

    override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean {
        ensureRuntimeInitialized()
        if (!runtimeReady) {
            return false
        }
        if (usingFallback) {
            return fallbackBridge.generate(prompt, maxTokens, onToken)
        }
        val output = runCatching {
            nativeApi.generate(prompt = prompt, maxTokens = maxTokens)
        }.getOrNull() ?: return false

        output
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { token -> onToken("$token ") }
        return true
    }

    override fun unloadModel() {
        if (!runtimeReady) {
            return
        }
        if (usingFallback) {
            fallbackBridge.unloadModel()
            return
        }
        runCatching { nativeApi.unloadModel() }
    }

    private fun ensureRuntimeInitialized() {
        if (initialized) {
            return
        }
        initialized = true
        val nativeReady = runCatching {
            libraryLoader(libraryName)
            nativeApi.initialize()
        }.getOrDefault(false)
        if (nativeReady) {
            runtimeReady = true
            usingFallback = false
            return
        }

        if (fallbackEnabled && fallbackBridge.isReady()) {
            runtimeReady = true
            usingFallback = true
            return
        }

        runtimeReady = false
        usingFallback = false
    }

    interface NativeApi {
        fun initialize(): Boolean
        fun loadModel(modelId: String): Boolean
        fun generate(prompt: String, maxTokens: Int): String
        fun unloadModel()
    }

    private class JniNativeApi : NativeApi {
        external fun nativeInitialize(): Boolean
        external fun nativeLoadModel(modelId: String): Boolean
        external fun nativeGenerate(prompt: String, maxTokens: Int): String
        external fun nativeUnloadModel()

        override fun initialize(): Boolean = nativeInitialize()

        override fun loadModel(modelId: String): Boolean = nativeLoadModel(modelId)

        override fun generate(prompt: String, maxTokens: Int): String = nativeGenerate(prompt, maxTokens)

        override fun unloadModel() = nativeUnloadModel()
    }
}
