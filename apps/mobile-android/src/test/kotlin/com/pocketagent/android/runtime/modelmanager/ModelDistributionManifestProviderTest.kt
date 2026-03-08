package com.pocketagent.android.runtime.modelmanager

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelDistributionManifestProviderTest {
    @Test
    fun `load manifest falls back to bundled catalog when endpoint is empty`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { bundledManifestJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(2, manifest.models.size)
        assertNotNull(manifest.syncedAtEpochMs)
        assertEquals(null, manifest.lastError)
    }

    @Test
    fun `load manifest overlays bundled with remote catalog`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { bundledManifestJson() },
            remoteManifestLoader = { remoteManifestJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED_AND_REMOTE, manifest.source)
        val model0 = manifest.models.first { it.modelId == "qwen3.5-0.8b-q4" }
        val model2 = manifest.models.first { it.modelId == "qwen3.5-2b-q4" }
        assertTrue(model0.versions.any { it.version == "q4_1" })
        assertTrue(model2.versions.any { it.version == "q4_0" })
        assertEquals(null, manifest.lastError)
    }

    @Test
    fun `load manifest keeps bundled catalog and warning when remote refresh fails`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { bundledManifestJson() },
            remoteManifestLoader = { error("network down") },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertTrue(manifest.models.isNotEmpty())
        assertTrue(manifest.lastError?.contains("network down") == true)
    }

    @Test
    fun `load manifest drops invalid catalog entries and reports warning`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithInvalidEntriesJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(1, manifest.models.size)
        val model = manifest.models.single()
        assertEquals("qwen3.5-0.8b-q4", model.modelId)
        assertEquals(1, model.versions.size)
        assertTrue(manifest.lastError?.contains("Dropped") == true)
    }

    @Test
    fun `load manifest accepts version when provenance fields are omitted`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithoutProvenanceJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(1, manifest.models.size)
        val version = manifest.models.single().versions.single()
        assertEquals("", version.provenanceIssuer)
        assertEquals("", version.provenanceSignature)
        assertEquals(null, manifest.lastError)
    }

    @Test
    fun `load manifest deduplicates repeated model-version entries`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = { manifestWithDuplicateEntriesJson() },
        )

        val manifest = provider.loadManifest()

        assertEquals(ManifestSource.BUNDLED, manifest.source)
        assertEquals(1, manifest.models.size)
        val model = manifest.models.single()
        assertEquals("qwen3.5-0.8b-q4", model.modelId)
        assertEquals(1, model.versions.size)
        assertTrue(manifest.lastError?.contains("duplicate") == true)
    }

    @Test
    fun `bundled catalog unavailable returns deterministic error code`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "",
            bundledManifestLoader = null,
        )

        val manifest = provider.loadManifest()

        assertTrue(manifest.models.isEmpty())
        assertTrue(manifest.lastError?.startsWith("MODEL_MANIFEST_BUNDLED_UNAVAILABLE:") == true)
    }

    @Test
    fun `remote refresh warning returns deterministic error code`() = runTest {
        val provider = ModelDistributionManifestProvider(
            context = null,
            endpointOverride = "https://example.test/catalog.json",
            bundledManifestLoader = { bundledManifestJson() },
            remoteManifestLoader = { error("network down") },
        )

        val manifest = provider.loadManifest()

        assertTrue(manifest.lastError?.contains("MODEL_MANIFEST_REMOTE_FETCH_FAILED:") == true)
    }
}

private fun bundledManifestJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_0",
                  "downloadUrl": "https://example.test/qwen-0.8b-q4_0.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                }
              ]
            },
            {
              "modelId": "qwen3.5-2b-q4",
              "displayName": "Qwen 3.5 2B (Q4)",
              "versions": [
                {
                  "version": "q4_0",
                  "downloadUrl": "https://example.test/qwen-2b-q4_0.gguf",
                  "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 222
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun remoteManifestJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_1",
                  "downloadUrl": "https://example.test/qwen-0.8b-q4_1.gguf",
                  "expectedSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "provenanceIssuer": "internal",
                  "provenanceSignature": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 333
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithInvalidEntriesJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_valid",
                  "downloadUrl": "https://example.test/valid.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                },
                {
                  "version": "q4_bad_url",
                  "downloadUrl": "http://example.test/invalid.gguf",
                  "expectedSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                },
                {
                  "version": "q4_bad_sha",
                  "downloadUrl": "https://example.test/invalid2.gguf",
                  "expectedSha256": "not-a-sha",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 111
                }
              ]
            },
            {
              "modelId": "qwen3.5-2b-q4",
              "displayName": "Qwen 3.5 2B (Q4)",
              "versions": [
                {
                  "version": "q4_missing_size",
                  "downloadUrl": "https://example.test/missing-size.gguf",
                  "expectedSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "runtimeCompatibility": "android-arm64-v8a"
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithoutProvenanceJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_0",
                  "downloadUrl": "https://example.test/qwen-0.8b-q4_0.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}

private fun manifestWithDuplicateEntriesJson(): String {
    return """
        {
          "models": [
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B (Q4)",
              "versions": [
                {
                  "version": "q4_0",
                  "downloadUrl": "https://example.test/dup-a.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            },
            {
              "modelId": "qwen3.5-0.8b-q4",
              "displayName": "Qwen 3.5 0.8B Duplicate Name",
              "versions": [
                {
                  "version": "q4_0",
                  "downloadUrl": "https://example.test/dup-b.gguf",
                  "expectedSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "runtimeCompatibility": "android-arm64-v8a",
                  "fileSizeBytes": 1234
                }
              ]
            }
          ]
        }
    """.trimIndent()
}
