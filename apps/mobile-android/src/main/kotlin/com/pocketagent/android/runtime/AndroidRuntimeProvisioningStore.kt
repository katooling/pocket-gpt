package com.pocketagent.android.runtime

import android.content.Context
import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.ModelDownloadWorker
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry
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
    val localFileMissing: Boolean = false,
) {
    val isProvisioned: Boolean
        get() = !localFileMissing && !absolutePath.isNullOrBlank() && !sha256.isNullOrBlank()
}

data class ProvisioningRecoverySignal(
    val code: String,
    val message: String,
    val technicalDetail: String,
)

data class RuntimeProvisioningSnapshot(
    val models: List<ProvisionedModelState>,
    val storageSummary: StorageSummary,
    val requiredModelIds: Set<String>,
    val recoverableCorruptions: List<ProvisioningRecoverySignal> = emptyList(),
) {
    val verifiedActiveModelCount: Int
        get() = models.count { it.modelId in requiredModelIds && it.isProvisioned && !it.activeVersion.isNullOrBlank() }

    val missingRequiredModelIds: Set<String>
        get() = requiredModelIds.filterNot { modelId ->
            models.any { it.modelId == modelId && it.isProvisioned && !it.activeVersion.isNullOrBlank() }
        }.toSet()

    val readiness: ProvisioningReadiness
        get() = when {
            verifiedActiveModelCount <= 0 -> ProvisioningReadiness.BLOCKED
            missingRequiredModelIds.isEmpty() -> ProvisioningReadiness.READY
            else -> ProvisioningReadiness.DEGRADED
        }

    val allRequiredModelsProvisioned: Boolean
        get() = models.filter { it.modelId in requiredModelIds }.all { it.isProvisioned }

    val hasRecoverableCorruption: Boolean
        get() = recoverableCorruptions.isNotEmpty()
}

enum class ProvisioningReadiness {
    READY,
    DEGRADED,
    BLOCKED,
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
    private val migrationCorruptionSignals: MutableList<ProvisioningRecoverySignal> = mutableListOf()
    private val baselineModelIdSet = BASELINE_MODEL_SPECS.mapTo(linkedSetOf()) { it.modelId }
    private val runtimeProfile: ModelRuntimeProfile = ModelRuntimeProfile.PROD
    private val startupCandidateModelIds: Set<String> = ModelRegistry.default()
        .startupPolicy(profile = runtimeProfile)
        .candidateModelIds
        .toSet()
        .ifEmpty { baselineModelIdSet }

