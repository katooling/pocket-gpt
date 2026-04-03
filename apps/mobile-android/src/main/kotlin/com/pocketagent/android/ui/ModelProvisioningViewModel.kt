package com.pocketagent.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.runtime.DefaultModelCatalogEligibilityEvaluator
import com.pocketagent.android.runtime.ModelCatalogEligibilityEvaluator
import com.pocketagent.android.runtime.ModelCatalogEligibilitySnapshot
import com.pocketagent.android.runtime.ModelEligibilitySignalsProvider
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadRequestOptions
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelProvisioningUiState(
    val snapshot: RuntimeProvisioningSnapshot? = null,
    val lifecycle: RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot.initial(),
    val downloads: List<DownloadTaskState> = emptyList(),
    val downloadPreferences: DownloadPreferencesState = DownloadPreferencesState(),
    val manifest: ModelDistributionManifest = ModelDistributionManifest(models = emptyList()),
    val manifestLoaded: Boolean = false,
    val eligibility: ModelCatalogEligibilitySnapshot = ModelCatalogEligibilitySnapshot(),
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val enqueuingModelIds: Set<String> = emptySet(),
)

data class ModelLibraryUiState(
    val snapshot: RuntimeProvisioningSnapshot,
    val manifest: ModelDistributionManifest,
    val downloads: List<DownloadTaskState>,
    val eligibility: ModelCatalogEligibilitySnapshot = ModelCatalogEligibilitySnapshot(),
    val isImporting: Boolean,
    val isManifestLoaded: Boolean,
    val statusMessage: String?,
    val defaultGetReadyModelId: String?,
    val defaultModelVersion: ModelDistributionVersion?,
    val enqueuingModelIds: Set<String> = emptySet(),
)

data class RuntimeModelUiState(
    val snapshot: RuntimeProvisioningSnapshot,
    val lifecycle: RuntimeModelLifecycleSnapshot,
    val isImporting: Boolean,
    val statusMessage: String?,
)

internal fun ModelProvisioningUiState.toModelLibraryUiState(defaultGetReadyModelId: String?): ModelLibraryUiState? {
    val currentSnapshot = snapshot ?: return null
    return ModelLibraryUiState(
        snapshot = currentSnapshot,
        manifest = manifest,
        downloads = downloads,
        eligibility = eligibility,
        isImporting = isImporting,
        isManifestLoaded = manifestLoaded,
        statusMessage = statusMessage,
        defaultGetReadyModelId = defaultGetReadyModelId,
        defaultModelVersion = resolveDefaultGetReadyVersion(
            manifest = manifest,
            defaultModelId = defaultGetReadyModelId,
        ),
        enqueuingModelIds = enqueuingModelIds,
    )
}

internal fun ModelProvisioningUiState.toRuntimeModelUiState(): RuntimeModelUiState? {
    val currentSnapshot = snapshot ?: return null
    return RuntimeModelUiState(
        snapshot = currentSnapshot,
        lifecycle = lifecycle,
        isImporting = isImporting,
        statusMessage = statusMessage,
    )
}

