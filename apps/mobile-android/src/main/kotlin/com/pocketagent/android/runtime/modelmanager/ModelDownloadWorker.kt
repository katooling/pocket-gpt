package com.pocketagent.android.runtime.modelmanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pocketagent.android.R
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.RuntimeBootstrapper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val appContext = context.applicationContext
    private val provisioningStore = AndroidRuntimeProvisioningStore(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty().trim()
        val modelId = inputData.getString(KEY_MODEL_ID).orEmpty().trim()
        val version = inputData.getString(KEY_VERSION).orEmpty().trim()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL).orEmpty().trim()
        val expectedSha = inputData.getString(KEY_EXPECTED_SHA256).orEmpty().trim()
        val issuer = inputData.getString(KEY_PROVENANCE_ISSUER).orEmpty().trim()
        val signature = inputData.getString(KEY_PROVENANCE_SIGNATURE).orEmpty().trim()
        val verificationPolicyRaw = inputData.getString(KEY_VERIFICATION_POLICY).orEmpty()
        val verificationPolicy = parseVerificationPolicy(verificationPolicyRaw)
        val runtimeCompatibility = inputData.getString(KEY_RUNTIME_COMPATIBILITY).orEmpty().trim()
        val declaredTotalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L).coerceAtLeast(0L)

        if (taskId.isEmpty() || modelId.isEmpty() || version.isEmpty() || downloadUrl.isEmpty()) {
            return@withContext Result.failure(outputDataOf(taskId))
        }
        if (verificationPolicy == null) {
            fail(
                taskId = taskId,
                modelId = modelId,
                version = version,
                reason = DownloadFailureReason.UNKNOWN,
                processingStage = DownloadProcessingStage.DOWNLOADING,
                message = "Corrupt task metadata: invalid verification policy '$verificationPolicyRaw'.",
            )
            return@withContext Result.failure(outputDataOf(taskId))
        }
        val retryAllowed = runAttemptCount < MAX_RETRY_ATTEMPTS
        if (!hasNetworkConnection()) {
            fail(
                taskId = taskId,
                modelId = modelId,
                version = version,
                reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                processingStage = DownloadProcessingStage.DOWNLOADING,
                message = "No network connection available.",
            )
            return@withContext if (retryAllowed) Result.retry() else Result.failure(outputDataOf(taskId))
        }

        val activeState = ModelDownloadTaskStateStore.get(appContext, taskId)
        if (activeState?.status == DownloadTaskStatus.PAUSED) {
            return@withContext Result.success(outputDataOf(taskId))
        }

        val downloadDir = provisioningStore.managedDownloadWorkspaceDirectory()
        val partFile = File(downloadDir, "$taskId.part")
        val destinationFile = provisioningStore.destinationFileForVersion(modelId = modelId, version = version)

        try {
            setForeground(createForegroundInfo(taskId = taskId, modelId = modelId, percent = 0))
            updateState(
                taskId = taskId,
                modelId = modelId,
                version = version,
                status = DownloadTaskStatus.DOWNLOADING,
                progressBytes = partFile.length().coerceAtLeast(0L),
                totalBytes = declaredTotalBytes,
                processingStage = DownloadProcessingStage.DOWNLOADING,
                verificationPolicy = verificationPolicy,
                message = "Downloading",
            )

            val (downloadedBytes, computedTotalBytes) = downloadToPartial(
                taskId = taskId,
                modelId = modelId,
                downloadUrl = downloadUrl,
                partFile = partFile,
                declaredTotalBytes = declaredTotalBytes,
                verificationPolicy = verificationPolicy,
            )
            val verificationTotalBytes = computedTotalBytes.takeIf { it > 0L } ?: downloadedBytes

            updateState(
                taskId = taskId,
                modelId = modelId,
                version = version,
                status = DownloadTaskStatus.VERIFYING,
                progressBytes = downloadedBytes,
                totalBytes = verificationTotalBytes,
                processingStage = DownloadProcessingStage.VERIFYING,
                verificationPolicy = verificationPolicy,
                message = "Verifying",
            )

            val sha = sha256HexFromFile(partFile)
            if (!sha.equals(expectedSha, ignoreCase = true)) {
                partFile.delete()
                fail(
                    taskId = taskId,
                    modelId = modelId,
                    version = version,
                    reason = DownloadFailureReason.CHECKSUM_MISMATCH,
                    processingStage = DownloadProcessingStage.VERIFYING,
                    progressBytes = downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Checksum verification failed.",
                )
                return@withContext Result.failure(outputDataOf(taskId))
            }

            val runtimeTag = provisioningStore.expectedRuntimeCompatibilityTag()
            if (runtimeCompatibility.isNotBlank() && runtimeCompatibility != runtimeTag) {
                partFile.delete()
                fail(
                    taskId = taskId,
                    modelId = modelId,
                    version = version,
                    reason = DownloadFailureReason.RUNTIME_INCOMPATIBLE,
                    processingStage = DownloadProcessingStage.VERIFYING,
                    progressBytes = downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Runtime compatibility mismatch.",
                )
                return@withContext Result.failure(outputDataOf(taskId))
            }

            if (verificationPolicy.enforcesProvenance && !verifyProvenanceSignature(
                    issuer = issuer,
                    modelId = modelId,
                    sha = sha,
                    expectedSignature = signature,
                )
            ) {
                partFile.delete()
                fail(
                    taskId = taskId,
                    modelId = modelId,
                    version = version,
                    reason = DownloadFailureReason.PROVENANCE_MISMATCH,
                    processingStage = DownloadProcessingStage.VERIFYING,
                    progressBytes = downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Provenance signature mismatch.",
                )
                return@withContext Result.failure(outputDataOf(taskId))
            }

            updateState(
                taskId = taskId,
                modelId = modelId,
                version = version,
                status = DownloadTaskStatus.VERIFYING,
                progressBytes = downloadedBytes,
                totalBytes = verificationTotalBytes,
                processingStage = DownloadProcessingStage.INSTALLING,
                verificationPolicy = verificationPolicy,
                message = "Installing",
            )
            if (!ModelInstallIo.replaceWithAtomicMove(source = partFile, destination = destinationFile)) {
                fail(
                    taskId = taskId,
                    modelId = modelId,
                    version = version,
                    reason = DownloadFailureReason.UNKNOWN,
                    processingStage = DownloadProcessingStage.INSTALLING,
                    progressBytes = downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Failed to move downloaded model into install location.",
                )
                return@withContext Result.failure(outputDataOf(taskId))
            }

            val installResult = provisioningStore.installDownloadedModel(
                modelId = modelId,
                version = version,
                absolutePath = destinationFile.absolutePath,
                sha256 = sha,
                provenanceIssuer = issuer.ifBlank { "internal-release" },
                provenanceSignature = signature.ifBlank { sha256Hex("${issuer.ifBlank { "internal-release" }}|$modelId|$sha|v1".encodeToByteArray()) },
                runtimeCompatibility = runtimeTag,
                fileSizeBytes = destinationFile.length().coerceAtLeast(0L),
                makeActive = verificationPolicy.enforcesProvenance,
            )
            runCatching {
                RuntimeBootstrapper.installProductionRuntime(appContext)
            }.onFailure { error ->
                Log.w(
                    LOG_TAG,
                    "Runtime refresh failed after installing $modelId@$version: ${error.message}",
                )
            }

            updateState(
                taskId = taskId,
                modelId = modelId,
                version = version,
                status = if (installResult.isActive) {
                    DownloadTaskStatus.COMPLETED
                } else {
                    DownloadTaskStatus.INSTALLED_INACTIVE
                },
                progressBytes = destinationFile.length().coerceAtLeast(0L),
                totalBytes = destinationFile.length().coerceAtLeast(0L),
                processingStage = DownloadProcessingStage.INSTALLING,
                verificationPolicy = verificationPolicy,
                message = if (installResult.isActive) {
                    "Downloaded, verified, and active."
                } else {
                    "Downloaded and verified. Activation pending."
                },
            )
            Result.success(outputDataOf(taskId))
        } catch (timeout: SocketTimeoutException) {
            fail(
                taskId = taskId,
                modelId = modelId,
                version = version,
                reason = DownloadFailureReason.TIMEOUT,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = "Download timed out.",
            )
            if (retryAllowed) Result.retry() else Result.failure(outputDataOf(taskId))
        } catch (cancellation: Exception) {
            if (isStopped) {
                val current = ModelDownloadTaskStateStore.get(appContext, taskId)
                if (
                    current?.status == DownloadTaskStatus.PAUSED ||
                    current?.status == DownloadTaskStatus.CANCELLED
                ) {
                    return@withContext Result.success(outputDataOf(taskId))
                }
                fail(
                    taskId = taskId,
                    modelId = modelId,
                    version = version,
                    reason = DownloadFailureReason.CANCELLED,
                    processingStage = current?.processingStage ?: DownloadProcessingStage.DOWNLOADING,
                    message = "Download cancelled.",
                )
                return@withContext Result.failure(outputDataOf(taskId))
            }
            fail(
                taskId = taskId,
                modelId = modelId,
                version = version,
                reason = DownloadFailureReason.NETWORK_ERROR,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = cancellation.message ?: "Download failed.",
            )
            if (retryAllowed) Result.retry() else Result.failure(outputDataOf(taskId))
        }
    }

    private fun downloadToPartial(
        taskId: String,
        modelId: String,
        downloadUrl: String,
        partFile: File,
        declaredTotalBytes: Long,
        verificationPolicy: DownloadVerificationPolicy,
    ): Pair<Long, Long> {
        var existingBytes = partFile.length().coerceAtLeast(0L)
        val connection = URL(downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        if (existingBytes > 0L) {
            connection.setRequestProperty("Range", "bytes=$existingBytes-")
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("Download server returned HTTP $responseCode")
            }
            if (responseCode == HttpURLConnection.HTTP_OK && existingBytes > 0L) {
                partFile.delete()
                existingBytes = 0L
            }

            val bodyLength = connection.contentLengthLong.coerceAtLeast(0L)
            val expectedTotal = when {
                declaredTotalBytes > 0L -> declaredTotalBytes
                responseCode == HttpURLConnection.HTTP_PARTIAL -> existingBytes + bodyLength
                else -> bodyLength
            }

            BufferedInputStream(connection.inputStream).use { input ->
                val append = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                FileOutputStream(partFile, append).use { fileOutput ->
                    BufferedOutputStream(fileOutput, COPY_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var downloaded = existingBytes
                    var bytesSinceUpdate = 0L
                    while (true) {
                        if (isStopped) {
                            throw IllegalStateException("Worker stopped")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        bytesSinceUpdate += read
                        if (bytesSinceUpdate >= PROGRESS_UPDATE_STEP_BYTES || downloaded == expectedTotal) {
                            bytesSinceUpdate = 0L
                            val percent = if (expectedTotal <= 0L) 0 else ((downloaded * 100L) / expectedTotal).toInt()
                            setForegroundAsync(createForegroundInfo(taskId = taskId, modelId = modelId, percent = percent))
                            updateState(
                                taskId = taskId,
                                modelId = modelId,
                                version = inputData.getString(KEY_VERSION).orEmpty(),
                                status = DownloadTaskStatus.DOWNLOADING,
                                progressBytes = downloaded,
                                totalBytes = expectedTotal,
                                processingStage = DownloadProcessingStage.DOWNLOADING,
                                verificationPolicy = verificationPolicy,
                                message = "Downloading ($percent%)",
                            )
                            setProgressAsync(outputDataOf(taskId))
                        }
                    }
                    output.flush()
                    runCatching { fileOutput.fd.sync() }
                    return@use downloaded to expectedTotal
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyProvenanceSignature(
        issuer: String,
        modelId: String,
        sha: String,
        expectedSignature: String,
    ): Boolean {
        if (expectedSignature.isBlank()) {
            return false
        }
        val normalizedIssuer = issuer.ifBlank { "internal-release" }
        val computed = sha256Hex("$normalizedIssuer|$modelId|$sha|v1".encodeToByteArray())
        return expectedSignature.equals(computed, ignoreCase = true)
    }

    private fun updateState(
        taskId: String,
        modelId: String,
        version: String,
        status: DownloadTaskStatus,
        progressBytes: Long,
        totalBytes: Long,
        processingStage: DownloadProcessingStage,
        verificationPolicy: DownloadVerificationPolicy,
        message: String,
        reason: DownloadFailureReason? = null,
    ) {
        val previous = ModelDownloadTaskStateStore.get(appContext, taskId)
        val next = (previous ?: DownloadTaskState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            downloadUrl = inputData.getString(KEY_DOWNLOAD_URL).orEmpty(),
            expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256).orEmpty(),
            provenanceIssuer = inputData.getString(KEY_PROVENANCE_ISSUER).orEmpty(),
            provenanceSignature = inputData.getString(KEY_PROVENANCE_SIGNATURE).orEmpty(),
            verificationPolicy = verificationPolicy,
            runtimeCompatibility = inputData.getString(KEY_RUNTIME_COMPATIBILITY).orEmpty(),
            processingStage = processingStage,
            status = DownloadTaskStatus.QUEUED,
            progressBytes = 0L,
            totalBytes = 0L,
            updatedAtEpochMs = System.currentTimeMillis(),
        )).copy(
            status = status,
            progressBytes = progressBytes.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L).coerceAtLeast(progressBytes.coerceAtLeast(0L)),
            processingStage = processingStage,
            verificationPolicy = verificationPolicy,
            updatedAtEpochMs = System.currentTimeMillis(),
            failureReason = reason,
            message = message,
        )
        ModelDownloadTaskStateStore.upsert(appContext, next)
    }

    private fun fail(
        taskId: String,
        modelId: String,
        version: String,
        reason: DownloadFailureReason,
        processingStage: DownloadProcessingStage,
        message: String,
        progressBytes: Long? = null,
        totalBytes: Long? = null,
    ) {
        val previous = ModelDownloadTaskStateStore.get(appContext, taskId)
        val resolvedProgress = progressBytes
            ?: previous?.progressBytes
            ?: 0L
        val resolvedTotal = (totalBytes
            ?: previous?.totalBytes
            ?: inputData.getLong(KEY_TOTAL_BYTES, 0L))
            .coerceAtLeast(0L)
            .coerceAtLeast(resolvedProgress.coerceAtLeast(0L))
        updateState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            status = DownloadTaskStatus.FAILED,
            progressBytes = resolvedProgress,
            totalBytes = resolvedTotal,
            processingStage = processingStage,
            verificationPolicy = previous?.verificationPolicy
                ?: DownloadVerificationPolicy.INTEGRITY_ONLY,
            message = message,
            reason = reason,
        )
    }

    private fun createForegroundInfo(taskId: String, modelId: String, percent: Int): ForegroundInfo {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Model downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val safePercent = percent.coerceIn(0, 100)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appContext.getString(R.string.ui_model_download_notification_title))
            .setContentText(appContext.getString(R.string.ui_model_download_notification_body, modelId, safePercent))
            .setOnlyAlertOnce(true)
            .setOngoing(safePercent < 100)
            .setProgress(100, safePercent, false)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                taskId.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(taskId.hashCode(), notification)
        }
    }

    private fun hasNetworkConnection(): Boolean {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun outputDataOf(taskId: String): androidx.work.Data {
        return androidx.work.Data.Builder()
            .putString(KEY_TASK_ID, taskId)
            .build()
    }

    private fun sha256HexFromFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val LOG_TAG = "ModelDownloadWorker"
        internal const val WORK_TAG = "model-download"
        internal const val DOWNLOAD_DIR = "runtime-model-downloads"
        internal const val KEY_TASK_ID = "task_id"
        internal const val KEY_MODEL_ID = "model_id"
        internal const val KEY_VERSION = "version"
        internal const val KEY_DOWNLOAD_URL = "download_url"
        internal const val KEY_EXPECTED_SHA256 = "expected_sha256"
        internal const val KEY_PROVENANCE_ISSUER = "provenance_issuer"
        internal const val KEY_PROVENANCE_SIGNATURE = "provenance_signature"
        internal const val KEY_VERIFICATION_POLICY = "verification_policy"
        internal const val KEY_RUNTIME_COMPATIBILITY = "runtime_compatibility"
        internal const val KEY_TOTAL_BYTES = "total_bytes"

        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_UPDATE_STEP_BYTES = 1024 * 256
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    private fun parseVerificationPolicy(raw: String): DownloadVerificationPolicy? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            return null
        }
        return runCatching {
            DownloadVerificationPolicy.valueOf(normalized)
        }.getOrNull()
    }
}
