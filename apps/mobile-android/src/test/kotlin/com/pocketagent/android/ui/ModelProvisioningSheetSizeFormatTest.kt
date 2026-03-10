package com.pocketagent.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelProvisioningSheetSizeFormatTest {
    @Test
    fun `formatAsGiB returns zero for non-positive values`() {
        assertEquals("0.00 GB", 0L.formatAsGiB())
        assertEquals("0.00 GB", (-1L).formatAsGiB())
    }

    @Test
    fun `formatAsGiB formats one gibibyte`() {
        val oneGiB = 1024L * 1024L * 1024L

        assertEquals("1.00 GB", oneGiB.formatAsGiB())
    }

    @Test
    fun `formatAsGiB rounds to two decimals`() {
        val bytes = 507_154_688L

        assertEquals("0.47 GB", bytes.formatAsGiB())
    }

    @Test
    fun `formatAsPerSecond and formatAsEta provide user friendly units`() {
        assertEquals("2.00 MB/s", (2L * 1024L * 1024L).formatAsPerSecond())
        assertEquals("2m 5s", 125L.formatAsEta())
    }
}
