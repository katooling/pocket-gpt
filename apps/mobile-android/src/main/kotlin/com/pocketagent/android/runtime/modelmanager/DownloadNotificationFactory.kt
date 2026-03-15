package com.pocketagent.android.runtime.modelmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pocketagent.android.R

internal object DownloadNotificationFactory {
    private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"

    fun build(
        context: Context,
        taskId: String,
        modelId: String,
        percent: Int,
    ): Notification {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val safePercent = percent.coerceIn(0, 100)
        return NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appContext.getString(R.string.ui_model_download_notification_title))
            .setContentText(appContext.getString(R.string.ui_model_download_notification_body, modelId, safePercent))
            .setOnlyAlertOnce(true)
            .setOngoing(safePercent < 100)
            .setProgress(100, safePercent, false)
            .addAction(
                android.R.drawable.ic_delete,
                appContext.getString(R.string.ui_cancel_button),
                cancelPendingIntent(appContext, taskId),
            )
            .build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun cancelPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ModelDownloadCancelReceiver::class.java)
            .setAction(ModelDownloadCancelReceiver.ACTION_CANCEL_DOWNLOAD)
            .putExtra(ModelDownloadCancelReceiver.EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
