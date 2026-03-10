package com.pocketagent.android.ui

import android.net.Uri
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.RuntimeDomainError
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.runtime.RuntimeErrorCodes
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.android.testutil.fakeUri
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelProvisioningViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads snapshot and observes download flow updates`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals("qwen3.5-0.8b-q4", viewModel.uiState.value.snapshot?.models?.firstOrNull()?.modelId)
        assertEquals(0, viewModel.uiState.value.downloads.size)

        gateway.downloads.value = listOf(sampleDownloadTask())
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.downloads.size)
    }

    @Test
    fun `import model updates importing state and refreshes snapshot`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val result = viewModel.importModelFromUri(
            modelId = "qwen3.5-0.8b-q4",
            sourceUri = fakeUri(),
        )
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertFalse(viewModel.uiState.value.isImporting)
        assertTrue(gateway.importCalls > 0)
        assertTrue(gateway.snapshotCalls > 1)
    }

    @Test
    fun `import model maps runtime domain error to user safe message`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply {
            importFailure = RuntimeDomainException(
                domainError = RuntimeDomainError(
                    code = RuntimeErrorCodes.PROVISIONING_IMPORT_SOURCE_UNREADABLE,
                    userMessage = "Unable to read the selected model file.",
                    technicalDetail = "source uri unreadable",
                ),
            )
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val result = viewModel.importModelFromUri(
            modelId = "qwen3.5-0.8b-q4",
            sourceUri = fakeUri(),
        )
        advanceUntilIdle()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is ProvisioningUserFacingException)
        assertEquals("Unable to read the selected model file.", error?.message)
        val userFacing = error as ProvisioningUserFacingException
        assertEquals(RuntimeErrorCodes.PROVISIONING_IMPORT_SOURCE_UNREADABLE, userFacing.code)
    }

    @Test
    fun `manifest and version actions delegate through gateway`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        viewModel.refreshManifest()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.manifest.models.size)

        assertTrue(viewModel.setActiveVersion("qwen3.5-0.8b-q4", "1"))
        assertTrue(viewModel.removeVersion("qwen3.5-0.8b-q4", "1"))
        viewModel.cancelDownload("task-1")
        assertEquals(1, gateway.setActiveCalls)
        assertEquals(1, gateway.removeCalls)
        assertEquals(1, gateway.cancelCalls)
    }
}

private class FakeProvisioningGateway : ProvisioningGateway {
    val downloads = MutableStateFlow<List<DownloadTaskState>>(emptyList())
    val lifecycle = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    var snapshotCalls: Int = 0
    var importCalls: Int = 0
    var setActiveCalls: Int = 0
    var removeCalls: Int = 0
    var cancelCalls: Int = 0
    var importFailure: Throwable? = null

    override fun currentSnapshot(): RuntimeProvisioningSnapshot {
        snapshotCalls += 1
        return sampleSnapshot()
    }

    override fun observeDownloads() = downloads

    override fun observeModelLifecycle() = lifecycle

    override fun currentModelLifecycle(): RuntimeModelLifecycleSnapshot = lifecycle.value

    override suspend fun importModelFromUri(modelId: String, sourceUri: Uri): RuntimeModelImportResult {
        importFailure?.let { throw it }
        importCalls += 1
        return RuntimeModelImportResult(
            modelId = modelId,
            version = "1",
            absolutePath = "/tmp/model.gguf",
            sha256 = "a".repeat(64),
            copiedBytes = 123L,
            isActive = true,
        )
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return ModelDistributionManifest(
            models = listOf(
                ModelDistributionModel(
                    modelId = "qwen3.5-0.8b-q4",
                    displayName = "Qwen",
                    versions = emptyList(),
                ),
            ),
        )
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return sampleSnapshot().models.first().installedVersions
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean {
        setActiveCalls += 1
        return true
    }

    override fun removeVersion(modelId: String, version: String): Boolean {
        removeCalls += 1
        return true
    }

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied(
            loadedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version),
        )
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            detail = "last_loaded_model_missing",
        )
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override fun enqueueDownload(version: ModelDistributionVersion): String = "task-1"

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) {
        cancelCalls += 1
    }
}

private fun sampleSnapshot(): RuntimeProvisioningSnapshot {
    return RuntimeProvisioningSnapshot(
        models = listOf(
            ProvisionedModelState(
                modelId = "qwen3.5-0.8b-q4",
                displayName = "Qwen",
                fileName = "qwen.gguf",
                absolutePath = "/tmp/qwen.gguf",
                sha256 = "a".repeat(64),
                importedAtEpochMs = 1L,
                activeVersion = "1",
                installedVersions = listOf(
                    ModelVersionDescriptor(
                        modelId = "qwen3.5-0.8b-q4",
                        version = "1",
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
        requiredModelIds = setOf("qwen3.5-0.8b-q4"),
    )
}

private fun sampleDownloadTask(): DownloadTaskState {
    return DownloadTaskState(
        taskId = "task-1",
        modelId = "qwen3.5-0.8b-q4",
        version = "1",
        downloadUrl = "https://example.com/model.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
        runtimeCompatibility = "android-arm64-v8a",
        status = DownloadTaskStatus.DOWNLOADING,
        progressBytes = 50L,
        totalBytes = 100L,
        updatedAtEpochMs = 1L,
    )
}
