package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class RuntimeConfig(
    val artifactPayloadByModelId: Map<String, ByteArray>,
    val artifactFilePathByModelId: Map<String, String>,
    val artifactSha256ByModelId: Map<String, String>,
    val artifactProvenanceIssuerByModelId: Map<String, String>,
    val artifactProvenanceSignatureByModelId: Map<String, String>,
    val runtimeCompatibilityTag: String,
    val requireNativeRuntimeForStartupChecks: Boolean,
    val prefixCacheEnabled: Boolean,
    val prefixCacheStrict: Boolean,
    val responseCacheTtlSec: Long,
    val responseCacheMaxEntries: Int,
    val streamContractV2Enabled: Boolean = true,
    val modelRuntimeProfile: ModelRuntimeProfile = ModelRuntimeProfile.PROD,
) {
    companion object {
        const val MODEL_ENV_PREFIX: String = "POCKETGPT_MODEL_"
        const val MODEL_SIDELOAD_PATH_ENV_SUFFIX: String = "_SIDELOAD_PATH"
        const val MODEL_SHA256_ENV_SUFFIX: String = "_SHA256"
        const val MODEL_PROVENANCE_SIGNATURE_ENV_SUFFIX: String = "_PROVENANCE_SIGNATURE"
        const val MODEL_PROVENANCE_ISSUER_ENV_SUFFIX: String = "_PROVENANCE_ISSUER"
        const val MODEL_PROVENANCE_ISSUER_ENV: String = "POCKETGPT_MODEL_PROVENANCE_ISSUER"
        const val MODEL_RUNTIME_COMPATIBILITY_ENV: String = "POCKETGPT_MODEL_RUNTIME_COMPATIBILITY"
        const val REQUIRE_NATIVE_RUNTIME_STARTUP_ENV: String = "POCKETGPT_REQUIRE_NATIVE_RUNTIME_STARTUP"
        const val PREFIX_CACHE_ENABLED_ENV: String = "POCKETGPT_PREFIX_CACHE_ENABLED"
        const val PREFIX_CACHE_STRICT_ENV: String = "POCKETGPT_PREFIX_CACHE_STRICT"
        const val RESPONSE_CACHE_TTL_SEC_ENV: String = "POCKETGPT_RESPONSE_CACHE_TTL_SEC"
        const val RESPONSE_CACHE_MAX_ENTRIES_ENV: String = "POCKETGPT_RESPONSE_CACHE_MAX_ENTRIES"
        const val STREAM_CONTRACT_V2_ENV: String = "POCKETGPT_STREAM_CONTRACT_V2"
        const val MODEL_RUNTIME_PROFILE_ENV: String = "POCKETGPT_MODEL_RUNTIME_PROFILE"
        private const val DEFAULT_PROVENANCE_ISSUER: String = "internal-release"
        private const val DEFAULT_RUNTIME_COMPATIBILITY_TAG: String = "android-arm64-v8a"
        private const val DEFAULT_SHA_BUFFER_SIZE: Int = 1024 * 1024
        private const val DEFAULT_PREFIX_CACHE_ENABLED: Boolean = true
        private const val DEFAULT_PREFIX_CACHE_STRICT: Boolean = false
        private const val DEFAULT_RESPONSE_CACHE_TTL_SEC: Long = 0L
        private const val DEFAULT_RESPONSE_CACHE_MAX_ENTRIES: Int = 0
        private const val MAX_IN_MEMORY_PAYLOAD_BYTES: Long = Int.MAX_VALUE.toLong() - 8L

        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
        ): RuntimeConfig {
            val artifactPayloadByModelId = mutableMapOf<String, ByteArray>()
            val artifactFilePathByModelId = mutableMapOf<String, String>()
            val artifactSha256ByModelId = mutableMapOf<String, String>()
            val artifactProvenanceIssuerByModelId = mutableMapOf<String, String>()
            val artifactProvenanceSignatureByModelId = mutableMapOf<String, String>()

            val issuer = environment[MODEL_PROVENANCE_ISSUER_ENV]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_PROVENANCE_ISSUER

            ModelCatalog.modelDescriptors().forEach { descriptor ->
                val modelId = descriptor.modelId
                val sideLoadPath = environment[sideLoadPathEnvName(modelId)].orEmpty()
                val payload = resolvePayload(sideLoadPath = sideLoadPath)
                if (payload != null) {
                    artifactPayloadByModelId[modelId] = payload
                }
                artifactFilePathByModelId[modelId] = sideLoadPath

                val payloadSha = resolveSha(
                    envValue = environment[sha256EnvName(modelId)],
                    payload = payload,
                    sideLoadPath = sideLoadPath,
                )
                artifactSha256ByModelId[modelId] = payloadSha

                val modelIssuer = environment[provenanceIssuerEnvName(modelId)]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: issuer
                artifactProvenanceIssuerByModelId[modelId] = modelIssuer
                artifactProvenanceSignatureByModelId[modelId] = resolveProvenanceSignature(
                    envValue = environment[provenanceSignatureEnvName(modelId)],
                    issuer = modelIssuer,
                    modelId = modelId,
                    payloadSha = payloadSha,
                )
            }

            val runtimeCompatibilityTag = environment[MODEL_RUNTIME_COMPATIBILITY_ENV]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_RUNTIME_COMPATIBILITY_TAG
            val requireNativeRuntimeForStartupChecks = booleanEnv(
                environment = environment,
                name = REQUIRE_NATIVE_RUNTIME_STARTUP_ENV,
                defaultValue = true,
            )
            val prefixCacheEnabled = booleanEnv(
                environment = environment,
                name = PREFIX_CACHE_ENABLED_ENV,
                defaultValue = DEFAULT_PREFIX_CACHE_ENABLED,
            )
            val prefixCacheStrict = booleanEnv(
                environment = environment,
                name = PREFIX_CACHE_STRICT_ENV,
                defaultValue = DEFAULT_PREFIX_CACHE_STRICT,
            )
            val responseCacheTtlSec = longEnv(
                environment = environment,
                name = RESPONSE_CACHE_TTL_SEC_ENV,
                defaultValue = DEFAULT_RESPONSE_CACHE_TTL_SEC,
                minValue = 0L,
            )
            val responseCacheMaxEntries = intEnv(
                environment = environment,
                name = RESPONSE_CACHE_MAX_ENTRIES_ENV,
                defaultValue = DEFAULT_RESPONSE_CACHE_MAX_ENTRIES,
                minValue = 0,
            )
            val streamContractV2Enabled = booleanEnv(
                environment = environment,
                name = STREAM_CONTRACT_V2_ENV,
                defaultValue = true,
            )
            val modelRuntimeProfile = runtimeProfileEnv(
                environment = environment,
                name = MODEL_RUNTIME_PROFILE_ENV,
                defaultValue = ModelRuntimeProfile.PROD,
            )
            return RuntimeConfig(
                artifactPayloadByModelId = artifactPayloadByModelId.toMap(),
                artifactFilePathByModelId = artifactFilePathByModelId.toMap(),
                artifactSha256ByModelId = artifactSha256ByModelId.toMap(),
                artifactProvenanceIssuerByModelId = artifactProvenanceIssuerByModelId.toMap(),
                artifactProvenanceSignatureByModelId = artifactProvenanceSignatureByModelId.toMap(),
                runtimeCompatibilityTag = runtimeCompatibilityTag,
                requireNativeRuntimeForStartupChecks = requireNativeRuntimeForStartupChecks,
                prefixCacheEnabled = prefixCacheEnabled,
                prefixCacheStrict = prefixCacheStrict,
                responseCacheTtlSec = responseCacheTtlSec,
                responseCacheMaxEntries = responseCacheMaxEntries,
                streamContractV2Enabled = streamContractV2Enabled,
                modelRuntimeProfile = modelRuntimeProfile,
            )
        }

        fun sideLoadPathEnvName(modelId: String): String {
            return envNameForModel(modelId = modelId, suffix = MODEL_SIDELOAD_PATH_ENV_SUFFIX)
        }

        fun sha256EnvName(modelId: String): String {
            return envNameForModel(modelId = modelId, suffix = MODEL_SHA256_ENV_SUFFIX)
        }

        fun provenanceSignatureEnvName(modelId: String): String {
            return envNameForModel(modelId = modelId, suffix = MODEL_PROVENANCE_SIGNATURE_ENV_SUFFIX)
        }

        fun provenanceIssuerEnvName(modelId: String): String {
            return envNameForModel(modelId = modelId, suffix = MODEL_PROVENANCE_ISSUER_ENV_SUFFIX)
        }

        private fun envNameForModel(modelId: String, suffix: String): String {
            val normalizedModelId = modelId.trim()
            val descriptor = ModelCatalog.descriptorFor(normalizedModelId)
                ?: ModelCatalog.descriptorForEnvKeyToken(normalizedModelId)
            val envToken = descriptor?.envKeyToken ?: normalizeModelEnvToken(normalizedModelId)
            return "$MODEL_ENV_PREFIX$envToken$suffix"
        }

        private fun normalizeModelEnvToken(modelId: String): String {
            return modelId
                .uppercase()
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
                .ifEmpty { "MODEL" }
        }

        private fun booleanEnv(environment: Map<String, String>, name: String, defaultValue: Boolean): Boolean {
            val raw = environment[name]
                ?.trim()
                ?.lowercase()
                ?: return defaultValue
            return when (raw) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> defaultValue
            }
        }

        private fun intEnv(
            environment: Map<String, String>,
            name: String,
            defaultValue: Int,
            minValue: Int,
        ): Int {
            val parsed = environment[name]?.trim()?.toIntOrNull() ?: return defaultValue
            return parsed.coerceAtLeast(minValue)
        }

        private fun longEnv(
            environment: Map<String, String>,
            name: String,
            defaultValue: Long,
            minValue: Long,
        ): Long {
            val parsed = environment[name]?.trim()?.toLongOrNull() ?: return defaultValue
            return parsed.coerceAtLeast(minValue)
        }

        private fun runtimeProfileEnv(
            environment: Map<String, String>,
            name: String,
            defaultValue: ModelRuntimeProfile,
        ): ModelRuntimeProfile {
            val normalized = environment[name]
                ?.trim()
                ?.lowercase()
                ?: return defaultValue
            return when (normalized) {
                "prod", "production" -> ModelRuntimeProfile.PROD
                "dev_fast", "dev-fast", "devfast", "debug" -> ModelRuntimeProfile.DEV_FAST
                else -> defaultValue
            }
        }

        private fun resolvePayload(sideLoadPath: String): ByteArray? {
            val normalized = sideLoadPath.trim()
            if (normalized.isEmpty()) {
                return null
            }
            val filePath = runCatching { Path.of(normalized) }.getOrNull() ?: return null
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return null
            }
            val fileSize = runCatching { Files.size(filePath) }.getOrNull() ?: return null
            if (fileSize > MAX_IN_MEMORY_PAYLOAD_BYTES) {
                return null
            }
            return runCatching { Files.readAllBytes(filePath) }.getOrNull()
        }

        private fun resolveSha(envValue: String?, payload: ByteArray?, sideLoadPath: String?): String {
            val envSha = envValue?.trim()?.takeIf { it.isNotEmpty() }
            if (envSha != null) {
                return envSha
            }
            val filePath = sideLoadPath
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Path.of(it) }
            if (filePath != null && Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return sha256HexFromFilePath(filePath)
            }
            return payload?.let(::sha256Hex).orEmpty()
        }

        private fun resolveProvenanceSignature(
            envValue: String?,
            issuer: String,
            modelId: String,
            payloadSha: String,
        ): String {
            val envSig = envValue?.trim()?.takeIf { it.isNotEmpty() }
            if (envSig != null) {
                return envSig
            }
            if (payloadSha.isBlank()) {
                return ""
            }
            return sha256Hex("$issuer|$modelId|$payloadSha|v1".encodeToByteArray())
        }

        private fun sha256HexFromFilePath(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(Files.newInputStream(path)).use { input ->
                val buffer = ByteArray(DEFAULT_SHA_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val builder = StringBuilder()
            digest.forEach { b -> builder.append("%02x".format(b)) }
            return builder.toString()
        }
    }
}
