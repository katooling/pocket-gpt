package com.pocketagent.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.ModelCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeInstrumentationSmokeTest {
    @Test
    fun appLaunchesMainActivity() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(appContext.packageName.contains("com.pocketagent.android"))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.localClassName.contains("MainActivity"))
            }
        }
    }

    @Test
    fun runtimeDependenciesDefaultToFacadeFactory() {
        val facade = AppRuntimeDependencies.runtimeFacadeFactory()
        assertTrue(facade.javaClass.simpleName.contains("DefaultMvpRuntimeFacade"))
    }

    @Test
    fun runtimeBridgeExposesSupportedModelCatalogViaFallback() {
        val bridge = AndroidLlamaCppRuntimeBridge(
            nativeApi = AlwaysUnavailableNativeApi(),
            libraryLoader = { _ -> error("native unavailable in instrumentation smoke") },
            fallbackBridge = AlwaysReadyFallbackBridge(),
        )

        assertTrue(bridge.isReady())
        val models = bridge.listAvailableModels()
        assertTrue(models.contains(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertTrue(models.contains(ModelCatalog.QWEN_3_5_2B_Q4))
    }
}

private class AlwaysUnavailableNativeApi : AndroidLlamaCppRuntimeBridge.NativeApi {
    override fun initialize(): Boolean = false

    override fun loadModel(modelId: String): Boolean = false

    override fun generate(prompt: String, maxTokens: Int): String = ""

    override fun unloadModel() {
        // no-op
    }
}

private class AlwaysReadyFallbackBridge : LlamaCppRuntimeBridge {
    override fun isReady(): Boolean = true

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    )

    override fun loadModel(modelId: String): Boolean = true

    override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean {
        onToken("smoke ")
        return true
    }

    override fun unloadModel() {
        // no-op
    }

    override fun runtimeBackend(): RuntimeBackend = RuntimeBackend.ADB_FALLBACK
}
