package com.pocketagent.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.core.SessionId
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.DeviceState
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.StreamUserMessageRequest
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealRuntimeJourneyInstrumentationTest {
    @Test
    fun runCoreJourneyGate() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping real-runtime journey lane. Set stage2_enable_journey_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_JOURNEY_TEST, defaultValue = false),
        )
        val modelPath0_8bRaw = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        val modelPath2bRaw = args.getString(ARG_MODEL_PATH_2B)?.trim().orEmpty()
        assumeTrue(
            "Skipping real-runtime journey lane. Provide both model paths via instrumentation arguments.",
            modelPath0_8bRaw.isNotEmpty() && modelPath2bRaw.isNotEmpty(),
        )
        val modelPath0_8b = requireFile(modelPath0_8bRaw)
        val modelPath2b = requireFile(modelPath2bRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val journeyArtifactDir = args.getString(ARG_JOURNEY_ARTIFACT_DIR)?.trim().orEmpty()
        val replyTimeoutSeconds = args.getString(ARG_REPLY_TIMEOUT_SECONDS)?.toLongOrNull()?.takeIf { it > 0L } ?: 90L
        val replyTimeoutMs = replyTimeoutSeconds * 1_000L
        val traceLines = mutableListOf<String>()

        fun trace(step: String, detail: String) {
            traceLines += "$step|$detail"
        }

        try {
            AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
            val seeded0 = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                absolutePath = modelPath0_8b,
            )
            trace("provision", "seeded ${ModelCatalog.QWEN_3_5_0_8B_Q4}")

            val seeded2 = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                absolutePath = modelPath2b,
            )
            trace("provision", "seeded ${ModelCatalog.QWEN_3_5_2B_Q4}")

            assertTrue(
                "Failed to activate seeded 0.8B version ${seeded0.version}.",
                AppRuntimeDependencies.setActiveVersion(
                    context = appContext,
                    modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                    version = seeded0.version,
                ),
            )
            assertTrue(
                "Failed to activate seeded 2B version ${seeded2.version}.",
                AppRuntimeDependencies.setActiveVersion(
                    context = appContext,
                    modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                    version = seeded2.version,
                ),
            )
            val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
            val state0 = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
            val state2 = snapshot.models.first { it.modelId == ModelCatalog.QWEN_3_5_2B_Q4 }
            assertEquals(seeded0.version, state0.activeVersion)
            assertEquals(seeded2.version, state2.activeVersion)
            assertEquals(normalizePath(modelPath0_8b), normalizePath(state0.absolutePath.orEmpty()))
            assertEquals(normalizePath(modelPath2b), normalizePath(state2.absolutePath.orEmpty()))
            trace("provision", "active_0_8b=${state0.activeVersion} active_2b=${state2.activeVersion}")

            AppRuntimeDependencies.installProductionRuntime(appContext)
            val facade = AppRuntimeDependencies.runtimeFacadeFactory()

            val startupChecks = facade.runStartupChecks()
            trace("startup", startupChecks.joinToString("; ").ifBlank { "ok" })
            assertTrue(
                "Real-runtime startup checks failed: ${startupChecks.joinToString()}",
                startupChecks.isEmpty(),
            )
            assertEquals("NATIVE_JNI", facade.runtimeBackend())

            val sessionId = facade.createSession()
            trace("session", "created ${sessionId.value}")
            assertTrue(sessionId.value.isNotBlank())

            facade.setRoutingMode(RoutingMode.QWEN_2B)
            trace("routing", "set ${RoutingMode.QWEN_2B}")
            assertEquals(RoutingMode.QWEN_2B, facade.getRoutingMode())

            val toolResult = facade.runTool(
                toolName = "calculator",
                jsonArgs = """{"expression":"4*9"}""",
            )
            trace("tool", toolResult.take(80))
            assertTrue(toolResult.isNotBlank())

            val diagnostics = facade.exportDiagnostics()
            trace("diagnostics", diagnostics.take(80))
            assertTrue(diagnostics.isNotBlank())

            val enableStreamingProbe = parseBooleanArg(args, ARG_ENABLE_STREAMING_PROBE, defaultValue = false)
            if (enableStreamingProbe) {
                var firstTokenLatencyMs: Long? = null
                var completionLatencyMs: Long? = null
                var completionText = ""
                var completionModelId: String? = null
                val sendStart = System.currentTimeMillis()

                withTimeout(replyTimeoutMs) {
                    facade.streamUserMessage(
                        StreamUserMessageRequest(
                            sessionId = SessionId(sessionId.value),
                            userText = "ola, how you doin",
                            taskType = "short_text",
                            deviceState = DeviceState(
                                batteryPercent = 72,
                                thermalLevel = 3,
                                ramClassGb = 8,
                            ),
                            maxTokens = 128,
                            requestTimeoutMs = replyTimeoutMs,
                        ),
                    ).collect { event ->
                        when (event) {
                            is ChatStreamEvent.TokenDelta -> {
                                if (firstTokenLatencyMs == null && event.accumulatedText.isNotBlank()) {
                                    firstTokenLatencyMs = System.currentTimeMillis() - sendStart
                                    trace("first_token", "latency_ms=$firstTokenLatencyMs")
                                }
                            }

                            is ChatStreamEvent.Completed -> {
                                completionLatencyMs = System.currentTimeMillis() - sendStart
                                completionText = event.response.text
                                completionModelId = event.response.modelId
                                trace("completed", "latency_ms=$completionLatencyMs")
                            }
                            else -> Unit
                        }
                    }
                }

                assertNotNull("First token did not arrive within ${replyTimeoutMs}ms.", firstTokenLatencyMs)
                assertNotNull("Completion did not arrive within ${replyTimeoutMs}ms.", completionLatencyMs)
                assertTrue("Completion text is blank.", completionText.isNotBlank())
                assertEquals(
                    "Journey probe should execute with the explicitly selected 2B model.",
                    ModelCatalog.QWEN_3_5_2B_Q4,
                    completionModelId,
                )
            } else {
                trace("send", "streaming probe disabled")
            }

            // Session continuity surface: restore turns and ensure delete path is healthy.
            facade.restoreSession(
                sessionId = sessionId,
                turns = listOf(
                    Turn(role = "user", content = "remember scenario c context", timestampEpochMs = 1L),
                    Turn(role = "assistant", content = "ack", timestampEpochMs = 2L),
                ),
            )
            trace("continuity", "restored turns into ${sessionId.value}")
            assertTrue(facade.deleteSession(sessionId))
            trace("session", "deleted ${sessionId.value}")
        } finally {
            if (journeyArtifactDir.isNotBlank()) {
                val outDir = File(journeyArtifactDir).apply { mkdirs() }
                File(outDir, "journey-context.txt").writeText(
                    traceLines.joinToString(separator = "\n", postfix = "\n"),
                )
            }
        }
    }

    private fun requireFile(value: String): String {
        val file = File(value)
        require(file.exists() && file.isFile) { "Model path does not exist: $value" }
        return file.absolutePath
    }

    private fun normalizePath(value: String): String {
        val canonical = runCatching { File(value).canonicalPath }.getOrElse { File(value).absolutePath }
        return canonical
            .replace("/sdcard/", "/storage/emulated/0/")
            .replace("/storage/self/primary/", "/storage/emulated/0/")
    }

    private fun parseBooleanArg(
        args: android.os.Bundle,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val raw = args.getString(key)?.trim()?.lowercase() ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    companion object {
        private const val ARG_ENABLE_JOURNEY_TEST = "stage2_enable_journey_test"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_2B = "stage2_model_2b_path"
        private const val ARG_JOURNEY_ARTIFACT_DIR = "journey_artifact_dir"
        private const val ARG_REPLY_TIMEOUT_SECONDS = "journey_reply_timeout_seconds"
        private const val ARG_ENABLE_STREAMING_PROBE = "journey_enable_streaming_probe"
    }
}
