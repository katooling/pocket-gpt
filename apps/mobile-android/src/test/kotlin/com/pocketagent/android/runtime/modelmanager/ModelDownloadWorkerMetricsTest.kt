package com.pocketagent.android.runtime.modelmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelDownloadWorkerMetricsTest {
    @Test
    fun `calculateTransferMetrics computes speed and eta from progress window`() {
        val metrics = calculateTransferMetrics(
            previousBytes = 1_000_000L,
            previousEpochMs = 1_000L,
            currentBytes = 2_000_000L,
            currentEpochMs = 2_000L,
            totalBytes = 4_000_000L,
        )

        assertEquals(1_000_000L, metrics.downloadSpeedBps)
        assertEquals(2L, metrics.etaSeconds)
        assertEquals(2_000L, metrics.lastProgressEpochMs)
    }

    @Test
    fun `calculateTransferMetrics supports restart continuity using persisted checkpoint`() {
        val metrics = calculateTransferMetrics(
            previousBytes = 2_500_000L,
            previousEpochMs = 10_000L,
            currentBytes = 2_750_000L,
            currentEpochMs = 10_500L,
            totalBytes = 3_000_000L,
        )

        assertNotNull(metrics.downloadSpeedBps)
        assertTrue((metrics.downloadSpeedBps ?: 0L) > 0L)
        assertNotNull(metrics.etaSeconds)
        assertTrue((metrics.etaSeconds ?: -1L) >= 0L)
    }
}
