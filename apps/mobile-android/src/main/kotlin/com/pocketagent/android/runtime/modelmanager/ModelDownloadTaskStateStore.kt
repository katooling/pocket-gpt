package com.pocketagent.android.runtime.modelmanager

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

internal object ModelDownloadTaskStateStore {
    private const val DB_NAME = "pocketagent_model_downloads.db"
    private const val DB_VERSION = 1
    private const val TABLE = "download_tasks"

    private const val LEGACY_PREFS_NAME = "pocketagent_model_downloads"
    private const val LEGACY_TASKS_KEY = "tasks_json_v1"

    private val lock = Any()

    @Volatile
    private var helper: StoreDbHelper? = null

    fun list(context: Context): List<DownloadTaskState> {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).readableDatabase
            db.query(TABLE, null, null, null, null, null, "updated_at_epoch_ms DESC").use { cursor ->
                val out = mutableListOf<DownloadTaskState>()
                while (cursor.moveToNext()) {
                    decodeCursor(cursor)?.let(out::add)
                }
                return out
            }
        }
    }

    fun get(context: Context, taskId: String): DownloadTaskState? {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).readableDatabase
            db.query(
                TABLE,
                null,
                "task_id = ?",
                arrayOf(taskId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return if (cursor.moveToFirst()) decodeCursor(cursor) else null
            }
        }
    }

    fun upsert(context: Context, task: DownloadTaskState) {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).writableDatabase
            upsertRow(db, task)
        }
    }

    fun remove(context: Context, taskId: String) {
        synchronized(lock) {
            val appContext = context.applicationContext
            migrateLegacyPrefsIfNeeded(appContext)
            val db = db(appContext).writableDatabase
            db.delete(TABLE, "task_id = ?", arrayOf(taskId))
        }
    }

    private fun upsertRow(db: SQLiteDatabase, task: DownloadTaskState) {
        val values = ContentValues().apply {
            put("task_id", task.taskId)
            put("model_id", task.modelId)
            put("version", task.version)
            put("download_url", task.downloadUrl)
            put("expected_sha256", task.expectedSha256)
            put("provenance_issuer", task.provenanceIssuer)
            put("provenance_signature", task.provenanceSignature)
            put("verification_policy", task.verificationPolicy.name)
            put("runtime_compatibility", task.runtimeCompatibility)
            put("processing_stage", task.processingStage.name)
            put("status", task.status.name)
            put("progress_bytes", task.progressBytes)
            put("total_bytes", task.totalBytes)
            put("updated_at_epoch_ms", task.updatedAtEpochMs)
            put("failure_reason", task.failureReason?.name)
            put("message", task.message)
        }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun decodeLegacy(json: JSONObject): DownloadTaskState? {
        return decodeValues(
            taskId = json.optString("taskId", "").trim(),
            modelId = json.optString("modelId", "").trim(),
            version = json.optString("version", "").trim(),
            downloadUrl = json.optString("downloadUrl", "").trim(),
            expectedSha256 = json.optString("expectedSha256", "").trim(),
            provenanceIssuer = json.optString("provenanceIssuer", "").trim(),
            provenanceSignature = json.optString("provenanceSignature", "").trim(),
            verificationPolicyRaw = json.optString(
                "verificationPolicy",
                DownloadVerificationPolicy.INTEGRITY_ONLY.name,
            ),
            runtimeCompatibility = json.optString("runtimeCompatibility", "").trim(),
            processingStageRaw = json.optString(
                "processingStage",
                DownloadProcessingStage.DOWNLOADING.name,
            ),
            statusRaw = json.optString("status", DownloadTaskStatus.QUEUED.name),
            progressBytes = json.optLong("progressBytes", 0L).coerceAtLeast(0L),
            totalBytes = json.optLong("totalBytes", 0L).coerceAtLeast(0L),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs", System.currentTimeMillis()),
            failureReasonRaw = json.optString("failureReason", ""),
            message = json.optString("message", "").trim().ifEmpty { null },
        )
    }

    private fun decodeCursor(cursor: Cursor): DownloadTaskState? {
        return decodeValues(
            taskId = cursor.stringOrEmpty("task_id"),
            modelId = cursor.stringOrEmpty("model_id"),
            version = cursor.stringOrEmpty("version"),
            downloadUrl = cursor.stringOrEmpty("download_url"),
            expectedSha256 = cursor.stringOrEmpty("expected_sha256"),
            provenanceIssuer = cursor.stringOrEmpty("provenance_issuer"),
            provenanceSignature = cursor.stringOrEmpty("provenance_signature"),
            verificationPolicyRaw = cursor.stringOrEmpty("verification_policy"),
            runtimeCompatibility = cursor.stringOrEmpty("runtime_compatibility"),
            processingStageRaw = cursor.stringOrEmpty("processing_stage"),
            statusRaw = cursor.stringOrEmpty("status"),
            progressBytes = cursor.longOrZero("progress_bytes"),
            totalBytes = cursor.longOrZero("total_bytes"),
            updatedAtEpochMs = cursor.longOrZero("updated_at_epoch_ms"),
            failureReasonRaw = cursor.stringOrEmpty("failure_reason"),
            message = cursor.stringOrEmpty("message").ifBlank { null },
        )
    }

    private fun decodeValues(
        taskId: String,
        modelId: String,
        version: String,
        downloadUrl: String,
        expectedSha256: String,
        provenanceIssuer: String,
        provenanceSignature: String,
        verificationPolicyRaw: String,
        runtimeCompatibility: String,
        processingStageRaw: String,
        statusRaw: String,
        progressBytes: Long,
        totalBytes: Long,
        updatedAtEpochMs: Long,
        failureReasonRaw: String,
        message: String?,
    ): DownloadTaskState? {
        val status = runCatching { DownloadTaskStatus.valueOf(statusRaw) }.getOrNull()
        val failure = runCatching {
            if (failureReasonRaw.isBlank()) {
                null
            } else {
                DownloadFailureReason.valueOf(failureReasonRaw)
            }
        }.getOrNull()
        val verificationPolicy = runCatching { DownloadVerificationPolicy.valueOf(verificationPolicyRaw) }.getOrNull()
        val processingStage = runCatching { DownloadProcessingStage.valueOf(processingStageRaw) }.getOrNull()
        if (taskId.isEmpty() || modelId.isEmpty() || version.isEmpty()) {
            return null
        }
        val corruptionNotes = buildList {
            if (status == null) {
                add("invalid_status=$statusRaw")
            }
            if (verificationPolicy == null) {
                add("invalid_verification_policy=$verificationPolicyRaw")
            }
            if (processingStage == null) {
                add("invalid_processing_stage=$processingStageRaw")
            }
            if (failureReasonRaw.isNotBlank() && failure == null) {
                add("invalid_failure_reason=$failureReasonRaw")
            }
        }
        val isCorrupt = corruptionNotes.isNotEmpty()
        val resolvedMessage = if (isCorrupt) {
            "Corrupt task metadata detected; task marked failed (${corruptionNotes.joinToString(",")})."
        } else {
            message
        }
        return DownloadTaskState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            downloadUrl = downloadUrl,
            expectedSha256 = expectedSha256,
            provenanceIssuer = provenanceIssuer,
            provenanceSignature = provenanceSignature,
            verificationPolicy = verificationPolicy ?: DownloadVerificationPolicy.UNKNOWN,
            runtimeCompatibility = runtimeCompatibility,
            processingStage = processingStage ?: DownloadProcessingStage.CORRUPT,
            status = if (isCorrupt) DownloadTaskStatus.FAILED else status ?: DownloadTaskStatus.FAILED,
            progressBytes = progressBytes.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            failureReason = if (isCorrupt) DownloadFailureReason.UNKNOWN else failure,
            message = resolvedMessage,
        )
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(LEGACY_TASKS_KEY, null).orEmpty().trim()
        if (raw.isEmpty()) {
            return
        }
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val db = db(context).writableDatabase
        db.beginTransaction()
        try {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val decoded = decodeLegacy(item) ?: continue
                upsertRow(db, decoded)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit().remove(LEGACY_TASKS_KEY).apply()
    }

    private fun db(context: Context): StoreDbHelper {
        return helper ?: synchronized(lock) {
            helper ?: StoreDbHelper(context.applicationContext).also { helper = it }
        }
    }

    private class StoreDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    task_id TEXT PRIMARY KEY NOT NULL,
                    model_id TEXT NOT NULL,
                    version TEXT NOT NULL,
                    download_url TEXT NOT NULL,
                    expected_sha256 TEXT NOT NULL,
                    provenance_issuer TEXT NOT NULL,
                    provenance_signature TEXT NOT NULL,
                    verification_policy TEXT NOT NULL,
                    runtime_compatibility TEXT NOT NULL,
                    processing_stage TEXT NOT NULL,
                    status TEXT NOT NULL,
                    progress_bytes INTEGER NOT NULL,
                    total_bytes INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    failure_reason TEXT,
                    message TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_download_tasks_updated_at ON $TABLE(updated_at_epoch_ms DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No-op for initial schema.
        }
    }
}

private fun Cursor.stringOrEmpty(columnName: String): String {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return ""
    }
    return getString(index)?.trim().orEmpty()
}

private fun Cursor.longOrZero(columnName: String): Long {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return 0L
    }
    return getLong(index)
}
