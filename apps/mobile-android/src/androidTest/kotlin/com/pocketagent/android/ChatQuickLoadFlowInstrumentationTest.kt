package com.pocketagent.android

import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.android.ui.ModelProvisioningViewModel
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.PocketAgentTheme
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatQuickLoadFlowInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatNotLoadedQuickLoadLastUsedThenSendCompletes() {
        val harness = QuickLoadFlowHarness()
        val runtimeGateway = QuickLoadRuntimeGateway(harness)
        val provisioningGateway = QuickLoadProvisioningGateway(harness)
        val viewModel = ChatViewModel(
            runtimeFacade = runtimeGateway,
            sessionPersistence = InMemorySessionPersistence(
                initialState = PersistedChatState(
                    onboardingCompleted = true,
                    advancedUnlocked = true,
                ),
            ),
        )
        val provisioningViewModel = ModelProvisioningViewModel(gateway = provisioningGateway)

        composeRule.setContent {
            PocketAgentTheme {
                Surface {
                    PocketAgentApp(
                        viewModel = viewModel,
                        provisioningViewModel = provisioningViewModel,
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Runtime: Not ready").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Runtime: Not ready").onFirst().assertIsDisplayed()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Load last used", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Load last used", substring = true).onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Runtime: Ready").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Runtime: Ready").onFirst().assertIsDisplayed()

        composeRule.onNodeWithTag("composer_input").performTextInput("quick load prompt")
        composeRule.onNodeWithTag("send_button").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText("runtime response for quick load prompt")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onAllNodesWithText("runtime response for quick load prompt").onFirst().assertIsDisplayed()
    }
}

private data class QuickLoadFlowHarness(
    val modelId: String = "qwen3.5-0.8b-q4",
    val modelVersion: String = "v1",
    var loaded: Boolean = false,
)

private class QuickLoadRuntimeGateway(
    private val harness: QuickLoadFlowHarness,
) : com.pocketagent.android.runtime.RuntimeGateway {
    private var sessionCounter = 0
    private var mode: RoutingMode = RoutingMode.AUTO

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("session-$sessionCounter")
    }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = flow {
        emit(
            ChatStreamEvent.Started(
                requestId = request.requestId,
                startedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        if (!harness.loaded) {
            emit(
                ChatStreamEvent.Failed(
                    requestId = request.requestId,
                    errorCode = "MODEL_NOT_LOADED",
                    message = "Missing runtime model",
                    firstTokenMs = null,
                    completionMs = 8L,
                ),
            )
            return@flow
        }
        val prompt = request.messages
            .asReversed()
            .firstOrNull { it.role == com.pocketagent.runtime.InteractionRole.USER }
            ?.parts
            ?.joinToString(separator = "\n") { part ->
                when (part) {
                    is com.pocketagent.runtime.InteractionContentPart.Text -> part.text
                }
            }
            .orEmpty()
        emit(
            ChatStreamEvent.Completed(
                requestId = request.requestId,
                response = ChatResponse(
                    sessionId = request.sessionId,
                    modelId = harness.modelId,
                    text = "runtime response for $prompt",
                    firstTokenLatencyMs = 20,
                    totalLatencyMs = 45,
                ),
                finishReason = "completed",
                firstTokenMs = 20,
                completionMs = 45,
            ),
        )
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        return ToolExecutionResult.Success(content = "tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return ImageAnalysisResult.Success(content = "image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        this.mode = mode
    }

    override fun getRoutingMode(): RoutingMode = mode

    override fun runStartupChecks(): List<String> {
        return if (harness.loaded) emptyList() else listOf("missing runtime model: ${harness.modelId}")
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = "FAKE"

    override fun supportsGpuOffload(): Boolean = false
}

private class QuickLoadProvisioningGateway(
    private val harness: QuickLoadFlowHarness,
) : ProvisioningGateway {
    private val downloads = MutableStateFlow<List<com.pocketagent.android.runtime.modelmanager.DownloadTaskState>>(emptyList())
    private val lifecycle = MutableStateFlow(
        RuntimeModelLifecycleSnapshot.initial().copy(
            state = ModelLifecycleState.UNLOADED,
            loadedModel = null,
            lastUsedModel = RuntimeLoadedModel(
                modelId = harness.modelId,
                modelVersion = harness.modelVersion,
            ),
        ),
    )

    override fun currentSnapshot(): RuntimeProvisioningSnapshot = sampleSnapshot(harness)

    override fun observeDownloads(): StateFlow<List<com.pocketagent.android.runtime.modelmanager.DownloadTaskState>> = downloads

    override fun observeModelLifecycle(): StateFlow<RuntimeModelLifecycleSnapshot> = lifecycle

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot = lifecycle.value

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        return RuntimeModelImportResult(
            modelId = modelId,
            version = harness.modelVersion,
            absolutePath = "/tmp/$modelId.gguf",
            sha256 = "a".repeat(64),
            copiedBytes = 128L,
            isActive = true,
        )
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return ModelDistributionManifest(models = emptyList())
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return sampleSnapshot(harness).models.firstOrNull { it.modelId == modelId }?.installedVersions.orEmpty()
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean = true

    override fun removeVersion(modelId: String, version: String): Boolean = true

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        val loadedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        harness.loaded = true
        lifecycle.value = lifecycle.value.copy(
            state = ModelLifecycleState.LOADED,
            loadedModel = loadedModel,
            requestedModel = null,
            lastUsedModel = loadedModel,
            errorCode = null,
            errorDetail = null,
        )
        return RuntimeModelLifecycleCommandResult.applied(loadedModel = loadedModel)
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        val lastUsed = lifecycle.value.lastUsedModel ?: return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            detail = "last_loaded_model_missing",
        )
        return loadInstalledModel(
            modelId = lastUsed.modelId,
            version = lastUsed.modelVersion.orEmpty(),
        )
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        harness.loaded = false
        lifecycle.value = lifecycle.value.copy(
            state = ModelLifecycleState.UNLOADED,
            loadedModel = null,
            requestedModel = null,
            errorCode = null,
            errorDetail = null,
            queuedOffload = false,
        )
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override fun enqueueDownload(version: ModelDistributionVersion): String = "task-1"

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) = Unit
}

private class InMemorySessionPersistence(
    initialState: PersistedChatState = PersistedChatState(),
) : SessionPersistence {
    private var current = initialState

    override fun loadState(): PersistedChatState = current

    override fun saveState(state: PersistedChatState) {
        current = state
    }
}

private fun sampleSnapshot(harness: QuickLoadFlowHarness): RuntimeProvisioningSnapshot {
    return RuntimeProvisioningSnapshot(
        models = listOf(
            ProvisionedModelState(
                modelId = harness.modelId,
                displayName = "Qwen",
                fileName = "qwen.gguf",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                importedAtEpochMs = 1L,
                activeVersion = harness.modelVersion,
                installedVersions = listOf(
                    ModelVersionDescriptor(
                        modelId = harness.modelId,
                        version = harness.modelVersion,
                        displayName = "Qwen",
                        absolutePath = "/tmp/qwen.gguf",
                        sha256 = "a".repeat(64),
                        provenanceIssuer = "issuer",
                        provenanceSignature = "sig",
                        runtimeCompatibility = "android-arm64-v8a",
                        fileSizeBytes = 123L,
                        importedAtEpochMs = 1L,
                        isActive = true,
                    ),
                ),
            ),
        ),
        storageSummary = StorageSummary(
            totalBytes = 1_000L,
            freeBytes = 500L,
            usedByModelsBytes = 250L,
            tempDownloadBytes = 0L,
        ),
        requiredModelIds = setOf(harness.modelId),
    )
}
