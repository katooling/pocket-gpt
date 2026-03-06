package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object ModelDownloadTaskStateStore {
    private const val PREFS_NAME = "pocketagent_model_downloads"
    private const val KEY_TASKS = "tasks_json_v1"

    fun list(context: Context): List<DownloadTaskState> {
        val raw = prefs(context).getString(KEY_TASKS, null).orEmpty().trim()
        if (raw.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val state = decode(item) ?: continue
                    add(state)
                }
            }
        }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAtEpochMs }
    }

    fun get(context: Context, taskId: String): DownloadTaskState? {
        return list(context).firstOrNull { it.taskId == taskId }
    }

    fun upsert(context: Context, task: DownloadTaskState) {
        val all = list(context).associateBy { it.taskId }.toMutableMap()
        all[task.taskId] = task
        persist(context, all.values)
    }

    fun remove(context: Context, taskId: String) {
        val all = list(context).filterNot { it.taskId == taskId }
        persist(context, all)
    }

    private fun persist(context: Context, tasks: Collection<DownloadTaskState>) {
        val array = JSONArray()
        tasks
            .sortedByDescending { it.updatedAtEpochMs }
            .forEach { task -> array.put(encode(task)) }
        prefs(context).edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun encode(task: DownloadTaskState): JSONObject {
        return JSONObject()
            .put("taskId", task.taskId)
            .put("modelId", task.modelId)
            .put("version", task.version)
            .put("downloadUrl", task.downloadUrl)
            .put("expectedSha256", task.expectedSha256)
            .put("provenanceIssuer", task.provenanceIssuer)
            .put("provenanceSignature", task.provenanceSignature)
            .put("verificationPolicy", task.verificationPolicy.name)
            .put("runtimeCompatibility", task.runtimeCompatibility)
            .put("processingStage", task.processingStage.name)
            .put("status", task.status.name)
            .put("progressBytes", task.progressBytes)
            .put("totalBytes", task.totalBytes)
            .put("updatedAtEpochMs", task.updatedAtEpochMs)
            .put("failureReason", task.failureReason?.name)
            .put("message", task.message)
    }

    private fun decode(json: JSONObject): DownloadTaskState? {
        val statusRaw = json.optString("status", DownloadTaskStatus.QUEUED.name)
        val status = runCatching { DownloadTaskStatus.valueOf(statusRaw) }
            .getOrDefault(DownloadTaskStatus.QUEUED)
        val failureRaw = json.optString("failureReason", "")
        val failure = runCatching {
            if (failureRaw.isBlank()) {
                null
            } else {
                DownloadFailureReason.valueOf(failureRaw)
            }
        }.getOrNull()
        val verificationPolicyRaw = json.optString(
            "verificationPolicy",
            DownloadVerificationPolicy.INTEGRITY_ONLY.name,
        )
        val verificationPolicy = runCatching {
            DownloadVerificationPolicy.valueOf(verificationPolicyRaw)
        }.getOrDefault(DownloadVerificationPolicy.INTEGRITY_ONLY)
        val processingStageRaw = json.optString(
            "processingStage",
            DownloadProcessingStage.DOWNLOADING.name,
        )
        val processingStage = runCatching {
            DownloadProcessingStage.valueOf(processingStageRaw)
        }.getOrDefault(DownloadProcessingStage.DOWNLOADING)
        val taskId = json.optString("taskId", "").trim()
        val modelId = json.optString("modelId", "").trim()
        val version = json.optString("version", "").trim()
        if (taskId.isEmpty() || modelId.isEmpty() || version.isEmpty()) {
            return null
        }
        return DownloadTaskState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            downloadUrl = json.optString("downloadUrl", "").trim(),
            expectedSha256 = json.optString("expectedSha256", "").trim(),
            provenanceIssuer = json.optString("provenanceIssuer", "").trim(),
            provenanceSignature = json.optString("provenanceSignature", "").trim(),
            verificationPolicy = verificationPolicy,
            runtimeCompatibility = json.optString("runtimeCompatibility", "").trim(),
            processingStage = processingStage,
            status = status,
            progressBytes = json.optLong("progressBytes", 0L).coerceAtLeast(0L),
            totalBytes = json.optLong("totalBytes", 0L).coerceAtLeast(0L),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs", System.currentTimeMillis()),
            failureReason = failure,
            message = json.optString("message", "").trim().ifEmpty { null },
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
