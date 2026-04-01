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
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadNetworkPreference
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
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

    @Test
    fun `clear active version delegates through gateway and refreshes snapshot`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.clearActiveVersion("qwen3.5-0.8b-q4"))
        advanceUntilIdle()

        assertEquals(1, gateway.clearActiveCalls)
        assertTrue(gateway.snapshotCalls > 1)
    }

    @Test
    fun `download preference actions update observed state and warning checks delegate`() = runTest(dispatcher) {
        val version = sampleDownloadVersion()
        val gateway = FakeProvisioningGateway().apply {
            shouldWarnForMeteredLargeDownloadResult = true
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.shouldWarnForMeteredLargeDownload(version))
        assertEquals(version, gateway.lastWarnVersion)

        viewModel.setDownloadWifiOnlyEnabled(true)
        viewModel.acknowledgeLargeDownloadCellularWarning()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.downloadPreferences.wifiOnlyEnabled)
        assertTrue(viewModel.uiState.value.downloadPreferences.largeDownloadCellularWarningAcknowledged)
    }

    @Test
    fun `library ui state exposes provisioning and download data`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply {
            downloads.value = listOf(sampleDownloadTask())
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()
        viewModel.refreshManifest()
        advanceUntilIdle()

        val libraryState = viewModel.uiState.value.toModelLibraryUiState(defaultGetReadyModelId = "qwen3.5-0.8b-q4")

        assertTrue(libraryState != null)
        assertEquals("qwen3.5-0.8b-q4", libraryState?.snapshot?.models?.firstOrNull()?.modelId)
        assertEquals(1, libraryState?.downloads?.size)
        assertEquals(null, libraryState?.defaultModelVersion?.modelId)
    }

    @Test
    fun `runtime ui state exposes lifecycle and installed versions`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply {
            lifecycle.value = RuntimeModelLifecycleSnapshot(
                loadedModel = RuntimeLoadedModel(
                    modelId = "qwen3.5-0.8b-q4",
                    modelVersion = "1",
                ),
            )
        }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.toRuntimeModelUiState()

        assertTrue(runtimeState != null)
        assertEquals("qwen3.5-0.8b-q4", runtimeState?.lifecycle?.loadedModel?.modelId)
        assertTrue(runtimeState?.snapshot?.models?.firstOrNull()?.installedVersions?.isNotEmpty() == true)
    }

    @Test
    fun `enqueue download forwards explicit request options`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        val version = sampleDownloadVersion()
        val options = DownloadRequestOptions(
            networkPreference = DownloadNetworkPreference.UNMETERED_ONLY,
            userInitiated = false,
        )
        advanceUntilIdle()

        assertEquals("task-1", viewModel.enqueueDownload(version, options))
        assertEquals(version, gateway.lastEnqueuedVersion)
        assertEquals(options, gateway.lastEnqueuedOptions)
    }

    @Test
    fun `load and offload model update lifecycle state`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val loadResult = viewModel.loadInstalledModel("qwen3.5-0.8b-q4", "1")
        advanceUntilIdle()
        assertTrue(loadResult.success)
        assertEquals(
            "qwen3.5-0.8b-q4",
            viewModel.uiState.value.lifecycle.loadedModel?.modelId,
        )

        val offloadResult = viewModel.offloadModel("manual")
        advanceUntilIdle()
        assertTrue(offloadResult.success)
        assertEquals(null, viewModel.uiState.value.lifecycle.loadedModel)
    }

    @Test
    fun `remove version async success sets status and refreshes snapshot`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        viewModel.setStatusMessage("Removing...")
        val result = viewModel.removeVersionAsync("qwen3.5-0.8b-q4", "1")
        advanceUntilIdle()

        assertTrue(result)
        assertEquals(1, gateway.removeCalls)
        assertTrue(gateway.snapshotCalls > 1)
    }

    @Test
    fun `remove version async failure preserves failed status`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway().apply { removeResult = false }
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        val snapshotCountAfterInit = gateway.snapshotCalls
        assertFalse(viewModel.removeVersionAsync("qwen3.5-0.8b-q4", "1"))
        advanceUntilIdle()

        assertEquals(snapshotCountAfterInit, gateway.snapshotCalls)
    }

    @Test
    fun `set status message updates ui state`() = runTest(dispatcher) {
        val gateway = FakeProvisioningGateway()
        val viewModel = ModelProvisioningViewModel(gateway, ioDispatcher = dispatcher)
        advanceUntilIdle()

        viewModel.setStatusMessage("test message")
        assertEquals("test message", viewModel.uiState.value.statusMessage)
        viewModel.setStatusMessage(null)
        assertEquals(null, viewModel.uiState.value.statusMessage)
    }
}

