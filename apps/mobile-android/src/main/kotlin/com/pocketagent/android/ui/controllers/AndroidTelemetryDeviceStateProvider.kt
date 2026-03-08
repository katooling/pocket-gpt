package com.pocketagent.android.ui.controllers

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.pocketagent.inference.DeviceState

class AndroidTelemetryDeviceStateProvider(
    context: Context,
    private val fallback: DeviceState = DeviceStateProvider.DEFAULT.current(),
) : DeviceStateProvider {
    private val appContext = context.applicationContext

    override fun current(): DeviceState {
        val batteryPercent = resolveBatteryPercent() ?: fallback.batteryPercent
        val thermalLevel = resolveThermalLevel() ?: fallback.thermalLevel
        val ramClassGb = resolveRamClassGb() ?: fallback.ramClassGb
        return DeviceState(
            batteryPercent = batteryPercent.coerceIn(1, 100),
            thermalLevel = thermalLevel.coerceIn(0, 10),
            ramClassGb = ramClassGb.coerceAtLeast(1),
        )
    }

    private fun resolveBatteryPercent(): Int? {
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return batteryPercentFromRaw(level = level, scale = scale)
    }

    private fun resolveThermalLevel(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return null
        return thermalLevelFromStatus(powerManager.currentThermalStatus)
    }

    private fun resolveRamClassGb(): Int? {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return ramClassGbFromTotalBytes(memoryInfo.totalMem)
    }
}

internal fun batteryPercentFromRaw(level: Int, scale: Int): Int? {
    if (level < 0 || scale <= 0) {
        return null
    }
    return ((level.toDouble() * 100.0) / scale.toDouble()).toInt().coerceIn(0, 100)
}

internal fun thermalLevelFromStatus(status: Int): Int {
    return when (status) {
        PowerManager.THERMAL_STATUS_NONE -> 1
        PowerManager.THERMAL_STATUS_LIGHT -> 3
        PowerManager.THERMAL_STATUS_MODERATE -> 5
        PowerManager.THERMAL_STATUS_SEVERE -> 7
        PowerManager.THERMAL_STATUS_CRITICAL -> 8
        PowerManager.THERMAL_STATUS_EMERGENCY -> 9
        PowerManager.THERMAL_STATUS_SHUTDOWN -> 10
        else -> 5
    }
}

internal fun ramClassGbFromTotalBytes(totalMemBytes: Long): Int? {
    if (totalMemBytes <= 0L) {
        return null
    }
    val gib = 1024L * 1024L * 1024L
    return (totalMemBytes / gib).toInt().coerceAtLeast(1)
}