    fun snapshot(): RuntimeProvisioningSnapshot {
        ensureMigrated()
        val corruptionSignals = mutableListOf<ProvisioningRecoverySignal>()
        corruptionSignals += drainMigrationCorruptionSignals()
        val dynamicIdsResult = readDynamicModelIdsWithDiagnostics()
        dynamicIdsResult.signal?.let { corruptionSignals += it }
        val specs = allModelSpecs(dynamicIdsResult.ids)
        val models = specs.map { spec ->
            val versionResult = readInstalledVersionsWithDiagnostics(spec)
            versionResult.signal?.let { corruptionSignals += it }
            val versions = versionResult.versions
            val activeVersion = prefs.readOptional(activeVersionKey(spec))
            val active = versions.firstOrNull { it.version == activeVersion }
            val localFileMissing = active?.absolutePath
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> !File(path).exists() }
                ?: false
            if (localFileMissing) {
                corruptionSignals += ProvisioningRecoverySignal(
                    code = "MODEL_LOCAL_FILE_MISSING",
                    message = "Active model file is missing for ${spec.modelId}. Re-download or import a local file.",
                    technicalDetail = "model=${spec.modelId};path=${active?.absolutePath.orEmpty()}",
                )
            }
            ProvisionedModelState(
                modelId = spec.modelId,
                displayName = spec.displayName,
                fileName = spec.fileName,
                absolutePath = active?.absolutePath,
                sha256 = active?.sha256,
                importedAtEpochMs = active?.importedAtEpochMs,
                activeVersion = activeVersion,
                installedVersions = versions,
                localFileMissing = localFileMissing,
            )
        }
        return RuntimeProvisioningSnapshot(
            models = models,
            storageSummary = storageSummary(),
            requiredModelIds = startupCandidateModelIds,
            recoverableCorruptions = corruptionSignals.distinctBy { signal -> "${signal.code}:${signal.technicalDetail}" },
        )
    }

    suspend fun importModel(
        modelId: String,
        sourceUri: Uri,
        version: String = generatedVersion(prefix = "manual"),
    ): RuntimeModelImportResult {
        val spec = modelSpecFor(modelId)
        return withContext(Dispatchers.IO) {
            withModelLock(modelId) {
                ensureMigrated()
                val destinationDir = managedModelDirectory()
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
                    makeActive = false,
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
        val spec = modelSpecFor(modelId)
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
                    makeActive = false,
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
        val spec = modelSpecFor(modelId)
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

    fun managedModelDirectory(): File {
        return File(managedStorageRoot(), MANAGED_MODELS_DIR_NAME).apply { mkdirs() }
    }

    fun managedDownloadWorkspaceDirectory(): File {
        return File(managedStorageRoot(), ModelDownloadWorker.DOWNLOAD_DIR).apply { mkdirs() }
    }

    fun destinationFileForVersion(modelId: String, version: String): File {
        val spec = modelSpecFor(modelId)
        return File(managedModelDirectory(), fileNameForVersion(spec, version))
    }

    fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        ensureMigrated()
        return readInstalledVersions(modelSpecFor(modelId))
    }

    fun setActiveVersion(modelId: String, version: String): Boolean {
        val spec = modelSpecFor(modelId)
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
        val spec = modelSpecFor(modelId)
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
            if (versions.isEmpty() && !isBaselineModel(modelId)) {
                unregisterDynamicModelId(modelId)
            }
            true
        }
    }

    fun storageSummary(): StorageSummary {
        ensureMigrated()
        val storageRoot = managedStorageRoot()
        val usedByModels = allModelSpecs()
            .flatMap { spec -> readStoredVersionEntries(spec) }
            .sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
        val tempDownloadDir = managedDownloadWorkspaceDirectory()
        val tempBytes = tempDownloadDir
            .takeIf { it.exists() && it.isDirectory }
            ?.listFiles()
            ?.sumOf { it.length().coerceAtLeast(0L) }
            ?: 0L
        return StorageSummary(
            totalBytes = storageRoot.totalSpace.coerceAtLeast(0L),
            freeBytes = storageRoot.usableSpace.coerceAtLeast(0L),
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

        allModelSpecs().forEach { spec ->
            val versions = readInstalledVersions(spec)
            val activeVersion = prefs.readOptional(activeVersionKey(spec))
            val active = versions.firstOrNull { it.version == activeVersion }

            val path = active?.absolutePath?.trim().orEmpty()
            val sha = active?.sha256?.takeIf { it.isNotBlank() }.orEmpty()
            val issuer = active?.provenanceIssuer?.takeIf { it.isNotBlank() } ?: DEFAULT_ISSUER
            val signature = active?.provenanceSignature?.takeIf { it.isNotBlank() }.orEmpty()

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
            modelRuntimeProfile = runtimeProfile,
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
        writeManagedMetadataIfApplicable(
            modelId = spec.modelId,
            entry = entry,
        )
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
        return readInstalledVersionsWithDiagnostics(spec).versions
    }

    private fun readInstalledVersionsWithDiagnostics(spec: ModelSpec): VersionReadResult {
        val reconciled = reconcileStoredVersionEntries(spec)
        return VersionReadResult(
            versions = reconciled.entries
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
                        isActive = entry.version == prefs.readOptional(activeVersionKey(spec)),
                    )
                },
            signal = reconciled.signal,
        )
    }

    private fun reconcileStoredVersionEntries(spec: ModelSpec): StoredEntriesReadResult {
        val readResult = readStoredVersionEntriesWithDiagnostics(spec)
        val original = readResult.entries
        if (original.isEmpty()) {
            return readResult
        }

        var changed = false
        val deduped = mutableListOf<StoredVersionEntry>()
        val seenVersions = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()

        original.forEach { entry ->
            if (!seenVersions.add(entry.version) || !seenPaths.add(entry.absolutePath)) {
                changed = true
                return@forEach
            }
            deduped += entry
        }

        val activeVersionKey = activeVersionKey(spec)
        val activeVersion = prefs.readOptional(activeVersionKey)
        if (activeVersion != null && deduped.none { it.version == activeVersion }) {
            changed = true
            if (deduped.isEmpty()) {
                prefs.edit().remove(activeVersionKey).apply()
            } else {
                val replacement = deduped.maxByOrNull { it.importedAtEpochMs }?.version
                prefs.edit().putString(activeVersionKey, replacement).apply()
            }
        }

        if (changed) {
            writeStoredVersionEntries(spec, deduped)
        }
        return readResult.copy(entries = deduped)
    }

    private fun resolveFallbackModelPath(spec: ModelSpec, missingPath: String): String? {
        val packageName = context.packageName
        val fileName = spec.fileName
        val staticCandidates = listOf(
            "/storage/emulated/0/Android/media/$packageName/models/$fileName",
            "/storage/emulated/0/Download/$packageName/models/$fileName",
            "/sdcard/Android/media/$packageName/models/$fileName",
            "/sdcard/Download/$packageName/models/$fileName",
        )
        val aliasCandidates = listOf(
            missingPath.replace("/Android/media/", "/Download/"),
            missingPath.replace("/storage/emulated/0/Android/media/", "/storage/emulated/0/Download/"),
            missingPath.replace("/sdcard/Android/media/", "/sdcard/Download/"),
        )

        return (staticCandidates + aliasCandidates)
            .asSequence()
            .map { candidate -> candidate.trim() }
            .filter { candidate -> candidate.isNotEmpty() }
            .map { candidate -> normalizeAbsolutePath(candidate) }
            .firstOrNull { candidate ->
                val file = File(candidate)
                file.exists() && file.isFile
            }
    }

    private fun runOneTimePathAliasMigrationLocked() {
        if (prefs.getBoolean(PATH_ALIAS_MIGRATION_DONE_KEY, false)) {
            return
        }
        allModelSpecs().forEach { spec ->
            migratePathAliasesForSpec(spec)
        }
        prefs.edit().putBoolean(PATH_ALIAS_MIGRATION_DONE_KEY, true).apply()
    }

    private fun migratePathAliasesForSpec(spec: ModelSpec) {
        val entries = readStoredVersionEntries(spec)
        if (entries.isEmpty()) {
            return
        }
        var changed = false
        val migrated = entries.map { entry ->
            val currentFile = File(entry.absolutePath)
            if (currentFile.exists() && currentFile.isFile) {
                entry
            } else {
                val fallbackPath = resolveFallbackModelPath(spec, entry.absolutePath) ?: return@map entry
                changed = true
                val fallbackFile = File(fallbackPath)
                entry.copy(
                    absolutePath = normalizeAbsolutePath(fallbackFile.absolutePath),
                    fileSizeBytes = fallbackFile.length().coerceAtLeast(0L),
                )
            }
        }
        if (changed) {
            writeStoredVersionEntries(spec, migrated)
        }
    }

    private fun readStoredVersionEntries(spec: ModelSpec): List<StoredVersionEntry> {
        return readStoredVersionEntriesWithDiagnostics(spec).entries
    }

    private fun readStoredVersionEntriesWithDiagnostics(spec: ModelSpec): StoredEntriesReadResult {
        val raw = prefs.getString(versionsKey(spec), null).orEmpty().trim()
        if (raw.isEmpty()) {
            return StoredEntriesReadResult(entries = emptyList())
        }
        return runCatching {
            val array = JSONArray(raw)
            val decoded = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    decodeStoredVersion(item)?.let { add(it) }
                }
            }
            val dropped = array.length() - decoded.size
            if (dropped > 0) {
                backupCorruptPayload(
                    key = versionsKey(spec),
                    raw = raw,
                    code = "PROVISIONING_VERSIONS_ROW_CORRUPT",
                    detail = "model=${spec.modelId};dropped_rows=$dropped",
                )
                writeStoredVersionEntries(spec, decoded)
                StoredEntriesReadResult(
                    entries = decoded,
                    signal = ProvisioningRecoverySignal(
                        code = "PROVISIONING_VERSIONS_ROW_CORRUPT",
                        message = "Some stored model versions were invalid and were removed for ${spec.modelId}.",
                        technicalDetail = "model=${spec.modelId};dropped_rows=$dropped",
                    ),
                )
            } else {
                StoredEntriesReadResult(entries = decoded)
            }
        }.getOrElse { error ->
            backupCorruptPayload(
                key = versionsKey(spec),
                raw = raw,
                code = "PROVISIONING_VERSIONS_JSON_CORRUPT",
                detail = "model=${spec.modelId};error=${error.message ?: "json_parse_failed"}",
            )
            prefs.edit().putString(versionsKey(spec), "[]").apply()
            val signal = ProvisioningRecoverySignal(
                code = "PROVISIONING_VERSIONS_JSON_CORRUPT",
                message = "Stored model metadata was corrupted for ${spec.modelId} and has been reset.",
                technicalDetail = "model=${spec.modelId};error=${error.message ?: "json_parse_failed"}",
            )
            StoredEntriesReadResult(entries = emptyList(), signal = signal)
        }
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
            BASELINE_MODEL_SPECS.forEach(::migrateSpecIfNeeded)
            val discoveredDynamicIds = discoverDynamicModelIdsFromMetadata()
            val mergedDynamicIds = (readDynamicModelIds() + discoveredDynamicIds)
                .filterNot(::isBaselineModel)
                .toSortedSet()
            writeDynamicModelIds(mergedDynamicIds)
            mergedDynamicIds
                .map(::dynamicModelSpec)
                .forEach(::migrateSpecIfNeeded)
            runOneTimePathAliasMigrationLocked()
        }
    }

    private fun migrateSpecIfNeeded(spec: ModelSpec) {
        if (prefs.contains(versionsKey(spec))) {
            return
        }
        val legacyPath = prefs.readOptional(spec.pathKey)
        val legacySha = prefs.readOptional(spec.shaKey)
        if (legacyPath.isNullOrBlank() || legacySha.isNullOrBlank()) {
            val discovered = discoverPersistedEntries(spec)
            prefs.edit()
                .putString(versionsKey(spec), encodeStoredVersions(discovered).toString())
                .apply()
            if (discovered.isEmpty()) {
                prefs.edit().remove(activeVersionKey(spec)).apply()
            } else {
                val latestVersion = discovered.maxByOrNull { it.importedAtEpochMs }?.version
                prefs.edit().putString(activeVersionKey(spec), latestVersion).apply()
            }
            prefs.edit()
                .remove(spec.pathKey)
                .remove(spec.shaKey)
                .remove(spec.issuerKey)
                .remove(spec.signatureKey)
                .remove(spec.importedAtKey)
                .apply()
            return
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

    private fun discoverDynamicModelIdsFromMetadata(): Set<String> {
        val dir = managedModelDirectory()
        val metadataFiles = dir.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX, ignoreCase = true) }
            ?.toList()
            ?: emptyList()
        if (metadataFiles.isEmpty()) {
            return emptySet()
        }
        return metadataFiles.mapNotNull { metadataFile ->
            val json = runCatching { JSONObject(metadataFile.readText()) }.getOrNull() ?: return@mapNotNull null
            val modelId = json.optString("modelId", "").trim()
            if (modelId.isBlank() || isBaselineModel(modelId)) {
                return@mapNotNull null
            }
            val absolutePath = normalizeAbsolutePath(json.optString("absolutePath", "").trim())
            val modelFile = File(absolutePath)
            if (absolutePath.isBlank() || !modelFile.exists() || !modelFile.isFile) {
                return@mapNotNull null
            }
            modelId
        }.toSet()
    }

    private fun allModelSpecs(dynamicIds: Set<String> = readDynamicModelIds()): List<ModelSpec> {
        val dynamicSpecs = dynamicIds
            .filterNot { modelId -> baselineModelIdSet.contains(modelId) }
            .sorted()
            .map { modelId -> dynamicModelSpec(modelId) }
        return BASELINE_MODEL_SPECS + dynamicSpecs
    }

    private fun modelSpecFor(modelId: String): ModelSpec {
        BASELINE_MODEL_SPECS.firstOrNull { it.modelId == modelId }?.let { return it }
        registerDynamicModelId(modelId)
        return dynamicModelSpec(modelId)
    }

    private fun isBaselineModel(modelId: String): Boolean = baselineModelIdSet.contains(modelId)

    private fun readDynamicModelIds(): Set<String> {
        return readDynamicModelIdsWithDiagnostics().ids
    }

    private fun readDynamicModelIdsWithDiagnostics(): DynamicModelIdsReadResult {
        val raw = prefs.getString(DYNAMIC_MODEL_IDS_KEY, null).orEmpty().trim()
        if (raw.isEmpty()) {
            return DynamicModelIdsReadResult(ids = emptySet())
        }
        return runCatching {
            val array = JSONArray(raw)
            val ids = buildSet {
                for (index in 0 until array.length()) {
                    val modelId = array.optString(index, "").trim()
                    if (modelId.isNotEmpty()) {
                        add(modelId)
                    }
                }
            }
            DynamicModelIdsReadResult(ids = ids)
        }.getOrElse { error ->
            backupCorruptPayload(
                key = DYNAMIC_MODEL_IDS_KEY,
                raw = raw,
                code = "PROVISIONING_DYNAMIC_MODEL_IDS_CORRUPT",
                detail = "error=${error.message ?: "json_parse_failed"}",
            )
            prefs.edit().putString(DYNAMIC_MODEL_IDS_KEY, "[]").apply()
            DynamicModelIdsReadResult(
                ids = emptySet(),
                signal = ProvisioningRecoverySignal(
                    code = "PROVISIONING_DYNAMIC_MODEL_IDS_CORRUPT",
                    message = "Dynamic model registry was corrupted and has been reset.",
                    technicalDetail = "error=${error.message ?: "json_parse_failed"}",
                ),
            )
        }
    }

    private fun registerDynamicModelId(modelId: String) {
        val normalized = modelId.trim()
        if (normalized.isEmpty() || isBaselineModel(normalized)) {
            return
        }
        val updated = (readDynamicModelIds() + normalized)
        writeDynamicModelIds(updated)
    }

    private fun unregisterDynamicModelId(modelId: String) {
        if (isBaselineModel(modelId)) {
            return
        }
        val updated = readDynamicModelIds()
            .filterNot { candidate -> candidate == modelId }
            .toSet()
        writeDynamicModelIds(updated)
    }

    private fun writeDynamicModelIds(ids: Set<String>) {
        val sanitized = ids
            .map { id -> id.trim() }
            .filter { id -> id.isNotEmpty() && !isBaselineModel(id) }
            .sorted()
        val encoded = JSONArray().apply {
            sanitized.forEach { value -> put(value) }
        }
        prefs.edit().putString(DYNAMIC_MODEL_IDS_KEY, encoded.toString()).apply()
    }

    private fun dynamicModelSpec(modelId: String): ModelSpec {
        val prefTag = "dyn_${sha256Hex(modelId.encodeToByteArray()).take(12)}"
        val safeFileName = modelId
            .trim()
            .ifEmpty { "model" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "-")
            .lowercase(Locale.US)
        return ModelSpec(
            modelId = modelId,
            displayName = modelId,
            fileName = "$safeFileName.gguf",
            prefTag = prefTag,
            pathKey = "legacy_path_$prefTag",
            shaKey = "legacy_sha_$prefTag",
            issuerKey = "legacy_issuer_$prefTag",
            signatureKey = "legacy_signature_$prefTag",
            importedAtKey = "legacy_imported_at_$prefTag",
        )
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

    private fun discoverPersistedEntries(spec: ModelSpec): List<StoredVersionEntry> {
        val metadataEntries = discoverPersistedEntriesFromMetadata(spec)
        if (metadataEntries.isNotEmpty()) {
            return metadataEntries
        }
        val dir = managedModelDirectory()
        val files = dir.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && file.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList()
            ?: emptyList()
        if (files.isEmpty()) {
            return emptyList()
        }
        val discovered = mutableListOf<StoredVersionEntry>()
        var droppedCount = 0
        val droppedSamples = mutableListOf<String>()
        files.forEach { file ->
            val version = parseVersionFromFileName(spec, file.name)
            if (version.isNullOrBlank()) {
                droppedCount += 1
                if (droppedSamples.size < 3) {
                    droppedSamples += "${file.name}:version_unrecognized"
                }
                return@forEach
            }
            val sha = runCatching { sha256HexFromFile(file) }.getOrNull()
            if (sha.isNullOrBlank()) {
                droppedCount += 1
                if (droppedSamples.size < 3) {
                    droppedSamples += "${file.name}:sha_compute_failed"
                }
                return@forEach
            }
            val normalizedPath = normalizeAbsolutePath(file.absolutePath)
            discovered += StoredVersionEntry(
                version = sanitizeVersion(version),
                absolutePath = normalizedPath,
                sha256 = sha,
                provenanceIssuer = DEFAULT_ISSUER,
                provenanceSignature = sha256Hex("$DEFAULT_ISSUER|${spec.modelId}|$sha|v1".encodeToByteArray()),
                runtimeCompatibility = RUNTIME_COMPATIBILITY_TAG,
                fileSizeBytes = file.length().coerceAtLeast(0L),
                importedAtEpochMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }
        if (droppedCount > 0) {
            recordMigrationCorruption(
                ProvisioningRecoverySignal(
                    code = "PROVISIONING_DISCOVERY_FILES_SKIPPED",
                    message = "Some discovered model files were skipped during migration for ${spec.modelId}.",
                    technicalDetail = "model=${spec.modelId};dropped=$droppedCount;samples=${droppedSamples.joinToString(",")}",
                ),
            )
        }
        return discovered.sortedByDescending { it.importedAtEpochMs }
    }

    private fun discoverPersistedEntriesFromMetadata(spec: ModelSpec): List<StoredVersionEntry> {
        val dir = managedModelDirectory()
        val metadataFiles = dir.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX, ignoreCase = true) }
            ?.toList()
            ?: emptyList()
        if (metadataFiles.isEmpty()) {
            return emptyList()
        }
        val discovered = mutableListOf<StoredVersionEntry>()
        var droppedCount = 0
        var parseFailureCount = 0
        val droppedSamples = mutableListOf<String>()
        metadataFiles.forEach { metadataFile ->
            val raw = runCatching { metadataFile.readText() }.getOrNull()
            if (raw == null) {
                droppedCount += 1
                if (droppedSamples.size < 3) {
                    droppedSamples += "${metadataFile.name}:read_failed"
                }
                return@forEach
            }
            val json = runCatching { JSONObject(raw) }.getOrElse { error ->
                droppedCount += 1
                parseFailureCount += 1
                if (droppedSamples.size < 3) {
                    droppedSamples += "${metadataFile.name}:json_parse_failed"
                }
                backupCorruptPayload(
                    key = "migration_metadata_${spec.prefTag}_${metadataFile.name.hashCode()}",
                    raw = raw,
                    code = "PROVISIONING_METADATA_JSON_CORRUPT",
                    detail = "model=${spec.modelId};file=${metadataFile.name};error=${error.message ?: "json_parse_failed"}",
                )
                return@forEach
            }
            val modelId = json.optString("modelId", "").trim()
            if (modelId != spec.modelId) {
                return@forEach
            }
            val absolutePath = normalizeAbsolutePath(json.optString("absolutePath", "").trim())
            val sha = json.optString("sha256", "").trim()
            val version = sanitizeVersion(json.optString("version", "").trim())
            val modelFile = File(absolutePath)
            if (
                absolutePath.isBlank() ||
                !modelFile.exists() ||
                !modelFile.isFile ||
                sha.isBlank() ||
                version.isBlank()
            ) {
                droppedCount += 1
                if (droppedSamples.size < 3) {
                    droppedSamples += "${metadataFile.name}:missing_required_fields"
                }
                return@forEach
            }
            val issuer = json.optString("provenanceIssuer", DEFAULT_ISSUER).trim().ifBlank { DEFAULT_ISSUER }
            discovered += StoredVersionEntry(
                version = version,
                absolutePath = absolutePath,
                sha256 = sha,
                provenanceIssuer = issuer,
                provenanceSignature = json.optString("provenanceSignature", "").trim().ifBlank {
                    sha256Hex("$issuer|${spec.modelId}|$sha|v1".encodeToByteArray())
                },
                runtimeCompatibility = json.optString("runtimeCompatibility", RUNTIME_COMPATIBILITY_TAG)
                    .trim()
                    .ifBlank { RUNTIME_COMPATIBILITY_TAG },
                fileSizeBytes = json.optLong("fileSizeBytes", modelFile.length().coerceAtLeast(0L)).coerceAtLeast(0L),
                importedAtEpochMs = json.optLong("importedAtEpochMs", modelFile.lastModified()).takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
            )
        }
        if (droppedCount > 0) {
            recordMigrationCorruption(
                ProvisioningRecoverySignal(
                    code = "PROVISIONING_DISCOVERY_METADATA_SKIPPED",
                    message = "Some stored metadata entries were skipped during migration for ${spec.modelId}.",
                    technicalDetail = buildString {
                        append("model=${spec.modelId};dropped=$droppedCount;parse_failures=$parseFailureCount")
                        if (droppedSamples.isNotEmpty()) {
                            append(";samples=${droppedSamples.joinToString(",")}")
                        }
                    },
                ),
            )
        }
        return discovered.sortedByDescending { it.importedAtEpochMs }
    }

    private fun parseVersionFromFileName(spec: ModelSpec, fileName: String): String? {
        val baseName = spec.fileName.substringBeforeLast('.', missingDelimiterValue = spec.fileName)
        val extension = spec.fileName.substringAfterLast('.', missingDelimiterValue = "gguf")

        val canonicalPrefix = "$baseName-"
        val canonicalSuffix = ".$extension"
        if (fileName.startsWith(canonicalPrefix) && fileName.endsWith(canonicalSuffix)) {
            val parsed = fileName.removePrefix(canonicalPrefix).removeSuffix(canonicalSuffix)
            if (parsed.isNotBlank()) {
                return parsed
            }
        }
        if (fileName.equals(spec.fileName, ignoreCase = true)) {
            return DISCOVERED_VERSION_FALLBACK
        }

        val legacyPrefix = "${sanitizeLegacyModelId(spec.modelId)}-"
        if (fileName.startsWith(legacyPrefix) && fileName.endsWith(".gguf")) {
            val parsed = fileName.removePrefix(legacyPrefix).removeSuffix(".gguf")
            if (parsed.isNotBlank()) {
                return parsed
            }
        }
        return null
    }

    private fun sanitizeLegacyModelId(modelId: String): String {
        return modelId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun encodeStoredVersions(entries: List<StoredVersionEntry>): JSONArray {
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
        return array
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
        metadataFileFor(target.absolutePath).takeIf { it.exists() }?.delete()
    }

    private fun isManagedModelFile(file: File): Boolean {
        val managedRuntimeDir = managedModelDirectory()
        val managedDownloadDir = managedDownloadWorkspaceDirectory()
        val legacyRuntimeDir = File(context.filesDir, MODEL_DIR_NAME)
        val legacyDownloadDir = File(context.filesDir, ModelDownloadWorker.DOWNLOAD_DIR)
        return isPathWithin(file, managedRuntimeDir) ||
            isPathWithin(file, managedDownloadDir) ||
            isPathWithin(file, legacyRuntimeDir) ||
            isPathWithin(file, legacyDownloadDir)
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

    private fun writeManagedMetadataIfApplicable(
        modelId: String,
        entry: StoredVersionEntry,
    ) {
        val target = File(entry.absolutePath)
        if (!target.exists() || !target.isFile || !isManagedModelFile(target)) {
            return
        }
        val metadataFile = metadataFileFor(entry.absolutePath)
        val payload = JSONObject()
            .put("modelId", modelId)
            .put("version", entry.version)
            .put("absolutePath", entry.absolutePath)
            .put("sha256", entry.sha256)
            .put("provenanceIssuer", entry.provenanceIssuer)
            .put("provenanceSignature", entry.provenanceSignature)
            .put("runtimeCompatibility", entry.runtimeCompatibility)
            .put("fileSizeBytes", entry.fileSizeBytes)
            .put("importedAtEpochMs", entry.importedAtEpochMs)
        runCatching {
            metadataFile.parentFile?.mkdirs()
            metadataFile.writeText(payload.toString())
        }
    }

    private fun metadataFileFor(absolutePath: String): File {
        return File("$absolutePath$METADATA_SUFFIX")
    }

    private fun backupCorruptPayload(
        key: String,
        raw: String,
        code: String,
        detail: String,
    ) {
        prefs.edit()
            .putString("$CORRUPT_BACKUP_PREFIX.$key.payload", raw)
            .putString("$CORRUPT_BACKUP_PREFIX.$key.code", code)
            .putString("$CORRUPT_BACKUP_PREFIX.$key.detail", detail)
            .putLong("$CORRUPT_BACKUP_PREFIX.$key.saved_at", System.currentTimeMillis())
            .apply()
    }

    private fun recordMigrationCorruption(signal: ProvisioningRecoverySignal) {
        synchronized(MIGRATION_LOCK) {
            migrationCorruptionSignals += signal
        }
    }

    private fun drainMigrationCorruptionSignals(): List<ProvisioningRecoverySignal> {
        synchronized(MIGRATION_LOCK) {
            if (migrationCorruptionSignals.isEmpty()) {
                return emptyList()
            }
            val drained = migrationCorruptionSignals.toList()
            migrationCorruptionSignals.clear()
            return drained
        }
    }

    private fun managedStorageRoot(): File {
        val externalRoot = context.externalMediaDirs
            .asSequence()
            .filterNotNull()
            .firstOrNull { candidate ->
                runCatching {
                    if (!candidate.exists()) {
                        candidate.mkdirs()
                    }
                    candidate.exists() && candidate.isDirectory && candidate.canWrite()
                }.getOrDefault(false)
            }
        val root = externalRoot ?: context.filesDir
        return root
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

    private data class StoredEntriesReadResult(
        val entries: List<StoredVersionEntry>,
        val signal: ProvisioningRecoverySignal? = null,
    )

    private data class DynamicModelIdsReadResult(
        val ids: Set<String>,
        val signal: ProvisioningRecoverySignal? = null,
    )

    private data class VersionReadResult(
        val versions: List<ModelVersionDescriptor>,
        val signal: ProvisioningRecoverySignal? = null,
    )

    companion object {
        private const val PREFS_NAME = "pocketagent_runtime_models"
        private const val MODEL_DIR_NAME = "runtime-models"
        private const val DEFAULT_ISSUER = "internal-release"
        private const val RUNTIME_COMPATIBILITY_TAG = "android-arm64-v8a"
        private const val COPY_BUFFER_SIZE_BYTES = 1024 * 1024
        private const val INITIAL_MIGRATION_VERSION = "1.0.0-initial"
        private const val DISCOVERED_VERSION_FALLBACK = "discovered"
        private const val DYNAMIC_MODEL_IDS_KEY = "dynamic_model_ids_json"
        private const val PATH_ALIAS_MIGRATION_DONE_KEY = "path_alias_migration_done_v1"
        private const val CORRUPT_BACKUP_PREFIX = "runtime_provisioning_corrupt_backup"
        private const val MANAGED_MODELS_DIR_NAME = "models"
        private const val METADATA_SUFFIX = ".meta.json"
        private val MIGRATION_LOCK = Any()

        private data class LegacySpecOverride(
            val displayName: String,
            val fileName: String,
            val prefTag: String,
            val pathKey: String,
            val shaKey: String,
            val issuerKey: String,
            val signatureKey: String,
            val importedAtKey: String,
        )

        private val LEGACY_BASELINE_OVERRIDES: Map<String, LegacySpecOverride> = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to LegacySpecOverride(
                displayName = "Qwen 3.5 0.8B (Q4)",
                fileName = "qwen3.5-0.8b-q4.gguf",
                prefTag = "0_8b",
                pathKey = "model_0_8b_path",
                shaKey = "model_0_8b_sha256",
                issuerKey = "model_0_8b_issuer",
                signatureKey = "model_0_8b_signature",
                importedAtKey = "model_0_8b_imported_at",
            ),
            ModelCatalog.QWEN_3_5_2B_Q4 to LegacySpecOverride(
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

        private val BASELINE_MODEL_SPECS: List<ModelSpec> = ModelCatalog.modelDescriptors()
            .asSequence()
            .filter { descriptor -> descriptor.bridgeSupported || descriptor.startupCandidate }
            .map { descriptor ->
                val modelId = descriptor.modelId
                val legacy = LEGACY_BASELINE_OVERRIDES[modelId]
                if (legacy != null) {
                    return@map ModelSpec(
                        modelId = modelId,
                        displayName = legacy.displayName,
                        fileName = legacy.fileName,
                        prefTag = legacy.prefTag,
                        pathKey = legacy.pathKey,
                        shaKey = legacy.shaKey,
                        issuerKey = legacy.issuerKey,
                        signatureKey = legacy.signatureKey,
                        importedAtKey = legacy.importedAtKey,
                    )
                }
                val derivedPrefTag = "cat_${descriptor.envKeyToken.lowercase(Locale.US)}"
                val displayName = descriptor.modelId
                val fileName = "${descriptor.modelId}.gguf"
                ModelSpec(
                    modelId = modelId,
                    displayName = displayName,
                    fileName = fileName,
                    prefTag = derivedPrefTag,
                    pathKey = "legacy_path_$derivedPrefTag",
                    shaKey = "legacy_sha_$derivedPrefTag",
                    issuerKey = "legacy_issuer_$derivedPrefTag",
                    signatureKey = "legacy_signature_$derivedPrefTag",
                    importedAtKey = "legacy_imported_at_$derivedPrefTag",
                )
            }
            .sortedBy { spec -> spec.modelId }
            .toList()

        internal fun baselineModelIdsForTesting(): Set<String> {
            return BASELINE_MODEL_SPECS.mapTo(linkedSetOf()) { spec -> spec.modelId }
        }

        internal fun legacyPathKeyForTesting(modelId: String): String? {
            return BASELINE_MODEL_SPECS.firstOrNull { spec -> spec.modelId == modelId }?.pathKey
        }
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
