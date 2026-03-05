package com.pocketagent.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.StreamUserMessageRequest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealRuntimeAppPathInstrumentationTest {
    @Test
    fun appPathNativeRuntimeFlowProducesResponse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping real-runtime app-path lane. Set stage2_enable_app_path_test=true for RC app-path validation.",
            parseBooleanArg(args, ARG_ENABLE_APP_PATH_TEST, defaultValue = false),
        )
        val modelPath0_8bRaw = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        val modelPath2bRaw = args.getString(ARG_MODEL_PATH_2B)?.trim().orEmpty()
        assumeTrue(
            "Skipping real-runtime app-path lane. Provide both model paths via instrumentation arguments.",
            modelPath0_8bRaw.isNotEmpty() && modelPath2bRaw.isNotEmpty(),
        )
        val modelPath0_8b = requireFile(modelPath0_8bRaw)
        val modelPath2b = requireFile(modelPath2bRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
        AppRuntimeDependencies.seedModelFromAbsolutePath(
            context = appContext,
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = modelPath0_8b,
        )
        AppRuntimeDependencies.seedModelFromAbsolutePath(
            context = appContext,
            modelId = ModelCatalog.QWEN_3_5_2B_Q4,
            absolutePath = modelPath2b,
        )
        AppRuntimeDependencies.installProductionRuntime(appContext)

        val facade = AppRuntimeDependencies.runtimeFacadeFactory()
        val startupChecks = facade.runStartupChecks()
        assertTrue(
            "App-path startup checks failed: ${startupChecks.joinToString()}",
            startupChecks.isEmpty(),
        )
        assertEquals("NATIVE_JNI", facade.runtimeBackend())

        val sessionId = facade.createSession()
        var completed: ChatStreamEvent.Completed? = null
        facade.streamUserMessage(
            StreamUserMessageRequest(
                sessionId = sessionId,
                userText = "Give one short sentence proving app-path native inference.",
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8),
                maxTokens = 64,
            ),
        ).collect { event ->
            if (event is ChatStreamEvent.Completed) {
                completed = event
            }
        }

        val response = completed?.response
        assertNotNull("Expected completed chat response from real runtime lane.", response)
        assertTrue(response?.text?.isNotBlank() == true)
    }

    private fun requireFile(value: String): String {
        val file = java.io.File(value)
        require(file.exists() && file.isFile) { "Model path does not exist: $value" }
        return file.absolutePath
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
        private const val ARG_ENABLE_APP_PATH_TEST = "stage2_enable_app_path_test"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_2B = "stage2_model_2b_path"
    }
}
