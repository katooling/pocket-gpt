package com.pocketagent.android

import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppDependenciesRemovalGuardTest {
    @Test
    fun `loaded version guard prefers explicit loaded model version`() {
        val guardedVersion = loadedVersionForRemovalGuard(
            loadedModel = RuntimeLoadedModel(
                modelId = "qwen3.5-0.8b-q4",
                modelVersion = "q4_k_m",
            ),
            installedVersions = listOf(
                descriptor(version = "q4_0", isActive = true),
                descriptor(version = "q4_k_m", isActive = false),
            ),
        )

        assertEquals("q4_k_m", guardedVersion)
    }

    @Test
    fun `loaded version guard falls back to active installed version when loaded version is unknown`() {
        val guardedVersion = loadedVersionForRemovalGuard(
            loadedModel = RuntimeLoadedModel(
                modelId = "qwen3.5-0.8b-q4",
                modelVersion = null,
            ),
            installedVersions = listOf(
                descriptor(version = "q4_0", isActive = true),
                descriptor(version = "q4_k_m", isActive = false),
            ),
        )

        assertEquals("q4_0", guardedVersion)
    }

    @Test
    fun `loaded version guard stays null when nothing is loaded`() {
        val guardedVersion = loadedVersionForRemovalGuard(
            loadedModel = null,
            installedVersions = listOf(
                descriptor(version = "q4_0", isActive = true),
            ),
        )

        assertNull(guardedVersion)
    }
}

private fun descriptor(version: String, isActive: Boolean): ModelVersionDescriptor {
    return ModelVersionDescriptor(
        modelId = "qwen3.5-0.8b-q4",
        version = version,
        displayName = "Qwen",
        absolutePath = "/tmp/$version.gguf",
        sha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 1L,
        importedAtEpochMs = 1L,
        isActive = isActive,
    )
}
