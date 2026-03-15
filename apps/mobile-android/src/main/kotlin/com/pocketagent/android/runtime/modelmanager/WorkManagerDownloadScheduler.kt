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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class WorkManagerDownloadScheduler(
    context: Context,
) : DownloadExecutionScheduler {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    override fun observeTaskInfos(): Flow<List<ScheduledTaskSnapshot>> {
        return workManager.getWorkInfosByTagFlow(ModelDownloadWorker.WORK_TAG).map { infos ->
            infos.mapNotNull(::toSnapshot)
        }
    }

    override suspend fun currentTaskInfos(): List<ScheduledTaskSnapshot> {
        return workManager.getWorkInfosByTag(ModelDownloadWorker.WORK_TAG).get().mapNotNull(::toSnapshot)
    }

    override fun schedule(task: DownloadTaskState) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (task.networkPreference == DownloadNetworkPreference.UNMETERED_ONLY) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                },
            )
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .addTag(ModelDownloadWorker.WORK_TAG)
            .addTag(scheduledTaskTag(task.taskId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .setInputData(workerData(task.taskId))
            .build()
        workManager.enqueueUniqueWork(uniqueScheduledDownloadName(task.taskId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(taskId: String) {
        workManager.cancelUniqueWork(uniqueScheduledDownloadName(taskId))
    }

    private fun toSnapshot(info: WorkInfo): ScheduledTaskSnapshot? {
        val taskId = info.progress.getString(ModelDownloadWorker.KEY_TASK_ID)
            ?: info.outputData.getString(ModelDownloadWorker.KEY_TASK_ID)
            ?: info.tags.firstOrNull { tag -> tag.startsWith("model-download-task:") }?.removePrefix("model-download-task:")
            ?: return null
        val status = when (info.state) {
            WorkInfo.State.ENQUEUED -> ScheduledTaskStatus.ENQUEUED
            WorkInfo.State.RUNNING -> ScheduledTaskStatus.RUNNING
            WorkInfo.State.SUCCEEDED -> ScheduledTaskStatus.SUCCEEDED
            WorkInfo.State.FAILED -> ScheduledTaskStatus.FAILED
            WorkInfo.State.CANCELLED -> ScheduledTaskStatus.CANCELLED
            WorkInfo.State.BLOCKED -> ScheduledTaskStatus.BLOCKED
        }
        return ScheduledTaskSnapshot(taskId = taskId, status = status)
    }
}

internal fun workerData(taskId: String): Data {
    return Data.Builder()
        .putString(ModelDownloadWorker.KEY_TASK_ID, taskId)
        .build()
}
