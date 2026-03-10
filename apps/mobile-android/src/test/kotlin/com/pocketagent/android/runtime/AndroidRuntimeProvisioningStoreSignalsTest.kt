package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidRuntimeProvisioningStoreSignalsTest {
    @Test
    fun `staleActiveVersionSignal returns signal when active version is missing`() {
        val signal = staleActiveVersionSignal(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_missing",
            installedVersions = listOf(
                descriptor(version = "q4_0"),
                descriptor(version = "q4_1"),
            ),
        )

        assertEquals("MODEL_ACTIVE_VERSION_STALE", signal?.code)
    }

    @Test
    fun `staleActiveVersionSignal returns null when active version resolves`() {
        val signal = staleActiveVersionSignal(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                descriptor(version = "q4_0"),
            ),
        )

        assertNull(signal)
    }

    private fun descriptor(version: String): ModelVersionDescriptor {
        return ModelVersionDescriptor(
            modelId = "qwen3.5-0.8b-q4",
            version = version,
            displayName = "Qwen 3.5 0.8B",
            absolutePath = "/tmp/$version.gguf",
            sha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 1L,
            importedAtEpochMs = 1L,
            isActive = false,
        )
    }
}
