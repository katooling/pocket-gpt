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
    private val context: Context,
) {
    suspend fun loadManifest(): ModelDistributionManifest = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.MODEL_MANIFEST_URL.trim()
        if (endpoint.isEmpty()) {
            return@withContext ModelDistributionManifest(models = emptyList())
        }
        val payload = runCatching {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { return@withContext ModelDistributionManifest(models = emptyList()) }

        parseManifest(payload)
    }

    private fun parseManifest(raw: String): ModelDistributionManifest {
        return runCatching {
            val root = JSONObject(raw)
            val modelsJson = root.optJSONArray("models") ?: JSONArray()
            val models = buildList {
                for (index in 0 until modelsJson.length()) {
                    val item = modelsJson.optJSONObject(index) ?: continue
                    val modelId = item.optString("modelId", "").trim()
                    val displayName = item.optString("displayName", modelId).trim()
                    if (modelId.isEmpty()) {
                        continue
                    }
                    val versionsJson = item.optJSONArray("versions") ?: JSONArray()
                    val versions = buildList {
                        for (v in 0 until versionsJson.length()) {
                            val versionItem = versionsJson.optJSONObject(v) ?: continue
                            val version = versionItem.optString("version", "").trim()
                            val downloadUrl = versionItem.optString("downloadUrl", "").trim()
                            val sha = versionItem.optString("expectedSha256", "").trim()
                            val issuer = versionItem.optString("provenanceIssuer", "").trim()
                            val signature = versionItem.optString("provenanceSignature", "").trim()
                            val runtimeCompatibility = versionItem
                                .optString("runtimeCompatibility", "android-arm64-v8a")
                                .trim()
                                .ifEmpty { "android-arm64-v8a" }
                            val fileSizeBytes = versionItem.optLong("fileSizeBytes", 0L)
                            if (version.isEmpty() || downloadUrl.isEmpty() || sha.isEmpty()) {
                                continue
                            }
                            add(
                                ModelDistributionVersion(
                                    modelId = modelId,
                                    version = version,
                                    downloadUrl = downloadUrl,
                                    expectedSha256 = sha,
                                    provenanceIssuer = issuer,
                                    provenanceSignature = signature,
                                    runtimeCompatibility = runtimeCompatibility,
                                    fileSizeBytes = fileSizeBytes.coerceAtLeast(0L),
                                ),
                            )
                        }
                    }
                    add(
                        ModelDistributionModel(
                            modelId = modelId,
                            displayName = displayName,
                            versions = versions.sortedByDescending { it.version },
                        ),
                    )
                }
            }
            ModelDistributionManifest(models = models)
        }.getOrElse { ModelDistributionManifest(models = emptyList()) }
    }
}
