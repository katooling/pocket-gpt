package com.pocketagent.nativebridge

import java.io.ByteArrayOutputStream

enum class RuntimeBackend {
    NATIVE_JNI,
    ADB_FALLBACK,
    UNAVAILABLE,
}

interface LlamaCppRuntimeBridge {
    fun isReady(): Boolean
    fun listAvailableModels(): List<String>
    fun loadModel(modelId: String): Boolean = loadModel(modelId, null)
    fun loadModel(modelId: String, modelPath: String?): Boolean
    fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean
    fun unloadModel()
    fun runtimeBackend(): RuntimeBackend
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
            process.inputStream.copyTo(stdoutBytes)
            process.errorStream.copyTo(stderrBytes)
            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                stdout = stdoutBytes.toString(Charsets.UTF_8.name()),
                stderr = stderrBytes.toString(Charsets.UTF_8.name()),
            )
        }.getOrElse { error ->
            CommandResult(
                exitCode = 127,
                stdout = "",
                stderr = error.message ?: "Command execution failed.",
            )
        }
    }
}
