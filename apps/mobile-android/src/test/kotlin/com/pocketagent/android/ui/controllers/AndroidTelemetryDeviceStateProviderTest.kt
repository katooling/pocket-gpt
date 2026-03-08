package com.pocketagent.android.ui.controllers

import android.os.PowerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidTelemetryDeviceStateProviderTest {
    @Test
    fun `battery percent conversion guards invalid values`() {
        assertNull(batteryPercentFromRaw(level = -1, scale = 100))
        assertNull(batteryPercentFromRaw(level = 40, scale = 0))
        assertEquals(40, batteryPercentFromRaw(level = 40, scale = 100))
        assertEquals(100, batteryPercentFromRaw(level = 200, scale = 100))
    }

    @Test
    fun `thermal status maps to routing-friendly scale`() {
        assertEquals(1, thermalLevelFromStatus(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(3, thermalLevelFromStatus(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(5, thermalLevelFromStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(7, thermalLevelFromStatus(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(8, thermalLevelFromStatus(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(9, thermalLevelFromStatus(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals(10, thermalLevelFromStatus(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `ram class conversion rounds down to whole gib and guards invalid bytes`() {
        val gib = 1024L * 1024L * 1024L
        assertNull(ramClassGbFromTotalBytes(0L))
        assertEquals(1, ramClassGbFromTotalBytes(gib / 2))
        assertEquals(6, ramClassGbFromTotalBytes((6L * gib) + (gib / 2)))
    }
}
