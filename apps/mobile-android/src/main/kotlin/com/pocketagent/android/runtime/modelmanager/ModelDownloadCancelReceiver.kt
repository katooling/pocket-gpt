package com.pocketagent.android.runtime.modelmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketagent.android.AppRuntimeDependencies

class ModelDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL_DOWNLOAD) {
            return
        }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty().trim()
        if (taskId.isEmpty()) {
            return
        }
        AppRuntimeDependencies.cancelDownload(context.applicationContext, taskId)
    }

    companion object {
        internal const val ACTION_CANCEL_DOWNLOAD = "com.pocketagent.android.action.CANCEL_MODEL_DOWNLOAD"
        internal const val EXTRA_TASK_ID = "task_id"
    }
}
