package com.pocketagent.android

import com.pocketagent.inference.ModelCatalog
import java.io.ByteArrayOutputStream

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface CommandRunner {
    fun run(command: List<String>): CommandResult
}

class AdbDeviceLlamaCppRuntimeBridge(
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

    override fun loadModel(modelId: String): Boolean {
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

        val shellOutput = commandRunner.run(
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

    private fun resolveSerial(): String? {
        val preferred = env["ADB_SERIAL"]?.trim()?.takeIf { it.isNotEmpty() }
        val result = commandRunner.run(listOf("adb", "devices", "-l"))
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

private class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>): CommandResult {
        val process = ProcessBuilder(command).start()
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        process.inputStream.copyTo(stdoutBytes)
        process.errorStream.copyTo(stderrBytes)
        val exitCode = process.waitFor()
        return CommandResult(
            exitCode = exitCode,
            stdout = stdoutBytes.toString(Charsets.UTF_8.name()),
            stderr = stderrBytes.toString(Charsets.UTF_8.name()),
        )
    }
}
