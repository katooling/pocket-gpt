package com.pocketagent.android.ui

import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelLibraryUxDecisionsTest {
    @Test
    fun `default badge is reserved for active product default model`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.DEFAULT, badge)
    }

    @Test
    fun `active non default version shows active badge instead of default`() {
        val model = provisionedModel(
            modelId = "phi-4-mini-instruct-q4_k_m",
            activeVersion = "q4_k_m",
            installedVersions = listOf(versionDescriptor(modelId = "phi-4-mini-instruct-q4_k_m", version = "q4_k_m", isActive = true)),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = null,
            loadedModel = null,
        )

        assertEquals(DownloadedModelBadge.ACTIVE, badge)
    }

    @Test
    fun `loaded badge wins over default and active badges`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val badge = resolveDownloadedModelBadge(
            model = model,
            version = model.installedVersions.first(),
            defaultGetReadyModelId = "qwen3.5-0.8b-q4",
            activeModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
        )

        assertEquals(DownloadedModelBadge.LOADED, badge)
    }

    @Test
    fun `removing only installed active version clears active selection`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertTrue(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresOffload)
    }

    @Test
    fun `removing loaded only installed active version offloads first and clears active selection`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true)),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = RuntimeLoadedModel(modelId = "qwen3.5-0.8b-q4", modelVersion = "q4_0"),
        )

        assertTrue(plan.requiresOffload)
        assertTrue(plan.requiresClearingActiveSelection)
        assertFalse(plan.isBlockedByActiveSelection)
    }

    @Test
    fun `removing active version with alternatives stays blocked`() {
        val model = provisionedModel(
            modelId = "qwen3.5-0.8b-q4",
            activeVersion = "q4_0",
            installedVersions = listOf(
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_0", isActive = true),
                versionDescriptor(modelId = "qwen3.5-0.8b-q4", version = "q4_k_m", isActive = false),
            ),
        )

        val plan = resolveRemoveVersionPlan(
            model = model,
            version = model.installedVersions.first(),
            loadedModel = null,
        )

        assertTrue(plan.isBlockedByActiveSelection)
        assertFalse(plan.requiresClearingActiveSelection)
    }
}

private fun provisionedModel(
    modelId: String,
    activeVersion: String?,
    installedVersions: List<ModelVersionDescriptor>,
): ProvisionedModelState {
    return ProvisionedModelState(
        modelId = modelId,
        displayName = modelId,
        fileName = "$modelId.gguf",
        absolutePath = installedVersions.firstOrNull()?.absolutePath,
        sha256 = installedVersions.firstOrNull()?.sha256,
        importedAtEpochMs = 1L,
        activeVersion = activeVersion,
        installedVersions = installedVersions,
    )
}

private fun versionDescriptor(
    modelId: String,
    version: String,
    isActive: Boolean,
): ModelVersionDescriptor {
    return ModelVersionDescriptor(
        modelId = modelId,
        version = version,
        displayName = "$modelId $version",
        absolutePath = "/tmp/$modelId-$version.gguf",
        sha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 1L,
        importedAtEpochMs = 1L,
        isActive = isActive,
    )
}
