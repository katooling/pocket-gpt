package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ModelDownloadManager(
    private val context: Context,
    private val provisioningStore: AndroidRuntimeProvisioningStore,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloads = MutableStateFlow(ModelDownloadTaskStateStore.list(appContext))

    init {
        scope.launch {
            while (isActive) {
                _downloads.value = ModelDownloadTaskStateStore.list(appContext)
                delay(750L)
            }
        }
    }

    fun observeDownloads(): StateFlow<List<DownloadTaskState>> = _downloads.asStateFlow()

    fun enqueueDownload(version: ModelDistributionVersion): String {
        val duplicate = _downloads.value.firstOrNull {
            it.modelId == version.modelId && it.version == version.version && !it.terminal
        }
        if (duplicate != null) {
            return duplicate.taskId
        }

        val storage = provisioningStore.storageSummary()
        if (version.fileSizeBytes > 0L && storage.freeBytes < version.fileSizeBytes) {
            return upsertSyntheticFailure(
                modelId = version.modelId,
                version = version.version,
                reason = DownloadFailureReason.INSUFFICIENT_STORAGE,
                message = "Not enough free storage for model download.",
            )
        }

        val taskId = "dl-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val state = DownloadTaskState(
            taskId = taskId,
            modelId = version.modelId,
            version = version.version,
            downloadUrl = version.downloadUrl,
            expectedSha256 = version.expectedSha256,
            provenanceIssuer = version.provenanceIssuer,
            provenanceSignature = version.provenanceSignature,
            runtimeCompatibility = version.runtimeCompatibility,
            status = DownloadTaskStatus.QUEUED,
            progressBytes = 0L,
            totalBytes = version.fileSizeBytes,
            updatedAtEpochMs = now,
            message = "Queued",
        )
        ModelDownloadTaskStateStore.upsert(appContext, state)
        launchWork(state)
        _downloads.value = ModelDownloadTaskStateStore.list(appContext)
        return taskId
    }

    fun pauseDownload(taskId: String) {
        val state = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        ModelDownloadTaskStateStore.upsert(
            appContext,
            state.copy(
                status = DownloadTaskStatus.PAUSED,
                updatedAtEpochMs = System.currentTimeMillis(),
                message = "Paused",
            ),
        )
        workManager.cancelUniqueWork(uniqueWorkName(taskId))
        _downloads.value = ModelDownloadTaskStateStore.list(appContext)
    }

    fun resumeDownload(taskId: String) {
        val state = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        if (state.status != DownloadTaskStatus.PAUSED) {
            return
        }
        val resumed = state.copy(
            status = DownloadTaskStatus.QUEUED,
            updatedAtEpochMs = System.currentTimeMillis(),
            failureReason = null,
            message = "Resumed",
        )
        ModelDownloadTaskStateStore.upsert(appContext, resumed)
        launchWork(resumed)
        _downloads.value = ModelDownloadTaskStateStore.list(appContext)
    }

    fun retryDownload(taskId: String) {
        val state = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        if (state.status != DownloadTaskStatus.FAILED && state.status != DownloadTaskStatus.CANCELLED) {
            return
        }
        cleanupPartial(taskId)
        val retried = state.copy(
            status = DownloadTaskStatus.QUEUED,
            progressBytes = 0L,
            updatedAtEpochMs = System.currentTimeMillis(),
            failureReason = null,
            message = "Retrying",
        )
        ModelDownloadTaskStateStore.upsert(appContext, retried)
        launchWork(retried)
        _downloads.value = ModelDownloadTaskStateStore.list(appContext)
    }

    fun refresh() {
        _downloads.value = ModelDownloadTaskStateStore.list(appContext)
    }

    fun syncFromWorkManagerState() {
        scope.launch {
            val states = ModelDownloadTaskStateStore.list(appContext).associateBy { it.taskId }.toMutableMap()
            val infos = withContext(Dispatchers.IO) {
                runCatching {
                    workManager.getWorkInfosByTag(ModelDownloadWorker.WORK_TAG).get()
                }.getOrDefault(emptyList())
            }
            infos.forEach { info ->
                val taskId = info.progress.getString(ModelDownloadWorker.KEY_TASK_ID)
                    ?: info.outputData.getString(ModelDownloadWorker.KEY_TASK_ID)
                    ?: return@forEach
                val existing = states[taskId] ?: return@forEach
                val status = when (info.state) {
                    WorkInfo.State.ENQUEUED -> DownloadTaskStatus.QUEUED
                    WorkInfo.State.RUNNING -> DownloadTaskStatus.DOWNLOADING
                    WorkInfo.State.SUCCEEDED -> DownloadTaskStatus.COMPLETED
                    WorkInfo.State.CANCELLED -> DownloadTaskStatus.CANCELLED
                    WorkInfo.State.FAILED -> DownloadTaskStatus.FAILED
                    WorkInfo.State.BLOCKED -> existing.status
                }
                states[taskId] = existing.copy(
                    status = if (existing.status == DownloadTaskStatus.INSTALLED_INACTIVE) existing.status else status,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
            states.values.forEach { ModelDownloadTaskStateStore.upsert(appContext, it) }
            refresh()
        }
    }

    private fun launchWork(task: DownloadTaskState) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .addTag(ModelDownloadWorker.WORK_TAG)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .setInputData(task.toWorkerData())
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(task.taskId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun upsertSyntheticFailure(
        modelId: String,
        version: String,
        reason: DownloadFailureReason = DownloadFailureReason.UNKNOWN,
        message: String,
    ): String {
        val taskId = "dl-${UUID.randomUUID()}"
        ModelDownloadTaskStateStore.upsert(
            appContext,
            DownloadTaskState(
                taskId = taskId,
                modelId = modelId,
                version = version,
                downloadUrl = "",
                expectedSha256 = "",
                provenanceIssuer = "",
                provenanceSignature = "",
                runtimeCompatibility = provisioningStore.expectedRuntimeCompatibilityTag(),
                status = DownloadTaskStatus.FAILED,
                progressBytes = 0L,
                totalBytes = 0L,
                updatedAtEpochMs = System.currentTimeMillis(),
                failureReason = reason,
                message = message,
            ),
        )
        _downloads.value = ModelDownloadTaskStateStore.list(appContext)
        return taskId
    }

    private fun cleanupPartial(taskId: String) {
        val dir = File(appContext.filesDir, ModelDownloadWorker.DOWNLOAD_DIR)
        File(dir, "$taskId.part").takeIf { it.exists() }?.delete()
    }

    private fun uniqueWorkName(taskId: String): String = "model-download-$taskId"
}

private fun DownloadTaskState.toWorkerData(): Data {
    return Data.Builder()
        .putString(ModelDownloadWorker.KEY_TASK_ID, taskId)
        .putString(ModelDownloadWorker.KEY_MODEL_ID, modelId)
        .putString(ModelDownloadWorker.KEY_VERSION, version)
        .putString(ModelDownloadWorker.KEY_DOWNLOAD_URL, downloadUrl)
        .putString(ModelDownloadWorker.KEY_EXPECTED_SHA256, expectedSha256)
        .putString(ModelDownloadWorker.KEY_PROVENANCE_ISSUER, provenanceIssuer)
        .putString(ModelDownloadWorker.KEY_PROVENANCE_SIGNATURE, provenanceSignature)
        .putString(ModelDownloadWorker.KEY_RUNTIME_COMPATIBILITY, runtimeCompatibility)
        .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, totalBytes)
        .build()
}
