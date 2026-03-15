package com.pocketagent.android.runtime.modelmanager

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ModelDownloadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val executor by lazy { ModelDownloadExecutor(applicationContext) }

    override fun onStartJob(params: JobParameters): Boolean {
        val taskId = params.extras?.getString(EXTRA_TASK_ID).orEmpty().trim()
        if (taskId.isEmpty()) {
            jobFinished(params, false)
            return false
        }
        UidtDownloadJobRegistry.markRunning(taskId)
        val job = serviceScope.launch {
            val outcome = executor.execute(
                taskId = taskId,
                network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.network else null,
                host = object : DownloadExecutionHost {
                    override suspend fun updateNotification(taskId: String, modelId: String, percent: Int) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            setNotification(
                                params,
                                taskId.hashCode(),
                                DownloadNotificationFactory.build(
                                    context = applicationContext,
                                    taskId = taskId,
                                    modelId = modelId,
                                    percent = percent,
                                ),
                                JOB_END_NOTIFICATION_POLICY_REMOVE,
                            )
                        }
                    }

                    override fun isStopped(): Boolean = !activeJobs.containsKey(params.jobId)

                    override fun stopDisposition(): DownloadStopDisposition = DownloadStopDisposition.RESCHEDULE
                },
            )
            UidtDownloadJobRegistry.markStopped(taskId)
            activeJobs.remove(params.jobId)
            jobFinished(params, outcome == DownloadExecutionOutcome.RETRY)
        }
        activeJobs[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJobs.remove(params.jobId)?.cancel()
        val taskId = params.extras?.getString(EXTRA_TASK_ID).orEmpty().trim()
        if (taskId.isNotEmpty()) {
            UidtDownloadJobRegistry.markStopped(taskId)
        }
        val state = taskId.takeIf { it.isNotEmpty() }?.let { ModelDownloadTaskStateStore.get(applicationContext, it) }
        return state != null && !state.terminal && state.status != DownloadTaskStatus.PAUSED && state.status != DownloadTaskStatus.CANCELLED
    }

    override fun onDestroy() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_TASK_ID = "task_id"
    }
}
