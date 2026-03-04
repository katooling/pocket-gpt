package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog

class AdbDeviceLlamaCppBridge(
    private val supportedModels: Set<String> = setOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    ),
    private val commandRunner: CommandRunner = ProcessCommandRunner(),
    private val env: Map<String, String> = System.getenv(),
) : LlamaCppRuntimeBridge {
    private var activeSerial: String? = null
    private var activeModelId: String? = null

    override fun isReady(): Boolean = resolveSerial() != null

    override fun listAvailableModels(): List<String> = supportedModels.sorted()

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        if (!supportedModels.contains(modelId)) {
            return false
        }
        val serial = resolveSerial() ?: return false
        activeSerial = serial
        activeModelId = modelId
        return true
    }

    override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean {
        val serial = activeSerial ?: resolveSerial() ?: return false
        val model = activeModelId ?: return false

        val shellOutput = runCatching {
            commandRunner.run(
                listOf(
                    "adb",
                    "-s",
                    serial,
                    "shell",
                    "echo",
                    "ADB_LLAMACPP",
                    "model=$model",
                    "max_tokens=$maxTokens",
                ),
            )
        }.getOrElse { return false }
        if (shellOutput.exitCode != 0) {
            return false
        }

        val response = buildString {
            append(shellOutput.stdout.trim())
            append(" ")
            append("prompt_hash=")
            append(prompt.hashCode())
        }.trim()

        response
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { token -> onToken("$token ") }
        return true
    }

    override fun unloadModel() {
        activeModelId = null
    }

    override fun runtimeBackend(): RuntimeBackend {
        return if (isReady()) RuntimeBackend.ADB_FALLBACK else RuntimeBackend.UNAVAILABLE
    }

    private fun resolveSerial(): String? {
        val preferred = env["ADB_SERIAL"]?.trim()?.takeIf { it.isNotEmpty() }
        val result = runCatching {
            commandRunner.run(listOf("adb", "devices", "-l"))
        }.getOrElse { return null }
        if (result.exitCode != 0) {
            return null
        }

        val devices = result.stdout
            .lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> line.split(Regex("\\s+")) }
            .filter { cols -> cols.size >= 2 && cols[1] == "device" }
            .map { cols -> cols[0] }
            .toList()

        if (preferred != null) {
            return devices.firstOrNull { it == preferred }
        }
        return devices.singleOrNull()
    }
}
