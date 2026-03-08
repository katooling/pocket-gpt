package com.pocketagent.android.ui.contracts

import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion

sealed interface ProvisioningCommand {
    data class ImportFromDocument(val modelId: String, val uri: String) : ProvisioningCommand
    data class QueueDownload(val version: ModelDistributionVersion) : ProvisioningCommand
    data class PauseDownload(val taskId: String) : ProvisioningCommand
    data class ResumeDownload(val taskId: String) : ProvisioningCommand
    data class RetryDownload(val taskId: String) : ProvisioningCommand
    data class ActivateVersion(val modelId: String, val version: String) : ProvisioningCommand
    data class RemoveVersion(val modelId: String, val version: String) : ProvisioningCommand
}

sealed interface ProvisioningEvent {
    data class DownloadQueued(val taskId: String, val modelId: String, val version: String) : ProvisioningEvent
    data class Progress(
        val taskId: String,
        val modelId: String,
        val version: String,
        val status: DownloadTaskStatus,
        val progressBytes: Long,
        val totalBytes: Long,
    ) : ProvisioningEvent
    data class Installed(val modelId: String, val version: String, val active: Boolean) : ProvisioningEvent
    data class Imported(val result: RuntimeModelImportResult) : ProvisioningEvent
    data class Failed(val code: String, val detail: String) : ProvisioningEvent
}
