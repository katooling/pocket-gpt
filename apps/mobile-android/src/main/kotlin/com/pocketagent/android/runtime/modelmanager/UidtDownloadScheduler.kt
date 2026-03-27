package com.pocketagent.android.runtime.modelmanager

import android.app.job.JobInfo
import android.os.Build
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

internal object UidtDownloadJobRegistry {
    val activeTaskIds = MutableStateFlow<Set<String>>(emptySet())

    fun markRunning(taskId: String) {
        activeTaskIds.value = activeTaskIds.value + taskId
    }

    fun markStopped(taskId: String) {
        activeTaskIds.value = activeTaskIds.value - taskId
    }
}

internal class UidtDownloadScheduler(
    context: Context,
) : DownloadExecutionScheduler {
    private val appContext = context.applicationContext
    private val jobScheduler = appContext.getSystemService(JobScheduler::class.java)
    private val refreshCounter = MutableStateFlow(0)
    private val serviceComponent = ComponentName(appContext, ModelDownloadJobService::class.java)

    override fun observeTaskInfos(): Flow<List<ScheduledTaskSnapshot>> {
        return combine(UidtDownloadJobRegistry.activeTaskIds, refreshCounter) { activeTaskIds, _ ->
            queryTaskInfos(activeTaskIds)
        }
    }

    override suspend fun currentTaskInfos(): List<ScheduledTaskSnapshot> {
        return queryTaskInfos(UidtDownloadJobRegistry.activeTaskIds.value)
    }

    override fun schedule(task: DownloadTaskState) {
        val extras = PersistableBundle().apply {
            putString(ModelDownloadJobService.EXTRA_TASK_ID, task.taskId)
        }
        val builder = JobInfo.Builder(jobId(task.taskId), serviceComponent)
            .setExtras(extras)
            .setRequiredNetworkType(
                if (task.networkPreference == DownloadNetworkPreference.UNMETERED_ONLY) {
                    JobInfo.NETWORK_TYPE_UNMETERED
                } else {
                    JobInfo.NETWORK_TYPE_ANY
                },
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setUserInitiated(true)
        }
        jobScheduler.schedule(builder.build())
        refresh()
    }

    override fun cancel(taskId: String) {
        jobScheduler.cancel(jobId(taskId))
        UidtDownloadJobRegistry.markStopped(taskId)
        refresh()
    }

    private fun refresh() {
        refreshCounter.value = refreshCounter.value + 1
    }

    private fun queryTaskInfos(activeTaskIds: Set<String>): List<ScheduledTaskSnapshot> {
        val pending = jobScheduler.allPendingJobs
            .filter { it.service == serviceComponent }
            .mapNotNull { jobInfo ->
                val taskId = jobInfo.extras?.getString(ModelDownloadJobService.EXTRA_TASK_ID)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                ScheduledTaskSnapshot(
                    taskId = taskId,
                    status = if (taskId in activeTaskIds) {
                        ScheduledTaskStatus.RUNNING
                    } else {
                        ScheduledTaskStatus.ENQUEUED
                    },
                )
            }
        val activeOnly = activeTaskIds
            .filterNot { activeId -> pending.any { it.taskId == activeId } }
            .map { activeId -> ScheduledTaskSnapshot(taskId = activeId, status = ScheduledTaskStatus.RUNNING) }
        return (pending + activeOnly).distinctBy { it.taskId }
    }

    companion object {
        internal fun jobId(taskId: String): Int = taskId.hashCode() and 0x7fffffff
    }
}
