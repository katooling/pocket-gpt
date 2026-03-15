package com.pocketagent.android.runtime.modelmanager

import kotlinx.coroutines.flow.Flow

enum class ScheduledTaskStatus {
    ENQUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BLOCKED,
}

data class ScheduledTaskSnapshot(
    val taskId: String,
    val status: ScheduledTaskStatus,
)

interface DownloadExecutionScheduler {
    fun observeTaskInfos(): Flow<List<ScheduledTaskSnapshot>>

    suspend fun currentTaskInfos(): List<ScheduledTaskSnapshot>

    fun schedule(task: DownloadTaskState)

    fun cancel(taskId: String)
}

internal fun uniqueScheduledDownloadName(taskId: String): String = "model-download-$taskId"

internal fun scheduledTaskTag(taskId: String): String = "model-download-task:$taskId"
