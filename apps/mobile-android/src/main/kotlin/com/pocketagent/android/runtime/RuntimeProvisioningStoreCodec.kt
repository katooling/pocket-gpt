package com.pocketagent.android.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun AndroidRuntimeProvisioningStore.decodeStoredVersion(json: JSONObject): StoredVersionEntry? {
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
        provenanceIssuer = json.optString("provenanceIssuer", PROVISIONING_DEFAULT_ISSUER).trim().ifEmpty { PROVISIONING_DEFAULT_ISSUER },
        provenanceSignature = json.optString("provenanceSignature", "").trim(),
        runtimeCompatibility = json.optString("runtimeCompatibility", PROVISIONING_RUNTIME_COMPATIBILITY_TAG)
            .trim()
            .ifEmpty { PROVISIONING_RUNTIME_COMPATIBILITY_TAG },
        fileSizeBytes = json.optLong("fileSizeBytes", 0L).coerceAtLeast(0L),
        importedAtEpochMs = json.optLong("importedAtEpochMs", System.currentTimeMillis()),
    )
}

internal fun AndroidRuntimeProvisioningStore.encodeStoredVersions(entries: List<StoredVersionEntry>): JSONArray {
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

internal fun AndroidRuntimeProvisioningStore.writeManagedMetadataIfApplicable(
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

internal fun AndroidRuntimeProvisioningStore.metadataFileFor(absolutePath: String): File {
    return File("$absolutePath$PROVISIONING_METADATA_SUFFIX")
}
