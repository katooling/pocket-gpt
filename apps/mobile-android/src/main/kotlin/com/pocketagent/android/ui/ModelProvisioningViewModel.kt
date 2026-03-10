package com.pocketagent.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.runtime.ProvisioningGateway
import com.pocketagent.android.runtime.RuntimeDomainException
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
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
    val downloads: List<DownloadTaskState> = emptyList(),
    val manifest: ModelDistributionManifest = ModelDistributionManifest(models = emptyList()),
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
)

class ModelProvisioningViewModel(
    private val gateway: ProvisioningGateway,
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
        viewModelScope.launch {
            gateway.observeDownloads().collect { downloads ->
                _uiState.update { state -> state.copy(downloads = downloads) }
                refreshSnapshot()
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
                _uiState.update { state -> state.copy(snapshot = snapshot) }
            } while (snapshotRefreshQueued && isActive)
        }.also { job ->
            job.invokeOnCompletion {
                snapshotRefreshJob = null
            }
        }
    }

    suspend fun refreshManifest() {
        val manifest = withContext(ioDispatcher) {
            gateway.loadModelDistributionManifest()
        }
        _uiState.update { state -> state.copy(manifest = manifest) }
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
        return changed
    }

    suspend fun setActiveVersionAsync(modelId: String, version: String): Boolean {
        val changed = withContext(ioDispatcher) {
            gateway.setActiveVersion(modelId = modelId, version = version)
        }
        if (changed) {
            refreshSnapshot()
        }
        return changed
    }

    fun removeVersion(modelId: String, version: String): Boolean {
        val removed = gateway.removeVersion(modelId = modelId, version = version)
        if (removed) {
            refreshSnapshot()
        }
        return removed
    }

    suspend fun removeVersionAsync(modelId: String, version: String): Boolean {
        val removed = withContext(ioDispatcher) {
            gateway.removeVersion(modelId = modelId, version = version)
        }
        if (removed) {
            refreshSnapshot()
        }
        return removed
    }

    fun enqueueDownload(version: ModelDistributionVersion): String {
        return gateway.enqueueDownload(version = version)
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelProvisioningViewModel::class.java)) {
            return ModelProvisioningViewModel(gateway) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}
