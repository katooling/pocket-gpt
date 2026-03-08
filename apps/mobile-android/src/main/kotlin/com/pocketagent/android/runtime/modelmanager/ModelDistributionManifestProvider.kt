package com.pocketagent.android.runtime.modelmanager

import android.content.Context
import com.pocketagent.android.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ModelDistributionManifestProvider(
    private val context: Context?,
    private val endpointOverride: String? = null,
    private val bundledAssetPath: String = DEFAULT_BUNDLED_MANIFEST_ASSET,
    private val remoteManifestLoader: suspend (String) -> String = { endpoint ->
        fetchRemoteManifest(endpoint)
    },
    private val bundledManifestLoader: (() -> String?)? = null,
) {
    suspend fun loadManifest(): ModelDistributionManifest = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val bundled = loadBundledManifest()
        val endpoint = (endpointOverride ?: BuildConfig.MODEL_MANIFEST_URL).trim()
        if (endpoint.isEmpty()) {
            return@withContext bundled.copy(
                source = ManifestSource.BUNDLED,
                syncedAtEpochMs = now,
            )
        }

        val remotePayload = runCatching { remoteManifestLoader(endpoint) }
        val remoteManifest = remotePayload.mapCatching { parseManifest(it) }
        if (remoteManifest.isSuccess) {
            val parsedRemote = remoteManifest.getOrThrow()
            val merged = mergeManifests(bundled, parsedRemote)
            return@withContext merged.copy(
                source = if (bundled.models.isEmpty()) {
                    ManifestSource.REMOTE
                } else {
                    ManifestSource.BUNDLED_AND_REMOTE
                },
                syncedAtEpochMs = now,
                lastError = mergeWarnings(bundled.lastError, parsedRemote.lastError),
            )
        }

        val remoteError = remoteManifest.exceptionOrNull()?.message
            ?: remotePayload.exceptionOrNull()?.message
            ?: "unknown remote catalog error"
        return@withContext bundled.copy(
            source = ManifestSource.BUNDLED,
            syncedAtEpochMs = now,
            lastError = mergeWarnings(
                bundled.lastError,
                "MODEL_MANIFEST_REMOTE_FETCH_FAILED:$remoteError",
            ),
        )
    }

    private fun loadBundledManifest(): ModelDistributionManifest {
        if (bundledManifestLoader == null && context == null) {
            return ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_UNAVAILABLE:context_unavailable",
            )
        }
        val payload = runCatching {
            bundledManifestLoader?.invoke()
                ?: context?.assets?.open(bundledAssetPath)?.bufferedReader()?.use { it.readText() }
        }.getOrElse {
            return ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_UNAVAILABLE:asset_missing:$bundledAssetPath",
            )
        }
        if (payload.isNullOrBlank()) {
            return ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_INVALID:asset_empty:$bundledAssetPath",
            )
        }
        return runCatching { parseManifest(payload) }.getOrElse { error ->
            ModelDistributionManifest(
                models = emptyList(),
                source = ManifestSource.BUNDLED,
                lastError = "MODEL_MANIFEST_BUNDLED_INVALID:parse_failed:${error.message ?: "invalid json"}",
            )
        }
    }

    private fun parseManifest(raw: String): ModelDistributionManifest {
        val root = JSONObject(raw)
        val modelsJson = root.optJSONArray("models") ?: JSONArray()
        val warnings = mutableListOf<String>()
        val byModelId = linkedMapOf<String, ParsedModelAccumulator>()
        for (index in 0 until modelsJson.length()) {
            val item = modelsJson.optJSONObject(index) ?: continue
            val modelId = item.optString("modelId", "").trim()
            val displayName = item.optString("displayName", modelId).trim()
            if (modelId.isEmpty()) {
                warnings += "Dropped manifest model entry at index=$index: missing modelId."
                continue
            }
            val accumulator = byModelId.getOrPut(modelId) {
                ParsedModelAccumulator(displayName = displayName.ifBlank { modelId })
            }
            if (accumulator.displayName != displayName && displayName.isNotBlank()) {
                warnings += "Model '$modelId' declared multiple display names; keeping '${accumulator.displayName}'."
            }
            val versionsJson = item.optJSONArray("versions") ?: JSONArray()
            for (v in 0 until versionsJson.length()) {
                val versionItem = versionsJson.optJSONObject(v) ?: continue
                val version = versionItem.optString("version", "").trim()
                val downloadUrl = versionItem.optString("downloadUrl", "").trim()
                val sha = versionItem.optString("expectedSha256", "").trim()
                val issuer = versionItem.optString("provenanceIssuer", "").trim()
                val signature = versionItem.optString("provenanceSignature", "").trim()
                val verificationPolicyRaw = versionItem
                    .optString("verificationPolicy", DownloadVerificationPolicy.INTEGRITY_ONLY.name)
                    .trim()
                val runtimeCompatibility = versionItem
                    .optString("runtimeCompatibility", "android-arm64-v8a")
                    .trim()
                    .ifEmpty { "android-arm64-v8a" }
                val fileSizeBytes = versionItem.optLong("fileSizeBytes", 0L)
                val verificationPolicy = parseVerificationPolicy(verificationPolicyRaw)
                    ?: run {
                        warnings += "Version '$modelId/$version' has invalid verificationPolicy '$verificationPolicyRaw'; using INTEGRITY_ONLY."
                        DownloadVerificationPolicy.INTEGRITY_ONLY
                    }
                val rejectionReason = validateVersionEntry(
                    version = version,
                    downloadUrl = downloadUrl,
                    expectedSha256 = sha,
                    fileSizeBytes = fileSizeBytes,
                )
                if (rejectionReason != null) {
                    warnings += "Dropped $modelId/$version: $rejectionReason"
                    continue
                }
                if (accumulator.versionsByVersion.containsKey(version)) {
                    warnings += "Dropped duplicate $modelId/$version entry from manifest."
                    continue
                }
                accumulator.versionsByVersion[version] = ModelDistributionVersion(
                    modelId = modelId,
                    version = version,
                    downloadUrl = downloadUrl,
                    expectedSha256 = sha,
                    provenanceIssuer = issuer,
                    provenanceSignature = signature,
                    runtimeCompatibility = runtimeCompatibility,
                    fileSizeBytes = fileSizeBytes,
                    verificationPolicy = verificationPolicy,
                )
            }
        }
        val models = byModelId.entries.mapNotNull { (modelId, accumulator) ->
            val versions = accumulator.versionsByVersion.values.sortedByDescending { it.version }
            if (versions.isEmpty()) {
                warnings += "Dropped model '$modelId': no valid versions."
                null
            } else {
                ModelDistributionModel(
                    modelId = modelId,
                    displayName = accumulator.displayName,
                    versions = versions,
                )
            }
        }
        return ModelDistributionManifest(
            models = models,
            lastError = summarizeWarnings(warnings),
        )
    }

    private data class ParsedModelAccumulator(
        val displayName: String,
        val versionsByVersion: LinkedHashMap<String, ModelDistributionVersion> = linkedMapOf(),
    )

    private fun mergeManifests(
        bundled: ModelDistributionManifest,
        remote: ModelDistributionManifest,
    ): ModelDistributionManifest {
        val bundledById = bundled.models.associateBy { it.modelId }
        val remoteById = remote.models.associateBy { it.modelId }
        val mergedModels = (bundledById.keys + remoteById.keys).sorted().mapNotNull { modelId ->
            val bundledModel = bundledById[modelId]
            val remoteModel = remoteById[modelId]
            val displayName = remoteModel?.displayName?.takeIf { it.isNotBlank() }
                ?: bundledModel?.displayName?.takeIf { it.isNotBlank() }
                ?: modelId
            val versionByKey = linkedMapOf<String, ModelDistributionVersion>()
            bundledModel?.versions?.forEach { version ->
                versionByKey[version.version] = version
            }
            remoteModel?.versions?.forEach { version ->
                versionByKey[version.version] = version
            }
            if (versionByKey.isEmpty()) {
                null
            } else {
                ModelDistributionModel(
                    modelId = modelId,
                    displayName = displayName,
                    versions = versionByKey.values.sortedByDescending { it.version },
                )
            }
        }
        return ModelDistributionManifest(models = mergedModels)
    }

    private fun validateVersionEntry(
        version: String,
        downloadUrl: String,
        expectedSha256: String,
        fileSizeBytes: Long,
    ): String? {
        if (version.isBlank()) {
            return "missing version"
        }
        if (downloadUrl.isBlank()) {
            return "missing downloadUrl"
        }
        if (!isHttpsUrl(downloadUrl)) {
            return "downloadUrl must be HTTPS"
        }
        if (expectedSha256.isBlank()) {
            return "missing expectedSha256"
        }
        if (!SHA256_HEX_REGEX.matches(expectedSha256)) {
            return "expectedSha256 must be 64 lowercase/uppercase hex chars"
        }
        if (fileSizeBytes <= 0L) {
            return "fileSizeBytes must be > 0"
        }
        return null
    }

    private fun isHttpsUrl(url: String): Boolean {
        return runCatching {
            URL(url).protocol.equals("https", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun parseVerificationPolicy(raw: String): DownloadVerificationPolicy? {
        if (raw.isBlank()) {
            return DownloadVerificationPolicy.INTEGRITY_ONLY
        }
        val normalized = raw.trim().uppercase()
        return runCatching { DownloadVerificationPolicy.valueOf(normalized) }.getOrNull()
    }

    private fun mergeWarnings(vararg warnings: String?): String? {
        val merged = warnings
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .distinct()
        return summarizeWarnings(merged)
    }

    private fun summarizeWarnings(warnings: List<String>): String? {
        if (warnings.isEmpty()) {
            return null
        }
        val visible = warnings.take(MAX_WARNING_MESSAGES)
        val suffix = if (warnings.size > visible.size) {
            " (+${warnings.size - visible.size} more)"
        } else {
            ""
        }
        return visible.joinToString(separator = " | ") + suffix
    }

    companion object {
        private const val DEFAULT_BUNDLED_MANIFEST_ASSET = "model-distribution-catalog.json"
        private const val MAX_WARNING_MESSAGES = 6
        private val SHA256_HEX_REGEX = Regex("^[a-fA-F0-9]{64}$")

        private fun fetchRemoteManifest(endpoint: String): String {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            return try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    error("HTTP $responseCode")
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
