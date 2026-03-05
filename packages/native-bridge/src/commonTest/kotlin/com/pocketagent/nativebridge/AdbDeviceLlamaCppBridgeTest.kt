package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbDeviceLlamaCppBridgeTest {
    @Test
    fun `is ready and generates tokens when one adb device is attached`() {
        val runner = ScriptedCommandRunner(
            mapOf(
                "adb devices -l" to CommandResult(
                    exitCode = 0,
                    stdout = "List of devices attached\nSER123 device product:a model:b device:c\n",
                    stderr = "",
                ),
                "adb -s SER123 shell echo ADB_LLAMACPP model=${ModelCatalog.QWEN_3_5_0_8B_Q4} max_tokens=64 cache_policy=OFF" to CommandResult(
                    exitCode = 0,
                    stdout = "ADB_LLAMACPP model=${ModelCatalog.QWEN_3_5_0_8B_Q4} max_tokens=64 cache_policy=OFF\n",
                    stderr = "",
                ),
            ),
        )
        val bridge = AdbDeviceLlamaCppBridge(commandRunner = runner)

        assertTrue(bridge.isReady())
        assertEquals(RuntimeBackend.ADB_FALLBACK, bridge.runtimeBackend())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        val tokens = mutableListOf<String>()
        assertTrue(bridge.generate(prompt = "hello", maxTokens = 64) { tokens.add(it) })
        assertTrue(tokens.isNotEmpty())
    }

    @Test
    fun `load fails when no adb devices are attached`() {
        val runner = ScriptedCommandRunner(
            mapOf(
                "adb devices -l" to CommandResult(
                    exitCode = 0,
                    stdout = "List of devices attached\n\n",
                    stderr = "",
                ),
            ),
        )
        val bridge = AdbDeviceLlamaCppBridge(commandRunner = runner)

        assertFalse(bridge.isReady())
        assertEquals(RuntimeBackend.UNAVAILABLE, bridge.runtimeBackend())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
    }

    @Test
    fun `command execution failure degrades to unavailable without crash`() {
        val bridge = AdbDeviceLlamaCppBridge(commandRunner = ThrowingCommandRunner())

        assertFalse(bridge.isReady())
        assertEquals(RuntimeBackend.UNAVAILABLE, bridge.runtimeBackend())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
    }
}

private class ScriptedCommandRunner(
    private val responses: Map<String, CommandResult>,
) : CommandRunner {
    override fun run(command: List<String>): CommandResult {
        val key = command.joinToString(" ")
        return responses[key] ?: CommandResult(
            exitCode = 1,
            stdout = "",
            stderr = "Unexpected command: $key",
        )
    }
}

private class ThrowingCommandRunner : CommandRunner {
    override fun run(command: List<String>): CommandResult {
        error("simulated command runner failure")
    }
}