class ModelProvisioningViewModel(
    private val gateway: ProvisioningGateway,
    private val eligibilityEvaluator: ModelCatalogEligibilityEvaluator = DefaultModelCatalogEligibilityEvaluator(),
    private val eligibilitySignalsProvider: ModelEligibilitySignalsProvider = ModelEligibilitySignalsProvider.ASSUME_SUPPORTED,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ModelProvisioningUiState(),
    )
    val uiState = _uiState.asStateFlow()
    private var snapshotRefreshJob: Job? = null
    @Volatile
    private var snapshotRefreshQueued: Boolean = false

    init {
        refreshSnapshot()
        refreshLifecycle()
        viewModelScope.launch { refreshManifest() }
        viewModelScope.launch {
            gateway.observeDownloads().collect { downloads ->
                _uiState.update { state -> state.copy(downloads = downloads) }
                refreshSnapshot()
            }
        }
        viewModelScope.launch {
            gateway.observeDownloadPreferences().collect { preferences ->
                _uiState.update { state -> state.copy(downloadPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            gateway.observeModelLifecycle().collect { lifecycle ->
                _uiState.update { state -> state.copy(lifecycle = lifecycle) }
            }
        }
    }

    fun refreshSnapshot() {
        if (snapshotRefreshJob?.isActive == true) {
            snapshotRefreshQueued = true
            return
        }
        snapshotRefreshJob = viewModelScope.launch(ioDispatcher) {
            do {
                snapshotRefreshQueued = false
                val snapshot = gateway.currentSnapshot()
                val lifecycle = gateway.currentModelLifecycle()
                _uiState.update { state ->
                    val updated = state.copy(snapshot = snapshot, lifecycle = lifecycle)
                    updated.withEligibility(
                        evaluator = eligibilityEvaluator,
                        signalsProvider = eligibilitySignalsProvider,
                    )
                }
            } while (snapshotRefreshQueued && isActive)
        }.also { job ->
            job.invokeOnCompletion {
                snapshotRefreshJob = null
            }
        }
    }

    fun refreshLifecycle() {
        val lifecycle = gateway.currentModelLifecycle()
        _uiState.update { state -> state.copy(lifecycle = lifecycle) }
    }

    suspend fun refreshManifest() {
        val manifest = withContext(ioDispatcher) {
            gateway.loadModelDistributionManifest()
        }
        _uiState.update { state ->
            val updated = state.copy(manifest = manifest, manifestLoaded = true)
            updated.withEligibility(
                evaluator = eligibilityEvaluator,
                signalsProvider = eligibilitySignalsProvider,
            )
        }
    }

    fun setStatusMessage(message: String?) {
        _uiState.update { state -> state.copy(statusMessage = message) }
    }

    suspend fun importModelFromUri(modelId: String, sourceUri: Uri): Result<RuntimeModelImportResult> {
        _uiState.update { state -> state.copy(isImporting = true) }
        val result = runCatching {
            withContext(ioDispatcher) {
                gateway.importModelFromUri(modelId = modelId, sourceUri = sourceUri)
            }
        }.recoverCatching { error ->
            throw ProvisioningUserFacingException(
                message = userMessageFor(error),
                code = (error as? RuntimeDomainException)?.domainError?.code,
                cause = error,
            )
        }
        refreshSnapshot()
        _uiState.update { state -> state.copy(isImporting = false) }
        return result
    }

    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return gateway.listInstalledVersions(modelId = modelId)
    }

    suspend fun listInstalledVersionsAsync(modelId: String): List<ModelVersionDescriptor> {
        return withContext(ioDispatcher) {
            gateway.listInstalledVersions(modelId = modelId)
        }
    }

    fun setActiveVersion(modelId: String, version: String): Boolean {
        val changed = gateway.setActiveVersion(modelId = modelId, version = version)
        if (changed) {
            refreshSnapshot()
        }
        refreshLifecycle()
        return changed
    }

    suspend fun setActiveVersionAsync(modelId: String, version: String): Boolean {
        val changed = withContext(ioDispatcher) {
            gateway.setActiveVersion(modelId = modelId, version = version)
        }
        if (changed) {
            refreshSnapshot()
        }
        refreshLifecycle()
        return changed
    }

    fun clearActiveVersion(modelId: String): Boolean {
        val changed = gateway.clearActiveVersion(modelId = modelId)
        if (changed) {
            refreshSnapshot()
        }
        refreshLifecycle()
        return changed
    }

    suspend fun clearActiveVersionAsync(modelId: String): Boolean {
        val changed = withContext(ioDispatcher) {
            gateway.clearActiveVersion(modelId = modelId)
        }
        if (changed) {
            refreshSnapshot()
        }
        refreshLifecycle()
        return changed
    }

    fun removeVersion(modelId: String, version: String): Boolean {
        val removed = gateway.removeVersion(modelId = modelId, version = version)
        if (removed) {
            refreshSnapshot()
        }
        refreshLifecycle()
        return removed
    }

    suspend fun removeVersionAsync(modelId: String, version: String): Boolean {
        val removed = withContext(ioDispatcher) {
            gateway.removeVersion(modelId = modelId, version = version)
        }
        if (removed) {
            refreshSnapshot()
        }
        refreshLifecycle()
        return removed
    }

    suspend fun loadInstalledModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult {
        val result = withContext(ioDispatcher) {
            gateway.loadInstalledModel(modelId = modelId, version = version)
        }
        refreshLifecycle()
        return result
    }

    suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult {
        val result = withContext(ioDispatcher) {
            gateway.loadLastUsedModel()
        }
        refreshLifecycle()
        return result
    }

    suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        val result = withContext(ioDispatcher) {
            gateway.offloadModel(reason = reason)
        }
        refreshLifecycle()
        return result
    }

    suspend fun enqueueDownload(
        version: ModelDistributionVersion,
        options: DownloadRequestOptions = DownloadRequestOptions(),
    ): String {
        val key = "${version.modelId}::${version.version}"
        _uiState.update { state -> state.copy(enqueuingModelIds = state.enqueuingModelIds + key) }
        return try {
            withContext(ioDispatcher) {
                gateway.enqueueDownload(version = version, options = options)
            }
        } finally {
            _uiState.update { state -> state.copy(enqueuingModelIds = state.enqueuingModelIds - key) }
        }
    }

    fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        return gateway.shouldWarnForMeteredLargeDownload(version)
    }

    fun setDownloadWifiOnlyEnabled(enabled: Boolean) {
        gateway.setDownloadWifiOnlyEnabled(enabled)
    }

    fun acknowledgeLargeDownloadCellularWarning() {
        gateway.acknowledgeLargeDownloadCellularWarning()
    }

    fun pauseDownload(taskId: String) {
        gateway.pauseDownload(taskId)
    }

    fun resumeDownload(taskId: String) {
        gateway.resumeDownload(taskId)
    }

    fun retryDownload(taskId: String) {
        gateway.retryDownload(taskId)
    }

    fun cancelDownload(taskId: String) {
        gateway.cancelDownload(taskId)
    }

    fun refreshDownloads() {
        gateway.syncDownloadsFromScheduler()
    }

    private fun userMessageFor(error: Throwable): String {
        return (error as? RuntimeDomainException)?.domainError?.userMessage
            ?: "Model import failed. Please try again."
    }
}

class ProvisioningUserFacingException(
    message: String,
    val code: String?,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ModelProvisioningViewModelFactory(
    private val gateway: ProvisioningGateway,
    private val eligibilityEvaluator: ModelCatalogEligibilityEvaluator = DefaultModelCatalogEligibilityEvaluator(),
    private val eligibilitySignalsProvider: ModelEligibilitySignalsProvider = ModelEligibilitySignalsProvider.ASSUME_SUPPORTED,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelProvisioningViewModel::class.java)) {
            return ModelProvisioningViewModel(
                gateway = gateway,
                eligibilityEvaluator = eligibilityEvaluator,
                eligibilitySignalsProvider = eligibilitySignalsProvider,
            ) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}

private fun ModelProvisioningUiState.withEligibility(
    evaluator: ModelCatalogEligibilityEvaluator,
    signalsProvider: ModelEligibilitySignalsProvider,
): ModelProvisioningUiState {
    return copy(
        eligibility = evaluator.evaluate(
            manifest = manifest,
            snapshot = snapshot,
            signals = signalsProvider.currentSignals(),
        ),
    )
}
