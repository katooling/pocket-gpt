package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelDownloadManager(
    private val context: Context,
    private val provisioningStore: AndroidRuntimeProvisioningStore,
    private val downloadPreferencesStore: DownloadPreferencesStore = DownloadPreferencesStore(context.applicationContext),
    private val scheduler: DownloadExecutionScheduler = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        UidtDownloadScheduler(context.applicationContext)
    } else {
        WorkManagerDownloadScheduler(context.applicationContext)
    },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloads = MutableStateFlow(ModelDownloadTaskStateStore.list(appContext))
    private val _downloadPreferences = MutableStateFlow(downloadPreferencesStore.state())

    init {
        scope.launch {
            scheduler.observeTaskInfos().collectLatest { infos ->
                syncFromSchedulerInfos(infos)
            }
        }
        scope.launch {
            pollActiveDownloadProgress()
        }
    }

    fun observeDownloads(): StateFlow<List<DownloadTaskState>> = _downloads.asStateFlow()

    fun observeDownloadPreferences(): StateFlow<DownloadPreferencesState> = _downloadPreferences.asStateFlow()

    fun currentDownloadPreferences(): DownloadPreferencesState = _downloadPreferences.value

    fun setWifiOnlyEnabled(enabled: Boolean) {
        downloadPreferencesStore.setWifiOnlyEnabled(enabled)
        refreshDownloadPreferences()
    }

    fun acknowledgeLargeDownloadCellularWarning() {
        downloadPreferencesStore.acknowledgeLargeDownloadCellularWarning()
        refreshDownloadPreferences()
    }

    fun refreshDownloadPreferences() {
        _downloadPreferences.value = downloadPreferencesStore.state()
    }

    fun shouldWarnForMeteredLargeDownload(version: ModelDistributionVersion): Boolean {
        val prefs = currentDownloadPreferences()
        if (prefs.wifiOnlyEnabled || prefs.largeDownloadCellularWarningAcknowledged) {
            return false
        }
        if (version.fileSizeBytes < DownloadPreferencesStore.LARGE_DOWNLOAD_WARNING_THRESHOLD_BYTES) {
            return false
        }
        return isActiveNetworkMetered()
    }

    fun enqueueDownload(
        version: ModelDistributionVersion,
        options: DownloadRequestOptions = defaultRequestOptions(version),
    ): String {
        val duplicate = _downloads.value.firstOrNull {
            it.modelId == version.modelId && it.version == version.version && !it.terminal
        }
        if (duplicate != null) {
            return duplicate.taskId
        }

        val storage = provisioningStore.storageSummary()
        if (version.fileSizeBytes > 0L && storage.freeBytes < version.fileSizeBytes) {
            throw IllegalStateException("INSUFFICIENT_STORAGE: Not enough free storage for model download.")
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
            verificationPolicy = version.verificationPolicy,
            runtimeCompatibility = version.runtimeCompatibility,
            processingStage = DownloadProcessingStage.DOWNLOADING,
            status = DownloadTaskStatus.QUEUED,
            progressBytes = 0L,
            totalBytes = version.fileSizeBytes,
            queueOrder = now,
            networkPreference = options.networkPreference,
            updatedAtEpochMs = now,
            message = "Queued",
        )
        ModelDownloadTaskStateStore.upsert(appContext, state)
        refresh()
        pumpQueue()
        return taskId
    }

    fun pauseDownload(taskId: String) {
        val state = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        scheduler.cancel(taskId)
        ModelDownloadTaskStateStore.upsert(
            appContext,
            state.copy(
                status = DownloadTaskStatus.PAUSED,
                downloadSpeedBps = null,
                etaSeconds = null,
                lastProgressEpochMs = null,
                updatedAtEpochMs = System.currentTimeMillis(),
                message = "Paused",
            ),
        )
        refresh()
        pumpQueue()
    }

    fun resumeDownload(taskId: String) {
        val state = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        if (state.status != DownloadTaskStatus.PAUSED) {
            return
        }
        val partBytes = partialFile(taskId).length().coerceAtLeast(0L)
        val resumed = state.copy(
            status = DownloadTaskStatus.QUEUED,
            processingStage = DownloadProcessingStage.DOWNLOADING,
            progressBytes = partBytes,
            queueOrder = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis(),
            failureReason = null,
            downloadSpeedBps = null,
            etaSeconds = null,
            lastProgressEpochMs = null,
            message = "Resumed",
        )
        ModelDownloadTaskStateStore.upsert(appContext, resumed)
        refresh()
        pumpQueue()
    }

    fun retryDownload(taskId: String) {
        val state = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        if (state.status != DownloadTaskStatus.FAILED && state.status != DownloadTaskStatus.CANCELLED) {
            return
        }
        val partBytes = partialFile(taskId).length().coerceAtLeast(0L)
        val retried = state.copy(
            status = DownloadTaskStatus.QUEUED,
            processingStage = DownloadProcessingStage.DOWNLOADING,
            progressBytes = partBytes,
            resumeEtag = state.resumeEtag.takeIf { partBytes > 0L },
            resumeLastModified = state.resumeLastModified.takeIf { partBytes > 0L },
            queueOrder = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis(),
            failureReason = null,
            downloadSpeedBps = null,
            etaSeconds = null,
            lastProgressEpochMs = null,
            message = "Retrying",
        )
        ModelDownloadTaskStateStore.upsert(appContext, retried)
        refresh()
        pumpQueue()
    }

    fun cancelDownload(taskId: String) {
        val state = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        scheduler.cancel(taskId)
        cleanupPartial(taskId)
        ModelDownloadTaskStateStore.upsert(
            appContext,
            state.copy(
                status = DownloadTaskStatus.CANCELLED,
                failureReason = DownloadFailureReason.CANCELLED,
                resumeEtag = null,
                resumeLastModified = null,
                downloadSpeedBps = null,
                etaSeconds = null,
                lastProgressEpochMs = null,
                updatedAtEpochMs = System.currentTimeMillis(),
                message = "Cancelled",
            ),
        )
        refresh()
        pumpQueue()
    }

    fun refresh() {
        _downloads.value = ModelDownloadTaskStateStore.list(appContext)
        refreshDownloadPreferences()
    }

    fun syncFromSchedulerState() {
        scope.launch {
            val infosResult = withContext(Dispatchers.IO) {
                runCatching { scheduler.currentTaskInfos() }
            }
            infosResult
                .onSuccess { infos -> syncFromSchedulerInfos(infos) }
                .onFailure { error -> annotateSyncFailure(error) }
        }
    }

    private fun annotateSyncFailure(error: Throwable) {
        val now = System.currentTimeMillis()
        val warning = "Sync warning: scheduler state unavailable (${error.message ?: "query_failed"}). Keeping last known progress."
        val states = ModelDownloadTaskStateStore.reconcile(appContext) { current ->
            current.values.forEach { state ->
                if (state.terminal) {
                    return@forEach
                }
                val nextMessage = when {
                    state.message.isNullOrBlank() -> warning
                    state.message.contains("Sync warning:", ignoreCase = true) -> state.message
                    else -> "${state.message} | $warning"
                }
                current[state.taskId] = state.copy(
                    updatedAtEpochMs = now,
                    downloadSpeedBps = null,
                    etaSeconds = null,
                    lastProgressEpochMs = null,
                    message = nextMessage,
                )
            }
        }
        _downloads.value = states
    }

    private fun syncFromSchedulerInfos(infos: List<ScheduledTaskSnapshot>) {
        val now = System.currentTimeMillis()
        val snapshotsByTaskId = infos.associateBy { it.taskId }
        val states = ModelDownloadTaskStateStore.reconcile(appContext) { current ->
            current.values.toList().forEach { existing ->
                val snapshot = snapshotsByTaskId[existing.taskId]
                if (snapshot != null) {
                    val status = reconcileDownloadStatusFromScheduler(
                        existingStatus = existing.status,
                        schedulerStatus = snapshot.status,
                    )
                    val preserveTimestamp = existing.status.isTerminalStatus() && status == existing.status
                    current[existing.taskId] = existing.copy(
                        status = status,
                        updatedAtEpochMs = if (preserveTimestamp) existing.updatedAtEpochMs else now,
                    )
                    return@forEach
                }
                if (
                    existing.status == DownloadTaskStatus.DOWNLOADING ||
                    existing.status == DownloadTaskStatus.VERIFYING
                ) {
                    val staleMs = now - existing.updatedAtEpochMs
                    if (staleMs < ORPHANED_ACTIVE_TASK_STALE_MS) {
                        return@forEach
                    }
                    current[existing.taskId] = existing.copy(
                        status = DownloadTaskStatus.FAILED,
                        failureReason = DownloadFailureReason.UNKNOWN,
                        downloadSpeedBps = null,
                        etaSeconds = null,
                        lastProgressEpochMs = null,
                        updatedAtEpochMs = now,
                        message = "Download interrupted. Retry or cancel.",
                    )
                }
            }
        }
        _downloads.value = states
        pumpQueue()
    }

    private fun pumpQueue() {
        scope.launch {
            val scheduledTaskIds = runCatching {
                scheduler.currentTaskInfos()
                    .filter { it.status == ScheduledTaskStatus.ENQUEUED || it.status == ScheduledTaskStatus.RUNNING || it.status == ScheduledTaskStatus.BLOCKED }
                    .map { it.taskId }
                    .toSet()
            }.getOrDefault(emptySet())
            val states = ModelDownloadTaskStateStore.list(appContext)
            val activeScheduledCount = states.count { it.taskId in scheduledTaskIds && !it.terminal && it.status != DownloadTaskStatus.PAUSED }
            val capacity = (MAX_CONCURRENT_DOWNLOADS - activeScheduledCount).coerceAtLeast(0)
            if (capacity <= 0) {
                return@launch
            }
            states.asSequence()
                .filter { it.status == DownloadTaskStatus.QUEUED }
                .filterNot { it.taskId in scheduledTaskIds }
                .sortedBy { it.queueOrder.takeIf { order -> order > 0L } ?: Long.MAX_VALUE }
                .take(capacity)
                .forEach { task ->
                    scheduler.schedule(task)
                }
        }
    }

    private suspend fun pollActiveDownloadProgress() {
        while (coroutineContext.isActive) {
            val hasActiveTasks = _downloads.value.any { it.shouldPollProgress }
            if (hasActiveTasks) {
                val latest = ModelDownloadTaskStateStore.list(appContext)
                if (latest != _downloads.value) {
                    _downloads.value = latest
                }
                delay(ACTIVE_PROGRESS_POLL_MS)
            } else {
                delay(IDLE_PROGRESS_POLL_MS)
            }
        }
    }

    private fun defaultRequestOptions(version: ModelDistributionVersion): DownloadRequestOptions {
        val prefs = currentDownloadPreferences()
        return DownloadRequestOptions(
            networkPreference = if (prefs.wifiOnlyEnabled && version.fileSizeBytes >= DownloadPreferencesStore.LARGE_DOWNLOAD_WARNING_THRESHOLD_BYTES) {
                DownloadNetworkPreference.UNMETERED_ONLY
            } else {
                DownloadNetworkPreference.ALLOW_METERED
            },
        )
    }

    private fun isActiveNetworkMetered(): Boolean {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.isActiveNetworkMetered
    }

    private fun cleanupPartial(taskId: String) {
        partialFile(taskId).takeIf { it.exists() }?.delete()
    }

    private fun partialFile(taskId: String): File {
        val dir = provisioningStore.managedDownloadWorkspaceDirectory()
        return File(dir, "$taskId.part")
    }

    companion object {
        private const val ORPHANED_ACTIVE_TASK_STALE_MS = 2 * 60 * 1000L
        private const val MAX_CONCURRENT_DOWNLOADS = 2
        private const val ACTIVE_PROGRESS_POLL_MS = 500L
        private const val IDLE_PROGRESS_POLL_MS = 2_000L
    }
}

