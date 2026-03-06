package com.pocketagent.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.ModelCatalog
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealRuntimeProvisioningInstrumentationTest {
    @Test
    fun seedModelsAndVerifyStartupChecks() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping real-runtime provisioning sanity lane. Set stage2_enable_provisioning_test=true to run.",
            parseBooleanArg(args, ARG_ENABLE_PROVISIONING_TEST, defaultValue = false),
        )
        val modelPath0_8bRaw = args.getString(ARG_MODEL_PATH_0_8B)?.trim().orEmpty()
        val modelPath2bRaw = args.getString(ARG_MODEL_PATH_2B)?.trim().orEmpty()
        assumeTrue(
            "Skipping real-runtime provisioning sanity lane. Provide both model paths via instrumentation arguments.",
            modelPath0_8bRaw.isNotEmpty() && modelPath2bRaw.isNotEmpty(),
        )
        val modelPath0_8b = requireFile(modelPath0_8bRaw)
        val modelPath2b = requireFile(modelPath2bRaw)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        AppRuntimeDependencies.resetRuntimeFacadeFactoryForTests()
        val (seeded0, seeded2b) = runCatching {
            val seeded0 = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                absolutePath = modelPath0_8b,
            )
            val seeded2b = AppRuntimeDependencies.seedModelFromAbsolutePath(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                absolutePath = modelPath2b,
            )
            seeded0 to seeded2b
        }.getOrThrow()

        assertTrue(
            "Failed to activate seeded 0.8B version ${seeded0.version}.",
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                version = seeded0.version,
            ),
        )
        assertTrue(
            "Failed to activate seeded 2B version ${seeded2b.version}.",
            AppRuntimeDependencies.setActiveVersion(
                context = appContext,
                modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                version = seeded2b.version,
            ),
        )

        val snapshot = AppRuntimeDependencies.currentProvisioningSnapshot(appContext)
        val state0 = snapshot.models.firstOrNull { it.modelId == ModelCatalog.QWEN_3_5_0_8B_Q4 }
        val state2 = snapshot.models.firstOrNull { it.modelId == ModelCatalog.QWEN_3_5_2B_Q4 }
        assertNotNull("Missing 0.8B model state after provisioning.", state0)
        assertNotNull("Missing 2B model state after provisioning.", state2)

        requireNotNull(state0)
        requireNotNull(state2)
        assertEquals(seeded0.version, state0.activeVersion)
        assertEquals(seeded2b.version, state2.activeVersion)
        assertEquals(normalizePath(modelPath0_8b), normalizePath(state0.absolutePath.orEmpty()))
        assertEquals(normalizePath(modelPath2b), normalizePath(state2.absolutePath.orEmpty()))
        assertEquals(
            "Expected one canonical entry for seeded 0.8B path.",
            1,
            state0.installedVersions.count { normalizePath(it.absolutePath) == normalizePath(modelPath0_8b) },
        )
        assertEquals(
            "Expected one canonical entry for seeded 2B path.",
            1,
            state2.installedVersions.count { normalizePath(it.absolutePath) == normalizePath(modelPath2b) },
        )

        AppRuntimeDependencies.installProductionRuntime(appContext)
        val facade = AppRuntimeDependencies.runtimeFacadeFactory()
        val startupChecks = facade.runStartupChecks()
        assertTrue(
            "Startup checks failed after provisioning: ${startupChecks.joinToString()}",
            startupChecks.isEmpty(),
        )
        assertEquals("NATIVE_JNI", facade.runtimeBackend())
    }

    private fun requireFile(value: String): String {
        val file = java.io.File(value)
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
        private const val ARG_ENABLE_PROVISIONING_TEST = "stage2_enable_provisioning_test"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_2B = "stage2_model_2b_path"
    }
}
