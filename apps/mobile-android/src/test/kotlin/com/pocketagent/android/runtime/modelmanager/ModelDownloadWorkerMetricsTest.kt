package com.pocketagent.android.runtime.modelmanager

import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
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

    @Test
    fun `resolveResumeTransferBaseline resets stale partial baseline when server returns full response`() {
        val baseline = resolveResumeTransferBaseline(
            responseCode = HttpURLConnection.HTTP_OK,
            existingBytes = 4_096L,
            metricBytes = 4_096L,
            metricEpochMs = 1_000L,
            nowEpochMs = 2_000L,
        )

        assertTrue(baseline.truncatePartialFile)
        assertEquals(0L, baseline.existingBytes)
        assertEquals(0L, baseline.metricBytes)
        assertEquals(2_000L, baseline.metricEpochMs)
    }

    @Test
    fun `resolveResumeTransferBaseline clamps metric bytes for range continuation`() {
        val baseline = resolveResumeTransferBaseline(
            responseCode = HttpURLConnection.HTTP_PARTIAL,
            existingBytes = 2_048L,
            metricBytes = 4_096L,
            metricEpochMs = 1_000L,
            nowEpochMs = 2_000L,
        )

        assertFalse(baseline.truncatePartialFile)
        assertEquals(2_048L, baseline.existingBytes)
        assertEquals(2_048L, baseline.metricBytes)
        assertEquals(1_000L, baseline.metricEpochMs)
    }
}
