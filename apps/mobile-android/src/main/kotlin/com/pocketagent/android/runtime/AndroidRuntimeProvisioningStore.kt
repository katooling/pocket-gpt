package com.pocketagent.android.runtime

import android.content.Context
import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.ModelDownloadWorker
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.RuntimeConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ProvisionedModelState(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val absolutePath: String?,
    val sha256: String?,
    val importedAtEpochMs: Long?,
    val activeVersion: String?,
    val installedVersions: List<ModelVersionDescriptor>,
) {
    val isProvisioned: Boolean
        get() = !absolutePath.isNullOrBlank() && !sha256.isNullOrBlank()
}

data class RuntimeProvisioningSnapshot(
    val models: List<ProvisionedModelState>,
    val storageSummary: StorageSummary,
) {
    val allRequiredModelsProvisioned: Boolean
        get() = models.all { it.isProvisioned }
}

data class RuntimeModelImportResult(
    val modelId: String,
    val version: String,
    val absolutePath: String,
    val sha256: String,
    val copiedBytes: Long,
    val isActive: Boolean,
)

class AndroidRuntimeProvisioningStore(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelLocks: MutableMap<String, Any> = mutableMapOf()

    fun snapshot(): RuntimeProvisioningSnapshot {
        ensureMigrated()
        val models = REQUIRED_MODEL_SPECS.map { spec ->
            val versions = readInstalledVersions(spec)
            val activeVersion = prefs.readOptional(activeVersionKey(spec))
            val active = versions.firstOrNull { it.version == activeVersion }
            ProvisionedModelState(
                modelId = spec.modelId,
                displayName = spec.displayName,
                fileName = spec.fileName,
                absolutePath = active?.absolutePath,
                sha256 = active?.sha256,
                importedAtEpochMs = active?.importedAtEpochMs,
                activeVersion = activeVersion,
                installedVersions = versions,
            )
        }
        return RuntimeProvisioningSnapshot(
            models = models,
            storageSummary = storageSummary(),
        )
    }

    suspend fun importModel(
        modelId: String,
        sourceUri: Uri,
        version: String = generatedVersion(prefix = "manual"),
    ): RuntimeModelImportResult {
        val spec = requiredSpec(modelId)
        return withContext(Dispatchers.IO) {
            withModelLock(modelId) {
                ensureMigrated()
                val destinationDir = File(context.filesDir, MODEL_DIR_NAME).apply { mkdirs() }
                val destinationFile = File(destinationDir, fileNameForVersion(spec, version))
                val tempFile = File(destinationDir, ".${destinationFile.name}.tmp")
                val digest = MessageDigest.getInstance("SHA-256")

                val copiedBytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    BufferedInputStream(input).use { bufferedInput ->
                        BufferedOutputStream(tempFile.outputStream()).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
                            var total = 0L
                            while (true) {
                                val read = bufferedInput.read(buffer)
                                if (read <= 0) {
                                    break
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                total += read
                            }
                            output.flush()
                            total
                        }
                    }
                } ?: error("Unable to open selected model file.")

                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                if (!tempFile.renameTo(destinationFile)) {
                    error("Unable to persist imported model file.")
                }

                val sha = digest.digest().toHex()
                val result = upsertInstalledVersion(
                    spec = spec,
                    version = version,
                    absolutePath = destinationFile.absolutePath,
                    sha = sha,
                    provenanceIssuer = DEFAULT_ISSUER,
                    provenanceSignature = sha256Hex("$DEFAULT_ISSUER|${spec.modelId}|$sha|v1".encodeToByteArray()),
                    runtimeCompatibility = RUNTIME_COMPATIBILITY_TAG,
                    fileSizeBytes = copiedBytes,
                    makeActive = true,
                )
                result
            }
        }
    }

    suspend fun seedModelFromAbsolutePath(
        modelId: String,
        absolutePath: String,
        version: String = generatedVersion(prefix = "seed"),
    ): RuntimeModelImportResult {
        val spec = requiredSpec(modelId)
        return withContext(Dispatchers.IO) {
            withModelLock(modelId) {
                ensureMigrated()
                val file = File(absolutePath)
                require(file.exists() && file.isFile) {
                    "Model path does not exist: $absolutePath"
                }
                val sha = sha256HexFromFile(file)
                val result = upsertInstalledVersion(
                    spec = spec,
                    version = version,
                    absolutePath = file.absolutePath,
                    sha = sha,
                    provenanceIssuer = DEFAULT_ISSUER,
                    provenanceSignature = sha256Hex("$DEFAULT_ISSUER|${spec.modelId}|$sha|v1".encodeToByteArray()),
                    runtimeCompatibility = RUNTIME_COMPATIBILITY_TAG,
                    fileSizeBytes = file.length().coerceAtLeast(0L),
                    makeActive = true,
                )
                result
            }
        }
    }

    fun installDownloadedModel(
        modelId: String,
        version: String,
        absolutePath: String,
        sha256: String,
        provenanceIssuer: String,
        provenanceSignature: String,
        runtimeCompatibility: String,
        fileSizeBytes: Long,
        makeActive: Boolean = false,
    ): RuntimeModelImportResult {
        val spec = requiredSpec(modelId)
        return withModelLock(modelId) {
            ensureMigrated()
            val result = upsertInstalledVersion(
                spec = spec,
                version = version,
                absolutePath = absolutePath,
                sha = sha256,
                provenanceIssuer = provenanceIssuer.ifBlank { DEFAULT_ISSUER },
                provenanceSignature = provenanceSignature.ifBlank {
                    sha256Hex("${provenanceIssuer.ifBlank { DEFAULT_ISSUER }}|${spec.modelId}|$sha256|v1".encodeToByteArray())
                },
                runtimeCompatibility = runtimeCompatibility.ifBlank { RUNTIME_COMPATIBILITY_TAG },
                fileSizeBytes = fileSizeBytes.coerceAtLeast(0L),
                makeActive = makeActive,
            )
            result
        }
    }

    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        ensureMigrated()
        return readInstalledVersions(requiredSpec(modelId))
    }

    fun setActiveVersion(modelId: String, version: String): Boolean {
        val spec = requiredSpec(modelId)
        return withModelLock(modelId) {
            ensureMigrated()
            val versions = readInstalledVersions(spec)
            versions.firstOrNull { it.version == version } ?: return@withModelLock false
            prefs.edit()
                .putString(activeVersionKey(spec), version)
                .apply()
            true
        }
    }

    fun removeVersion(modelId: String, version: String): Boolean {
        val spec = requiredSpec(modelId)
        return withModelLock(modelId) {
            ensureMigrated()
            val versions = readStoredVersionEntries(spec).toMutableList()
            val activeVersion = prefs.readOptional(activeVersionKey(spec))
            if (activeVersion == version) {
                return@withModelLock false
            }
            val target = versions.firstOrNull { it.version == version } ?: return@withModelLock false
            versions.removeIf { it.version == version }
            writeStoredVersionEntries(spec, versions)
            deleteManagedFileIfOrphaned(
                absolutePath = target.absolutePath,
                remainingEntries = versions,
            )
            true
        }
    }

    fun storageSummary(): StorageSummary {
        ensureMigrated()
        val filesDir = context.filesDir
        val usedByModels = REQUIRED_MODEL_SPECS
            .flatMap { spec -> readStoredVersionEntries(spec) }
            .sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
        val tempDownloadDir = File(context.filesDir, ModelDownloadWorker.DOWNLOAD_DIR)
        val tempBytes = tempDownloadDir
            .takeIf { it.exists() && it.isDirectory }
            ?.listFiles()
            ?.sumOf { it.length().coerceAtLeast(0L) }
            ?: 0L
        return StorageSummary(
            totalBytes = filesDir.totalSpace.coerceAtLeast(0L),
            freeBytes = filesDir.usableSpace.coerceAtLeast(0L),
            usedByModelsBytes = usedByModels,
            tempDownloadBytes = tempBytes,
        )
    }

    fun runtimeConfig(): RuntimeConfig {
        ensureMigrated()
        val payloadByModelId = mutableMapOf<String, ByteArray>()
        val filePathByModelId = mutableMapOf<String, String>()
        val shaByModelId = mutableMapOf<String, String>()
        val issuerByModelId = mutableMapOf<String, String>()
        val signatureByModelId = mutableMapOf<String, String>()

        REQUIRED_MODEL_SPECS.forEach { spec ->
            val versions = readInstalledVersions(spec)
            val activeVersion = prefs.readOptional(activeVersionKey(spec))
            val active = versions.firstOrNull { it.version == activeVersion }

            val path = active?.absolutePath.orEmpty()
            val sha = active?.sha256.orEmpty()
            val issuer = active?.provenanceIssuer ?: DEFAULT_ISSUER
            val signature = active?.provenanceSignature.orEmpty()

            payloadByModelId[spec.modelId] = "provisioned:${spec.modelId}:$path".encodeToByteArray()
            filePathByModelId[spec.modelId] = path
            shaByModelId[spec.modelId] = sha
            issuerByModelId[spec.modelId] = issuer
            signatureByModelId[spec.modelId] = signature
        }

        return RuntimeConfig(
            artifactPayloadByModelId = payloadByModelId,
            artifactFilePathByModelId = filePathByModelId,
            artifactSha256ByModelId = shaByModelId,
            artifactProvenanceIssuerByModelId = issuerByModelId,
            artifactProvenanceSignatureByModelId = signatureByModelId,
            runtimeCompatibilityTag = RUNTIME_COMPATIBILITY_TAG,
            requireNativeRuntimeForStartupChecks = true,
            prefixCacheEnabled = true,
            prefixCacheStrict = false,
            responseCacheTtlSec = 0L,
            responseCacheMaxEntries = 0,
            streamContractV2Enabled = true,
        )
    }

    fun expectedRuntimeCompatibilityTag(): String = RUNTIME_COMPATIBILITY_TAG

    private fun upsertInstalledVersion(
        spec: ModelSpec,
        version: String,
        absolutePath: String,
        sha: String,
        provenanceIssuer: String,
        provenanceSignature: String,
        runtimeCompatibility: String,
        fileSizeBytes: Long,
        makeActive: Boolean,
    ): RuntimeModelImportResult {
        val entries = readStoredVersionEntries(spec).toMutableList()
        val now = System.currentTimeMillis()
        val sanitizedVersion = sanitizeVersion(version)
        val normalizedPath = normalizeAbsolutePath(absolutePath)
        val entry = StoredVersionEntry(
            version = sanitizedVersion,
            absolutePath = normalizedPath,
            sha256 = sha,
            provenanceIssuer = provenanceIssuer,
            provenanceSignature = provenanceSignature,
            runtimeCompatibility = runtimeCompatibility,
            fileSizeBytes = fileSizeBytes.coerceAtLeast(0L),
            importedAtEpochMs = now,
        )
        // Keep one canonical entry per source path so repeated stage seeding does not create unbounded duplicates.
        entries.removeAll { it.version == sanitizedVersion || it.absolutePath == normalizedPath }
        entries.add(entry)
        writeStoredVersionEntries(spec, entries)
        if (makeActive || prefs.readOptional(activeVersionKey(spec)).isNullOrBlank()) {
            prefs.edit().putString(activeVersionKey(spec), sanitizedVersion).apply()
        }
        val isActive = prefs.readOptional(activeVersionKey(spec)) == sanitizedVersion
        return RuntimeModelImportResult(
            modelId = spec.modelId,
            version = sanitizedVersion,
            absolutePath = normalizedPath,
            sha256 = sha,
            copiedBytes = fileSizeBytes.coerceAtLeast(0L),
            isActive = isActive,
        )
    }

    private fun readInstalledVersions(spec: ModelSpec): List<ModelVersionDescriptor> {
        val activeVersion = prefs.readOptional(activeVersionKey(spec))
        return readStoredVersionEntries(spec)
            .sortedByDescending { it.importedAtEpochMs }
            .map { entry ->
                ModelVersionDescriptor(
                    modelId = spec.modelId,
                    version = entry.version,
                    displayName = spec.displayName,
                    absolutePath = entry.absolutePath,
                    sha256 = entry.sha256,
                    provenanceIssuer = entry.provenanceIssuer,
                    provenanceSignature = entry.provenanceSignature,
                    runtimeCompatibility = entry.runtimeCompatibility,
                    fileSizeBytes = entry.fileSizeBytes,
                    importedAtEpochMs = entry.importedAtEpochMs,
                    isActive = entry.version == activeVersion,
                )
            }
    }

    private fun readStoredVersionEntries(spec: ModelSpec): List<StoredVersionEntry> {
        val raw = prefs.getString(versionsKey(spec), null).orEmpty().trim()
        if (raw.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    decodeStoredVersion(item)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeStoredVersionEntries(spec: ModelSpec, entries: List<StoredVersionEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("version", entry.version)
                    .put("absolutePath", entry.absolutePath)
                    .put("sha256", entry.sha256)
                    .put("provenanceIssuer", entry.provenanceIssuer)
                    .put("provenanceSignature", entry.provenanceSignature)
                    .put("runtimeCompatibility", entry.runtimeCompatibility)
                    .put("fileSizeBytes", entry.fileSizeBytes)
                    .put("importedAtEpochMs", entry.importedAtEpochMs),
            )
        }
        prefs.edit().putString(versionsKey(spec), array.toString()).apply()
    }

    private fun decodeStoredVersion(json: JSONObject): StoredVersionEntry? {
        val version = sanitizeVersion(json.optString("version", "").trim())
        val absolutePath = normalizeAbsolutePath(json.optString("absolutePath", "").trim())
        val sha = json.optString("sha256", "").trim()
        if (version.isEmpty() || absolutePath.isEmpty() || sha.isEmpty()) {
            return null
        }
        return StoredVersionEntry(
            version = version,
            absolutePath = absolutePath,
            sha256 = sha,
            provenanceIssuer = json.optString("provenanceIssuer", DEFAULT_ISSUER).trim().ifEmpty { DEFAULT_ISSUER },
            provenanceSignature = json.optString("provenanceSignature", "").trim(),
            runtimeCompatibility = json.optString("runtimeCompatibility", RUNTIME_COMPATIBILITY_TAG)
                .trim()
                .ifEmpty { RUNTIME_COMPATIBILITY_TAG },
            fileSizeBytes = json.optLong("fileSizeBytes", 0L).coerceAtLeast(0L),
            importedAtEpochMs = json.optLong("importedAtEpochMs", System.currentTimeMillis()),
        )
    }

    private fun ensureMigrated() {
        synchronized(MIGRATION_LOCK) {
            REQUIRED_MODEL_SPECS.forEach { spec ->
                if (prefs.contains(versionsKey(spec))) {
                    return@forEach
                }
                val legacyPath = prefs.readOptional(spec.pathKey)
                val legacySha = prefs.readOptional(spec.shaKey)
                if (legacyPath.isNullOrBlank() || legacySha.isNullOrBlank()) {
                    prefs.edit()
                        .putString(versionsKey(spec), "[]")
                        .remove(activeVersionKey(spec))
                        .remove(spec.pathKey)
                        .remove(spec.shaKey)
                        .remove(spec.issuerKey)
                        .remove(spec.signatureKey)
                        .remove(spec.importedAtKey)
                        .apply()
                    return@forEach
                }
                val version = INITIAL_MIGRATION_VERSION
                val issuer = prefs.readOptional(spec.issuerKey) ?: DEFAULT_ISSUER
                val signature = prefs.readOptional(spec.signatureKey)
                    ?: sha256Hex("$issuer|${spec.modelId}|$legacySha|v1".encodeToByteArray())
                val importedAt = prefs.readOptionalLong(spec.importedAtKey) ?: System.currentTimeMillis()
                val fileSizeBytes = File(legacyPath).takeIf { it.exists() && it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
                val entry = StoredVersionEntry(
                    version = version,
                    absolutePath = normalizeAbsolutePath(legacyPath),
                    sha256 = legacySha,
                    provenanceIssuer = issuer,
                    provenanceSignature = signature,
                    runtimeCompatibility = RUNTIME_COMPATIBILITY_TAG,
                    fileSizeBytes = fileSizeBytes,
                    importedAtEpochMs = importedAt,
                )
                writeStoredVersionEntries(spec, listOf(entry))
                prefs.edit()
                    .putString(activeVersionKey(spec), version)
                    .remove(spec.pathKey)
                    .remove(spec.shaKey)
                    .remove(spec.issuerKey)
                    .remove(spec.signatureKey)
                    .remove(spec.importedAtKey)
                    .apply()
            }
        }
    }

    private fun requiredSpec(modelId: String): ModelSpec {
        return REQUIRED_MODEL_SPECS.firstOrNull { it.modelId == modelId }
            ?: error("Unsupported model id for provisioning: $modelId")
    }

    private fun versionsKey(spec: ModelSpec): String = "model_versions_json_${spec.prefTag}"

    private fun activeVersionKey(spec: ModelSpec): String = "model_active_version_${spec.prefTag}"

    private fun fileNameForVersion(spec: ModelSpec, version: String): String {
        val suffix = sanitizeVersion(version)
        val dotIndex = spec.fileName.lastIndexOf('.')
        return if (dotIndex <= 0) {
            "${spec.fileName}-$suffix"
        } else {
            val base = spec.fileName.substring(0, dotIndex)
            val ext = spec.fileName.substring(dotIndex)
            "$base-$suffix$ext"
        }
    }

    private fun generatedVersion(prefix: String): String {
        return "$prefix-${System.currentTimeMillis()}"
    }

    private fun normalizeAbsolutePath(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }
        return runCatching { File(trimmed).canonicalPath }
            .getOrElse { File(trimmed).absolutePath }
    }

    private fun deleteManagedFileIfOrphaned(
        absolutePath: String,
        remainingEntries: List<StoredVersionEntry>,
    ) {
        if (absolutePath.isBlank()) {
            return
        }
        if (remainingEntries.any { it.absolutePath == absolutePath }) {
            return
        }
        val target = File(absolutePath)
        if (!isManagedModelFile(target)) {
            return
        }
        target.takeIf { it.exists() }?.delete()
    }

    private fun isManagedModelFile(file: File): Boolean {
        val runtimeDir = File(context.filesDir, MODEL_DIR_NAME)
        val downloadDir = File(context.filesDir, ModelDownloadWorker.DOWNLOAD_DIR)
        return isPathWithin(file, runtimeDir) || isPathWithin(file, downloadDir)
    }

    private fun isPathWithin(candidate: File, root: File): Boolean {
        val candidatePath = runCatching { candidate.canonicalPath }.getOrNull() ?: return false
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        return candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
    }

    private fun sanitizeVersion(raw: String): String {
        return raw
            .trim()
            .ifEmpty { generatedVersion(prefix = "v") }
            .replace(Regex("[^a-zA-Z0-9._-]"), "-")
            .lowercase(Locale.US)
    }

    private fun sha256HexFromFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun lockForModel(modelId: String): Any {
        return synchronized(modelLocks) {
            modelLocks.getOrPut(modelId) { Any() }
        }
    }

    private fun <T> withModelLock(modelId: String, block: () -> T): T {
        val lock = lockForModel(modelId)
        return synchronized(lock) {
            block()
        }
    }

    private data class StoredVersionEntry(
        val version: String,
        val absolutePath: String,
        val sha256: String,
        val provenanceIssuer: String,
        val provenanceSignature: String,
        val runtimeCompatibility: String,
        val fileSizeBytes: Long,
        val importedAtEpochMs: Long,
    )

    private data class ModelSpec(
        val modelId: String,
        val displayName: String,
        val fileName: String,
        val prefTag: String,
        val pathKey: String,
        val shaKey: String,
        val issuerKey: String,
        val signatureKey: String,
        val importedAtKey: String,
    )

    companion object {
        private const val PREFS_NAME = "pocketagent_runtime_models"
        private const val MODEL_DIR_NAME = "runtime-models"
        private const val DEFAULT_ISSUER = "internal-release"
        private const val RUNTIME_COMPATIBILITY_TAG = "android-arm64-v8a"
        private const val COPY_BUFFER_SIZE_BYTES = 1024 * 1024
        private const val INITIAL_MIGRATION_VERSION = "1.0.0-initial"
        private val MIGRATION_LOCK = Any()

        private val REQUIRED_MODEL_SPECS = listOf(
            ModelSpec(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                displayName = "Qwen 3.5 0.8B (Q4)",
                fileName = "qwen3.5-0.8b-q4.gguf",
                prefTag = "0_8b",
                pathKey = "model_0_8b_path",
                shaKey = "model_0_8b_sha256",
                issuerKey = "model_0_8b_issuer",
                signatureKey = "model_0_8b_signature",
                importedAtKey = "model_0_8b_imported_at",
            ),
            ModelSpec(
                modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                displayName = "Qwen 3.5 2B (Q4)",
                fileName = "qwen3.5-2b-q4.gguf",
                prefTag = "2b",
                pathKey = "model_2b_path",
                shaKey = "model_2b_sha256",
                issuerKey = "model_2b_issuer",
                signatureKey = "model_2b_signature",
                importedAtKey = "model_2b_imported_at",
            ),
        )
    }
}

private fun ByteArray.toHex(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun android.content.SharedPreferences.readOptional(key: String): String? {
    return getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun android.content.SharedPreferences.readOptionalLong(key: String): Long? {
    return if (contains(key)) getLong(key, 0L) else null
}
