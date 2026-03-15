package com.pocketagent.android.runtime.modelmanager

import android.content.Context

class DownloadPreferencesStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun state(): DownloadPreferencesState {
        return DownloadPreferencesState(
            wifiOnlyEnabled = prefs.getBoolean(KEY_WIFI_ONLY_ENABLED, false),
            largeDownloadCellularWarningAcknowledged = prefs.getBoolean(KEY_LARGE_DOWNLOAD_CELLULAR_WARNING_ACK, false),
        )
    }

    fun setWifiOnlyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY_ENABLED, enabled).apply()
    }

    fun acknowledgeLargeDownloadCellularWarning() {
        prefs.edit().putBoolean(KEY_LARGE_DOWNLOAD_CELLULAR_WARNING_ACK, true).apply()
    }

    companion object {
        internal const val LARGE_DOWNLOAD_WARNING_THRESHOLD_BYTES = 1024L * 1024L * 1024L

        private const val PREFS_NAME = "pocketagent_download_preferences"
        private const val KEY_WIFI_ONLY_ENABLED = "wifi_only_enabled"
        private const val KEY_LARGE_DOWNLOAD_CELLULAR_WARNING_ACK = "large_download_cellular_warning_ack"
    }
}