private class FakeProvisioningGateway : ProvisioningGateway {
    val downloads = MutableStateFlow<List<DownloadTaskState>>(emptyList())
    val downloadPreferences = MutableStateFlow(DownloadPreferencesState())
    val lifecycle = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    var snapshotCalls: Int = 0
    var importCalls: Int = 0
    var setActiveCalls: Int = 0
    var clearActiveCalls: Int = 0
    var removeCalls: Int = 0
    var cancelCalls: Int = 0
    var importFailure: Throwable? = null
    var lastEnqueuedVersion: ModelDistributionVersion? = null
    var lastEnqueuedOptions: DownloadRequestOptions? = null
    var shouldWarnForMeteredLargeDownloadResult: Boolean = false
    var lastWarnVersion: ModelDistributionVersion? = null
    var removeResult: Boolean = true

    override fun currentSnapshot(): RuntimeProvisioningSnapshot {
        snapshotCalls += 1
        return sampleSnapshot()
    }

    override fun observeDownloads() = downloads

    override fun observeDownloadPreferences() = downloadPreferences

    override fun currentDownloadPreferences(): DownloadPreferencesState = downloadPreferences.value

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

    override fun clearActiveVersion(modelId: String): Boolean {
        clearActiveCalls += 1
        return true
    }

    override fun removeVersion(modelId: String, version: String): Boolean {
        removeCalls += 1
        return removeResult
    }

    override suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        val loaded = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        lifecycle.value = lifecycle.value.copy(
            state = com.pocketagent.nativebridge.ModelLifecycleState.LOADED,
            loadedModel = loaded,
            lastUsedModel = loaded,
        )
        return RuntimeModelLifecycleCommandResult.applied(loadedModel = loaded)
    }

    override suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
            detail = "last_loaded_model_missing",
        )
    }

    override suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        lifecycle.value = lifecycle.value.copy(
            state = com.pocketagent.nativebridge.ModelLifecycleState.UNLOADED,
            loadedModel = null,
            queuedOffload = false,
        )
        return RuntimeModelLifecycleCommandResult.applied()
    }

    override fun enqueueDownload(version: ModelDistributionVersion, options: DownloadRequestOptions): String {
        lastEnqueuedVersion = version
        lastEnqueuedOptions = options
        return "task-1"
    }

    override fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        lastWarnVersion = version
        return shouldWarnForMeteredLargeDownloadResult
    }

    override fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        downloadPreferences.value = downloadPreferences.value.copy(wifiOnlyEnabled = enabled)
    }

    override fun acknowledgeLargeDownloadCellularWarning() {
        downloadPreferences.value = downloadPreferences.value.copy(
            largeDownloadCellularWarningAcknowledged = true,
        )
    }

    override fun pauseDownload(taskId: String) = Unit

    override fun resumeDownload(taskId: String) = Unit

    override fun retryDownload(taskId: String) = Unit

    override fun cancelDownload(taskId: String) {
        cancelCalls += 1
    }

    override fun syncDownloadsFromScheduler() = Unit
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

private fun sampleDownloadVersion(): ModelDistributionVersion {
    return ModelDistributionVersion(
        modelId = "qwen3.5-0.8b-q4",
        version = "1",
        downloadUrl = "https://example.com/model.gguf",
        expectedSha256 = "a".repeat(64),
        provenanceIssuer = "issuer",
        provenanceSignature = "sig",
        runtimeCompatibility = "android-arm64-v8a",
        fileSizeBytes = 2L * 1024L * 1024L * 1024L,
    )
}
