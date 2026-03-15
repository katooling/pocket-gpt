package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.RuntimeBootstrapper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

internal enum class DownloadExecutionOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal enum class DownloadStopDisposition {
    MARK_CANCELLED,
    RESCHEDULE,
}

internal interface DownloadExecutionHost {
    suspend fun updateNotification(taskId: String, modelId: String, percent: Int)

    fun isStopped(): Boolean

    fun stopDisposition(): DownloadStopDisposition
}

internal data class DownloadResult(
    val downloadedBytes: Long,
    val computedTotalBytes: Long,
    val sha256Hex: String?,
    val resumeEtag: String?,
    val resumeLastModified: String?,
)

internal data class TransferMetrics(
    val downloadSpeedBps: Long?,
    val etaSeconds: Long?,
    val lastProgressEpochMs: Long?,
)

internal data class ResumeTransferBaseline(
    val existingBytes: Long,
    val metricBytes: Long,
    val metricEpochMs: Long,
    val truncatePartialFile: Boolean,
)

internal class ModelDownloadExecutor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provisioningStore = AndroidRuntimeProvisioningStore(appContext)

    suspend fun execute(
        taskId: String,
        host: DownloadExecutionHost,
        network: Network? = null,
        retryAllowed: Boolean = false,
    ): DownloadExecutionOutcome = withContext(Dispatchers.IO) {
        val task = ModelDownloadTaskStateStore.get(appContext, taskId)
            ?: return@withContext DownloadExecutionOutcome.FAILURE
        if (task.status == DownloadTaskStatus.PAUSED) {
            return@withContext DownloadExecutionOutcome.SUCCESS
        }
        if (!hasNetworkConnection()) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                processingStage = DownloadProcessingStage.DOWNLOADING,
                message = "No network connection available.",
            )
            return@withContext if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        }

        val downloadDir = provisioningStore.managedDownloadWorkspaceDirectory()
        val partFile = File(downloadDir, "$taskId.part")
        val destinationFile = provisioningStore.destinationFileForVersion(modelId = task.modelId, version = task.version)

        try {
            host.updateNotification(taskId = taskId, modelId = task.modelId, percent = 0)
            updateState(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                status = DownloadTaskStatus.DOWNLOADING,
                progressBytes = partFile.length().coerceAtLeast(0L),
                totalBytes = task.totalBytes,
                processingStage = DownloadProcessingStage.DOWNLOADING,
                verificationPolicy = task.verificationPolicy,
                message = "Downloading",
            )

            val result = downloadToPartial(
                task = task,
                partFile = partFile,
                host = host,
                network = network,
            )
            persistResumeMetadata(
                taskId = taskId,
                resumeEtag = result.resumeEtag,
                resumeLastModified = result.resumeLastModified,
            )
            val verificationTotalBytes = result.computedTotalBytes.takeIf { it > 0L } ?: result.downloadedBytes

            updateState(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                status = DownloadTaskStatus.VERIFYING,
                progressBytes = result.downloadedBytes,
                totalBytes = verificationTotalBytes,
                processingStage = DownloadProcessingStage.VERIFYING,
                verificationPolicy = task.verificationPolicy,
                message = "Verifying",
                clearTransferMetrics = true,
            )

            val sha = result.sha256Hex ?: sha256HexFromFile(partFile) { host.isStopped() }
            if (!sha.equals(task.expectedSha256, ignoreCase = true)) {
                partFile.delete()
                fail(
                    taskId = taskId,
                    modelId = task.modelId,
                    version = task.version,
                    reason = DownloadFailureReason.CHECKSUM_MISMATCH,
                    processingStage = DownloadProcessingStage.VERIFYING,
                    progressBytes = result.downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Checksum verification failed.",
                )
                return@withContext DownloadExecutionOutcome.FAILURE
            }

            val runtimeTag = provisioningStore.expectedRuntimeCompatibilityTag()
            if (task.runtimeCompatibility.isNotBlank() && task.runtimeCompatibility != runtimeTag) {
                partFile.delete()
                fail(
                    taskId = taskId,
                    modelId = task.modelId,
                    version = task.version,
                    reason = DownloadFailureReason.RUNTIME_INCOMPATIBLE,
                    processingStage = DownloadProcessingStage.VERIFYING,
                    progressBytes = result.downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Runtime compatibility mismatch.",
                )
                return@withContext DownloadExecutionOutcome.FAILURE
            }

            if (task.verificationPolicy.enforcesProvenance && !verifyProvenanceSignature(
                    issuer = task.provenanceIssuer,
                    modelId = task.modelId,
                    sha = sha,
                    expectedSignature = task.provenanceSignature,
                )
            ) {
                partFile.delete()
                fail(
                    taskId = taskId,
                    modelId = task.modelId,
                    version = task.version,
                    reason = DownloadFailureReason.PROVENANCE_MISMATCH,
                    processingStage = DownloadProcessingStage.VERIFYING,
                    progressBytes = result.downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Provenance signature mismatch.",
                )
                return@withContext DownloadExecutionOutcome.FAILURE
            }

            updateState(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                status = DownloadTaskStatus.VERIFYING,
                progressBytes = result.downloadedBytes,
                totalBytes = verificationTotalBytes,
                processingStage = DownloadProcessingStage.INSTALLING,
                verificationPolicy = task.verificationPolicy,
                message = "Installing",
                clearTransferMetrics = true,
            )
            if (!ModelInstallIo.replaceWithAtomicMove(source = partFile, destination = destinationFile)) {
                fail(
                    taskId = taskId,
                    modelId = task.modelId,
                    version = task.version,
                    reason = DownloadFailureReason.UNKNOWN,
                    processingStage = DownloadProcessingStage.INSTALLING,
                    progressBytes = result.downloadedBytes,
                    totalBytes = verificationTotalBytes,
                    message = "Failed to move downloaded model into install location.",
                )
                return@withContext DownloadExecutionOutcome.FAILURE
            }

            val installResult = provisioningStore.installDownloadedModel(
                modelId = task.modelId,
                version = task.version,
                absolutePath = destinationFile.absolutePath,
                sha256 = sha,
                provenanceIssuer = task.provenanceIssuer.ifBlank { "internal-release" },
                provenanceSignature = task.provenanceSignature.ifBlank {
                    sha256Hex("${task.provenanceIssuer.ifBlank { "internal-release" }}|${task.modelId}|$sha|v1".encodeToByteArray())
                },
                runtimeCompatibility = runtimeTag,
                fileSizeBytes = destinationFile.length().coerceAtLeast(0L),
                makeActive = task.verificationPolicy.enforcesProvenance,
            )

            runCatching {
                val metadataFile = File("${destinationFile.absolutePath}.meta.json")
                GgufMetadataExtractor.extractAndPersist(
                    modelFile = destinationFile,
                    metadataFile = metadataFile,
                )
            }.onFailure { error ->
                Log.w(
                    LOG_TAG,
                    "GGUF metadata extraction failed for ${task.modelId}@${task.version}: ${error.message}",
                )
            }

            runCatching {
                RuntimeBootstrapper.installProductionRuntime(appContext)
            }.onFailure { error ->
                Log.w(
                    LOG_TAG,
                    "Runtime refresh failed after installing ${task.modelId}@${task.version}: ${error.message}",
                )
            }

            val installedSize = destinationFile.length().coerceAtLeast(0L)
            updateState(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                status = if (installResult.isActive) {
                    DownloadTaskStatus.COMPLETED
                } else {
                    DownloadTaskStatus.INSTALLED_INACTIVE
                },
                progressBytes = installedSize,
                totalBytes = installedSize,
                processingStage = DownloadProcessingStage.INSTALLING,
                verificationPolicy = task.verificationPolicy,
                message = if (installResult.isActive) {
                    "Downloaded, verified, and active."
                } else {
                    "Downloaded and verified. Activation pending."
                },
                clearTransferMetrics = true,
            )
            DownloadExecutionOutcome.SUCCESS
        } catch (timeout: SocketTimeoutException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.TIMEOUT,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = "Download timed out.",
            )
            if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        } catch (storageError: InsufficientStorageException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.INSUFFICIENT_STORAGE,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = storageError.message ?: "Not enough storage to continue the download.",
            )
            DownloadExecutionOutcome.FAILURE
        } catch (cancellation: CancellationException) {
            if (host.isStopped()) {
                val current = ModelDownloadTaskStateStore.get(appContext, taskId)
                if (
                    current?.status == DownloadTaskStatus.PAUSED ||
                    current?.status == DownloadTaskStatus.CANCELLED
                ) {
                    return@withContext DownloadExecutionOutcome.SUCCESS
                }
                return@withContext when (host.stopDisposition()) {
                    DownloadStopDisposition.MARK_CANCELLED -> {
                        fail(
                            taskId = taskId,
                            modelId = task.modelId,
                            version = task.version,
                            reason = DownloadFailureReason.CANCELLED,
                            processingStage = current?.processingStage ?: DownloadProcessingStage.DOWNLOADING,
                            message = "Download cancelled.",
                        )
                        DownloadExecutionOutcome.FAILURE
                    }

                    DownloadStopDisposition.RESCHEDULE -> {
                        updateState(
                            taskId = taskId,
                            modelId = task.modelId,
                            version = task.version,
                            status = DownloadTaskStatus.QUEUED,
                            progressBytes = current?.progressBytes ?: partFile.length().coerceAtLeast(0L),
                            totalBytes = current?.totalBytes ?: task.totalBytes,
                            processingStage = DownloadProcessingStage.DOWNLOADING,
                            verificationPolicy = current?.verificationPolicy ?: task.verificationPolicy,
                            message = "Download interrupted. Rescheduling.",
                            clearTransferMetrics = true,
                        )
                        DownloadExecutionOutcome.RETRY
                    }
                }
            }
            throw cancellation
        } catch (error: IOException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.NETWORK_ERROR,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = error.message ?: "Download failed.",
            )
            if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        } catch (error: RuntimeException) {
            fail(
                taskId = taskId,
                modelId = task.modelId,
                version = task.version,
                reason = DownloadFailureReason.UNKNOWN,
                processingStage = ModelDownloadTaskStateStore.get(appContext, taskId)?.processingStage
                    ?: DownloadProcessingStage.DOWNLOADING,
                message = error.message ?: "Download failed unexpectedly.",
            )
            if (retryAllowed) {
                DownloadExecutionOutcome.RETRY
            } else {
                DownloadExecutionOutcome.FAILURE
            }
        }
    }

    private suspend fun downloadToPartial(
        task: DownloadTaskState,
        partFile: File,
        host: DownloadExecutionHost,
        network: Network?,
    ): DownloadResult {
        var existingBytes = partFile.length().coerceAtLeast(0L)
        var lastMetricBytes = task.progressBytes.coerceAtLeast(0L).coerceAtMost(existingBytes)
        var lastMetricEpochMs = task.lastProgressEpochMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        var smoothedSpeedBps = task.downloadSpeedBps
        var resumeEtag = task.resumeEtag
        var resumeLastModified = task.resumeLastModified
        var resumeHeaderValue = resolveIfRangeHeader(task)
        if (existingBytes > 0L && resumeHeaderValue == null) {
            partFile.delete()
            existingBytes = 0L
            lastMetricBytes = 0L
            lastMetricEpochMs = System.currentTimeMillis()
            resumeEtag = null
            resumeLastModified = null
            persistResumeMetadata(task.taskId, null, null)
        }

        val requestBuilder = Request.Builder().get().url(task.downloadUrl)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
            requestBuilder.header("If-Range", resumeHeaderValue!!)
        }
        DownloadHttpClient.forNetwork(network).newCall(requestBuilder.build()).execute().use { response ->
            val responseCode = response.code
            if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Download server returned HTTP $responseCode")
            }
            val baseline = resolveResumeTransferBaseline(
                responseCode = responseCode,
                existingBytes = existingBytes,
                metricBytes = lastMetricBytes,
                metricEpochMs = lastMetricEpochMs,
                nowEpochMs = System.currentTimeMillis(),
            )
            if (baseline.truncatePartialFile) {
                partFile.delete()
                resumeEtag = null
                resumeLastModified = null
                persistResumeMetadata(task.taskId, null, null)
            }
            existingBytes = baseline.existingBytes
            lastMetricBytes = baseline.metricBytes
            lastMetricEpochMs = baseline.metricEpochMs

            val capturedStrongEtag = response.header("ETag")?.trim()?.takeIf(::isStrongEtag)
            val capturedLastModified = response.header("Last-Modified")?.trim()?.takeIf { capturedStrongEtag == null && !it.isNullOrBlank() }
            if (capturedStrongEtag != null || capturedLastModified != null) {
                resumeEtag = capturedStrongEtag
                resumeLastModified = capturedLastModified
            }
            val responseBody = response.body ?: throw IOException("Download response body was empty.")
            val bodyLength = responseBody.contentLength().coerceAtLeast(0L)
            val expectedTotal = when {
                task.totalBytes > 0L -> task.totalBytes
                responseCode == HttpURLConnection.HTTP_PARTIAL -> existingBytes + bodyLength
                else -> bodyLength
            }
            val bodyStream = responseBody.byteStream()
            val digest = if (existingBytes == 0L) MessageDigest.getInstance("SHA-256") else null
            val append = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            bodyStream.use { input ->
                FileOutputStream(partFile, append).use { fileOutput ->
                    BufferedOutputStream(fileOutput, COPY_BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var downloaded = existingBytes
                        var bytesSinceUpdate = 0L
                        var lastBytesReceivedEpochMs = System.currentTimeMillis()
                        var slowWindowStartEpochMs = lastBytesReceivedEpochMs
                        var slowWindowStartBytes = downloaded
                        while (true) {
                            if (host.isStopped()) {
                                throw CancellationException("Download execution stopped")
                            }
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            val nowEpochMs = System.currentTimeMillis()
                            output.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            downloaded += read
                            bytesSinceUpdate += read
                            lastBytesReceivedEpochMs = nowEpochMs

                            if (nowEpochMs - slowWindowStartEpochMs >= SLOW_DRIP_WINDOW_MS) {
                                if (downloaded - slowWindowStartBytes < SLOW_DRIP_MIN_PROGRESS_BYTES) {
                                    throw SocketTimeoutException("Download throughput fell below the slow-drip threshold.")
                                }
                                slowWindowStartEpochMs = nowEpochMs
                                slowWindowStartBytes = downloaded
                            }

                            if (nowEpochMs - lastMetricEpochMs >= MIN_PROGRESS_INTERVAL_MS) {
                                ensureStorageCapacity(partFile = partFile, downloaded = downloaded, expectedTotal = expectedTotal)
                            }

                            val shouldFlushProgress = bytesSinceUpdate >= PROGRESS_UPDATE_STEP_BYTES &&
                                nowEpochMs - lastMetricEpochMs >= MIN_PROGRESS_INTERVAL_MS
                            val shouldFlushTimed = nowEpochMs - lastMetricEpochMs >= MIN_PROGRESS_INTERVAL_MS
                            val shouldFlushCompletion = expectedTotal > 0L && downloaded >= expectedTotal
                            if (shouldFlushProgress || shouldFlushTimed || shouldFlushCompletion) {
                                val percent = if (expectedTotal <= 0L) 0 else ((downloaded * 100L) / expectedTotal).toInt()
                                val transferMetrics = calculateTransferMetrics(
                                    previousBytes = lastMetricBytes,
                                    previousEpochMs = lastMetricEpochMs,
                                    currentBytes = downloaded,
                                    currentEpochMs = nowEpochMs,
                                    totalBytes = expectedTotal,
                                    previousSmoothedSpeedBps = smoothedSpeedBps,
                                )
                                host.updateNotification(taskId = task.taskId, modelId = task.modelId, percent = percent)
                                updateState(
                                    taskId = task.taskId,
                                    modelId = task.modelId,
                                    version = task.version,
                                    status = DownloadTaskStatus.DOWNLOADING,
                                    progressBytes = downloaded,
                                    totalBytes = expectedTotal,
                                    processingStage = DownloadProcessingStage.DOWNLOADING,
                                    verificationPolicy = task.verificationPolicy,
                                    message = "Downloading ($percent%)",
                                    transferMetrics = transferMetrics,
                                )
                                lastMetricBytes = downloaded
                                lastMetricEpochMs = nowEpochMs
                                smoothedSpeedBps = transferMetrics.downloadSpeedBps
                                bytesSinceUpdate = 0L
                            }

                            if (System.currentTimeMillis() - lastBytesReceivedEpochMs > HARD_STALL_TIMEOUT_MS) {
                                throw SocketTimeoutException("Download stalled.")
                            }
                        }
                        output.flush()
                        runCatching { fileOutput.fd.sync() }
                        if (expectedTotal > 0L && downloaded != expectedTotal) {
                            throw IOException("Download truncated. Expected $expectedTotal bytes, received $downloaded bytes.")
                        }
                        ensureStorageCapacity(partFile = partFile, downloaded = downloaded, expectedTotal = expectedTotal)
                        return DownloadResult(
                            downloadedBytes = downloaded,
                            computedTotalBytes = expectedTotal,
                            sha256Hex = digest?.digest()?.joinToString(separator = "") { "%02x".format(it) },
                            resumeEtag = resumeEtag,
                            resumeLastModified = resumeLastModified,
                        )
                    }
                }
            }
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
        transferMetrics: TransferMetrics? = null,
        clearTransferMetrics: Boolean = false,
    ) {
        val previous = ModelDownloadTaskStateStore.get(appContext, taskId)
        val next = (previous ?: DownloadTaskState(
            taskId = taskId,
            modelId = modelId,
            version = version,
            downloadUrl = "",
            expectedSha256 = "",
            provenanceIssuer = "",
            provenanceSignature = "",
            verificationPolicy = verificationPolicy,
            runtimeCompatibility = "",
            processingStage = processingStage,
            status = DownloadTaskStatus.QUEUED,
            progressBytes = 0L,
            totalBytes = 0L,
            updatedAtEpochMs = System.currentTimeMillis(),
        )).copy(
            status = status,
            progressBytes = progressBytes.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L).coerceAtLeast(progressBytes.coerceAtLeast(0L)),
            downloadSpeedBps = when {
                clearTransferMetrics -> null
                transferMetrics != null -> transferMetrics.downloadSpeedBps
                else -> previous?.downloadSpeedBps
            },
            etaSeconds = when {
                clearTransferMetrics -> null
                transferMetrics != null -> transferMetrics.etaSeconds
                else -> previous?.etaSeconds
            },
            lastProgressEpochMs = when {
                clearTransferMetrics -> null
                transferMetrics != null -> transferMetrics.lastProgressEpochMs
                else -> previous?.lastProgressEpochMs
            },
            processingStage = processingStage,
            verificationPolicy = verificationPolicy,
            updatedAtEpochMs = System.currentTimeMillis(),
            failureReason = reason,
            message = message,
        )
        ModelDownloadTaskStateStore.upsert(appContext, next)
    }

    private fun persistResumeMetadata(
        taskId: String,
        resumeEtag: String?,
        resumeLastModified: String?,
    ) {
        val previous = ModelDownloadTaskStateStore.get(appContext, taskId) ?: return
        ModelDownloadTaskStateStore.upsert(
            appContext,
            previous.copy(
                resumeEtag = resumeEtag?.takeIf(::isStrongEtag),
                resumeLastModified = if (resumeEtag == null) resumeLastModified?.takeIf { it.isNotBlank() } else null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
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
            ?: 0L)
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
            clearTransferMetrics = true,
        )
    }

    private fun ensureStorageCapacity(
        partFile: File,
        downloaded: Long,
        expectedTotal: Long,
    ) {
        val directory = partFile.parentFile ?: return
        val remainingBytes = (expectedTotal - downloaded).coerceAtLeast(0L)
        val requiredBytes = remainingBytes + STORAGE_SAFETY_HEADROOM_BYTES
        if (expectedTotal > 0L && directory.usableSpace < requiredBytes) {
            throw InsufficientStorageException(
                "Not enough free space to finish the download. Required ${requiredBytes}B, available ${directory.usableSpace}B.",
            )
        }
    }

    private fun hasNetworkConnection(): Boolean {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sha256HexFromFile(file: File, isCancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesHashed = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                if (isCancelled()) {
                    throw CancellationException("Verification cancelled")
                }
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
                bytesHashed += read
                if (bytesHashed >= VERIFICATION_CANCEL_CHECK_STEP_BYTES) {
                    bytesHashed = 0L
                    if (isCancelled()) {
                        throw CancellationException("Verification cancelled")
                    }
                }
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }

    private fun resolveIfRangeHeader(task: DownloadTaskState): String? {
        val strongEtag = task.resumeEtag?.trim()?.takeIf(::isStrongEtag)
        if (strongEtag != null) {
            return strongEtag
        }
        return task.resumeLastModified?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun isStrongEtag(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isNotEmpty() && !trimmed.startsWith("W/")
    }

    private class InsufficientStorageException(
        message: String,
    ) : IOException(message)

    companion object {
        private const val LOG_TAG = "ModelDownloadExecutor"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_UPDATE_STEP_BYTES = 1024 * 256
        private const val MIN_PROGRESS_INTERVAL_MS = 500L
        private const val VERIFICATION_CANCEL_CHECK_STEP_BYTES = 10L * 1024L * 1024L
        private const val HARD_STALL_TIMEOUT_MS = 30_000L
        private const val SLOW_DRIP_WINDOW_MS = 2 * 60 * 1000L
        private const val SLOW_DRIP_MIN_PROGRESS_BYTES = 512L * 1024L
        private const val STORAGE_SAFETY_HEADROOM_BYTES = 32L * 1024L * 1024L
    }
}

internal fun resolveResumeTransferBaseline(
    responseCode: Int,
    existingBytes: Long,
    metricBytes: Long,
    metricEpochMs: Long,
    nowEpochMs: Long,
): ResumeTransferBaseline {
    val safeNow = nowEpochMs.takeIf { it > 0L } ?: 1L
    val safeExisting = existingBytes.coerceAtLeast(0L)
    if (responseCode == HttpURLConnection.HTTP_OK && safeExisting > 0L) {
        return ResumeTransferBaseline(
            existingBytes = 0L,
            metricBytes = 0L,
            metricEpochMs = safeNow,
            truncatePartialFile = true,
        )
    }
    return ResumeTransferBaseline(
        existingBytes = safeExisting,
        metricBytes = metricBytes.coerceAtLeast(0L).coerceAtMost(safeExisting),
        metricEpochMs = metricEpochMs.takeIf { it > 0L } ?: safeNow,
        truncatePartialFile = false,
    )
}

internal fun calculateTransferMetrics(
    previousBytes: Long,
    previousEpochMs: Long,
    currentBytes: Long,
    currentEpochMs: Long,
    totalBytes: Long,
    previousSmoothedSpeedBps: Long? = null,
): TransferMetrics {
    val safeCurrentBytes = currentBytes.coerceAtLeast(previousBytes.coerceAtLeast(0L))
    val deltaBytes = (safeCurrentBytes - previousBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
    val elapsedMs = (currentEpochMs - previousEpochMs).coerceAtLeast(1L)
    val rawSpeedBps = if (deltaBytes > 0L) {
        ((deltaBytes * 1000L) / elapsedMs).coerceAtLeast(1L)
    } else {
        null
    }
    val speedBps = when {
        rawSpeedBps == null -> previousSmoothedSpeedBps
        previousSmoothedSpeedBps == null -> rawSpeedBps
        else -> ((EMA_ALPHA * rawSpeedBps) + ((1.0 - EMA_ALPHA) * previousSmoothedSpeedBps))
            .toLong()
            .coerceAtLeast(1L)
    }
    val etaSeconds = if ((speedBps ?: 0L) > 0L && totalBytes > 0L) {
        ((totalBytes - safeCurrentBytes).coerceAtLeast(0L) / (speedBps ?: 1L))
            .coerceAtLeast(0L)
    } else {
        null
    }
    return TransferMetrics(
        downloadSpeedBps = speedBps,
        etaSeconds = etaSeconds,
        lastProgressEpochMs = currentEpochMs.takeIf { it > 0L },
    )
}

private const val EMA_ALPHA = 0.2
