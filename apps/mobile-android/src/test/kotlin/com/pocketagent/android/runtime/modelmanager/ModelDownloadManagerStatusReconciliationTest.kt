package com.pocketagent.android.runtime.modelmanager

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadManagerStatusReconciliationTest {
    @Test
    fun `keeps completed status when scheduler reports enqueued`() {
        val reconciled = reconcileDownloadStatusFromScheduler(
            existingStatus = DownloadTaskStatus.COMPLETED,
            schedulerStatus = ScheduledTaskStatus.ENQUEUED,
        )

        assertEquals(DownloadTaskStatus.COMPLETED, reconciled)
    }

    @Test
    fun `keeps installed inactive status when scheduler reports running`() {
        val reconciled = reconcileDownloadStatusFromScheduler(
            existingStatus = DownloadTaskStatus.INSTALLED_INACTIVE,
            schedulerStatus = ScheduledTaskStatus.RUNNING,
        )

        assertEquals(DownloadTaskStatus.INSTALLED_INACTIVE, reconciled)
    }

    @Test
    fun `keeps paused state when scheduler reports cancelled`() {
        val reconciled = reconcileDownloadStatusFromScheduler(
            existingStatus = DownloadTaskStatus.PAUSED,
            schedulerStatus = ScheduledTaskStatus.CANCELLED,
        )

        assertEquals(DownloadTaskStatus.PAUSED, reconciled)
    }

    @Test
    fun `maps running to downloading while preserving verify stage intent`() {
        val downloading = reconcileDownloadStatusFromScheduler(
            existingStatus = DownloadTaskStatus.QUEUED,
            schedulerStatus = ScheduledTaskStatus.RUNNING,
        )
        val verifying = reconcileDownloadStatusFromScheduler(
            existingStatus = DownloadTaskStatus.VERIFYING,
            schedulerStatus = ScheduledTaskStatus.RUNNING,
        )

        assertEquals(DownloadTaskStatus.DOWNLOADING, downloading)
        assertEquals(DownloadTaskStatus.VERIFYING, verifying)
    }
}
