package com.pocketagent.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketagent.android.runtime.ModelPathOrigin
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ManifestSource
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.RoutingMode
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.runtime.RuntimeLoadedModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelManagementSheetComposeContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun modelLibrarySheetShowsArtifactActionsWithoutRuntimeControls() {
        var openedRuntimeControls = false

        composeRule.setContent {
            MaterialTheme {
                ModelLibrarySheet(
                    state = sampleLibraryState(),
                    modelLoadingState = ModelLoadingState.Idle(),
                    onImportModel = {},
                    onDownloadVersion = {},
                    onPauseDownload = {},
                    onResumeDownload = {},
                    onRetryDownload = {},
                    onCancelDownload = {},
                    onActivateVersion = { _, _ -> },
                    onRemoveVersion = { _, _ -> },
                    onRefreshManifest = {},
                    onRefreshRuntime = {},
                    onRefreshAll = {},
                    onOpenRuntimeControls = { openedRuntimeControls = true },
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText("Model library").assertIsDisplayed()
        composeRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeRule.onNodeWithText("Installed versions").assertIsDisplayed()
        composeRule.onNodeWithTag("model_library_list")
            .performScrollToNode(hasText("Open runtime controls"))
        composeRule.onNodeWithText("Open runtime controls").performClick()
        composeRule.runOnIdle {
            assertTrue(openedRuntimeControls)
        }
        assertTrue(composeRule.onAllNodesWithText("Load now").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Offload").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun runtimeModelSheetShowsRuntimeActionsWithoutDownloadManager() {
        var openedLibrary = false

        composeRule.setContent {
            MaterialTheme {
                RuntimeModelSheet(
                    state = sampleRuntimeState(),
                    modelLoadingState = sampleRuntimeLoadingState(),
                    routingMode = RoutingMode.AUTO,
                    onLoadVersion = { _, _ -> },
                    onLoadLastUsedModel = {},
                    onOffloadModel = {},
                    onRefreshRuntime = {},
                    onOpenModelLibrary = { openedLibrary = true },
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText("Runtime model").assertIsDisplayed()
        composeRule.onNodeWithText("Active model").assertIsDisplayed()
        composeRule.onNodeWithText("Offload").assertIsDisplayed()
        composeRule.onNodeWithText("Open model library").performClick()
        composeRule.runOnIdle {
            assertTrue(openedLibrary)
        }
        assertTrue(composeRule.onAllNodesWithText("Downloads").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Start download").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun runtimeModelSheetShowsEmptyStateWhenNothingIsInstalled() {
        composeRule.setContent {
            MaterialTheme {
                RuntimeModelSheet(
                    state = sampleRuntimeState(
                        snapshot = sampleSnapshot(installed = false),
                        lifecycle = RuntimeModelLifecycleSnapshot.initial(),
                    ),
                    modelLoadingState = ModelLoadingState.Idle(),
                    routingMode = RoutingMode.AUTO,
                    onLoadVersion = { _, _ -> },
                    onLoadLastUsedModel = {},
                    onOffloadModel = {},
                    onRefreshRuntime = {},
                    onOpenModelLibrary = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText("No installed models yet").assertIsDisplayed()
        composeRule.onNodeWithText("Open model library").assertIsDisplayed()
    }
}

private fun sampleLibraryState(): ModelLibraryUiState {
    return ModelLibraryUiState(
        snapshot = sampleSnapshot(installed = true),
        manifest = sampleManifest(),
        downloads = listOf(sampleDownload()),
        isImporting = false,
        statusMessage = "Ready for provisioning actions",
        defaultGetReadyModelId = "qwen3.5-0.8b-q4",
        defaultModelVersion = sampleManifest().models.first().versions.first(),
    )
}

private fun sampleRuntimeState(
    snapshot: RuntimeProvisioningSnapshot = sampleSnapshot(installed = true),
    lifecycle: RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot(
        state = ModelLifecycleState.LOADED,
        loadedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "q4_0",
        ),
        lastUsedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "q4_0",
        ),
    ),
): RuntimeModelUiState {
    return RuntimeModelUiState(
        snapshot = snapshot,
        lifecycle = lifecycle,
        isImporting = false,
        statusMessage = "Runtime ready",
    )
}

private fun sampleRuntimeLoadingState(): ModelLoadingState {
    val loadedModel = RuntimeLoadedModel(
        modelId = "qwen3.5-0.8b-q4",
        modelVersion = "q4_0",
    )
    return ModelLoadingState.Loaded(
        model = loadedModel,
        lastUsedModel = loadedModel,
        readyAtEpochMs = 1L,
    )
}

private fun sampleSnapshot(installed: Boolean): RuntimeProvisioningSnapshot {
    val versions = if (installed) {
        listOf(
            ModelVersionDescriptor(
                modelId = "qwen3.5-0.8b-q4",
                version = "q4_0",
                displayName = "Qwen 3.5 0.8B (Q4)",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                provenanceIssuer = "",
                provenanceSignature = "",
                runtimeCompatibility = "android-arm64-v8a",
                fileSizeBytes = 1024L,
                importedAtEpochMs = 1L,
                isActive = true,
            ),
        )
    } else {
        emptyList()
    }
    return RuntimeProvisioningSnapshot(
        models = listOf(
            ProvisionedModelState(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen 3.5 0.8B (Q4)",
                fileName = "qwen.gguf",
                absolutePath = if (installed) "/tmp/qwen.gguf" else null,
                sha256 = if (installed) "a".repeat(64) else null,
                importedAtEpochMs = if (installed) 1L else null,
                activeVersion = if (installed) "q4_0" else null,
                installedVersions = versions,
                pathOrigin = ModelPathOrigin.MANAGED,
            ),
        ),
        storageSummary = StorageSummary(
            totalBytes = 8L * 1024L * 1024L * 1024L,
            freeBytes = 4L * 1024L * 1024L * 1024L,
            usedByModelsBytes = if (installed) 2L * 1024L * 1024L * 1024L else 0L,
            tempDownloadBytes = 512L * 1024L * 1024L,
        ),
        requiredModelIds = setOf("qwen3.5-0.8b-q4"),
    )
}

private fun sampleManifest(): ModelDistributionManifest {
    return ModelDistributionManifest(
        models = listOf(
            ModelDistributionModel(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen 3.5 0.8B (Q4)",
                versions = listOf(
                    ModelDistributionVersion(
                        modelId = "qwen3.5-0.8b-q4",
                        version = "q4_0",
                        downloadUrl = "https://example.test/qwen.gguf",
                        expectedSha256 = "a".repeat(64),
                        provenanceIssuer = "",
                        provenanceSignature = "",
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 2L * 1024L * 1024L * 1024L,
                        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
                    ),
                ),
            ),
        ),
        source = ManifestSource.BUNDLED,
        syncedAtEpochMs = 1L,
    )
}

private fun sampleDownload(): DownloadTaskState {
    return DownloadTaskState(
        taskId = "task-1",
        modelId = "qwen3.5-0.8b-q4",
        version = "q4_0",
        downloadUrl = "https://example.test/qwen.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "",
        provenanceSignature = "",
        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        runtimeCompatibility = "android-arm64-v8a",
        processingStage = DownloadProcessingStage.DOWNLOADING,
        status = DownloadTaskStatus.DOWNLOADING,
        progressBytes = 512L,
        totalBytes = 1024L,
        updatedAtEpochMs = 1L,
        message = "Downloading",
    )
}