private val DownloadTaskState.shouldPollProgress: Boolean
    get() = !terminal && status != DownloadTaskStatus.PAUSED

internal fun reconcileDownloadStatusFromScheduler(
    existingStatus: DownloadTaskStatus,
    schedulerStatus: ScheduledTaskStatus,
): DownloadTaskStatus {
    if (existingStatus.isTerminalStatus()) {
        return existingStatus
    }
    return when (schedulerStatus) {
        ScheduledTaskStatus.ENQUEUED -> DownloadTaskStatus.QUEUED
        ScheduledTaskStatus.RUNNING -> if (existingStatus == DownloadTaskStatus.VERIFYING) {
            DownloadTaskStatus.VERIFYING
        } else {
            DownloadTaskStatus.DOWNLOADING
        }
        ScheduledTaskStatus.SUCCEEDED -> DownloadTaskStatus.COMPLETED
        ScheduledTaskStatus.CANCELLED -> if (existingStatus == DownloadTaskStatus.PAUSED) {
            DownloadTaskStatus.PAUSED
        } else {
            DownloadTaskStatus.CANCELLED
        }
        ScheduledTaskStatus.FAILED -> DownloadTaskStatus.FAILED
        ScheduledTaskStatus.BLOCKED -> existingStatus
    }
}

internal fun DownloadTaskStatus.isTerminalStatus(): Boolean {
    return this == DownloadTaskStatus.FAILED ||
        this == DownloadTaskStatus.CANCELLED ||
        this == DownloadTaskStatus.COMPLETED ||
        this == DownloadTaskStatus.INSTALLED_INACTIVE
}
