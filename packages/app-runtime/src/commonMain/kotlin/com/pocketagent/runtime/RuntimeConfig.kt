package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
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
) {
    companion object {
        const val QWEN_0_8B_SHA256_ENV: String = "POCKETGPT_QWEN_3_5_0_8B_Q4_SHA256"
        const val QWEN_2B_SHA256_ENV: String = "POCKETGPT_QWEN_3_5_2B_Q4_SHA256"
        const val QWEN_0_8B_SIDELOAD_PATH_ENV: String = "POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH"
        const val QWEN_2B_SIDELOAD_PATH_ENV: String = "POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH"
        const val QWEN_0_8B_PROVENANCE_SIG_ENV: String = "POCKETGPT_QWEN_3_5_0_8B_Q4_PROVENANCE_SIGNATURE"
        const val QWEN_2B_PROVENANCE_SIG_ENV: String = "POCKETGPT_QWEN_3_5_2B_Q4_PROVENANCE_SIGNATURE"
        const val MODEL_PROVENANCE_ISSUER_ENV: String = "POCKETGPT_MODEL_PROVENANCE_ISSUER"
        const val MODEL_RUNTIME_COMPATIBILITY_ENV: String = "POCKETGPT_MODEL_RUNTIME_COMPATIBILITY"
        const val REQUIRE_NATIVE_RUNTIME_STARTUP_ENV: String = "POCKETGPT_REQUIRE_NATIVE_RUNTIME_STARTUP"
        const val PREFIX_CACHE_ENABLED_ENV: String = "POCKETGPT_PREFIX_CACHE_ENABLED"
        const val PREFIX_CACHE_STRICT_ENV: String = "POCKETGPT_PREFIX_CACHE_STRICT"
        const val RESPONSE_CACHE_TTL_SEC_ENV: String = "POCKETGPT_RESPONSE_CACHE_TTL_SEC"
        const val RESPONSE_CACHE_MAX_ENTRIES_ENV: String = "POCKETGPT_RESPONSE_CACHE_MAX_ENTRIES"
        const val STREAM_CONTRACT_V2_ENV: String = "POCKETGPT_STREAM_CONTRACT_V2"
        private const val DEFAULT_PROVENANCE_ISSUER: String = "internal-release"
        private const val DEFAULT_RUNTIME_COMPATIBILITY_TAG: String = "android-arm64-v8a"
        private const val DEFAULT_SHA_BUFFER_SIZE: Int = 1024 * 1024
        private const val DEFAULT_PREFIX_CACHE_ENABLED: Boolean = true
        private const val DEFAULT_PREFIX_CACHE_STRICT: Boolean = false
        private const val DEFAULT_RESPONSE_CACHE_TTL_SEC: Long = 0L
        private const val DEFAULT_RESPONSE_CACHE_MAX_ENTRIES: Int = 0

        fun fromEnvironment(): RuntimeConfig {
            val artifactPayloadByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to resolvePayload(
                    sideLoadPathEnv = QWEN_0_8B_SIDELOAD_PATH_ENV,
                    fallbackSeed = "sideload:${ModelCatalog.QWEN_3_5_0_8B_Q4}:v1",
                ),
                ModelCatalog.QWEN_3_5_2B_Q4 to resolvePayload(
                    sideLoadPathEnv = QWEN_2B_SIDELOAD_PATH_ENV,
                    fallbackSeed = "sideload:${ModelCatalog.QWEN_3_5_2B_Q4}:v1",
                ),
            )
            val artifactFilePathByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to System.getenv(QWEN_0_8B_SIDELOAD_PATH_ENV).orEmpty(),
                ModelCatalog.QWEN_3_5_2B_Q4 to System.getenv(QWEN_2B_SIDELOAD_PATH_ENV).orEmpty(),
            )
            val artifactSha256ByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to resolveSha(
                    envValue = System.getenv(QWEN_0_8B_SHA256_ENV),
                    payload = artifactPayloadByModelId.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4),
                    sideLoadPath = System.getenv(QWEN_0_8B_SIDELOAD_PATH_ENV),
                ),
                ModelCatalog.QWEN_3_5_2B_Q4 to resolveSha(
                    envValue = System.getenv(QWEN_2B_SHA256_ENV),
                    payload = artifactPayloadByModelId.getValue(ModelCatalog.QWEN_3_5_2B_Q4),
                    sideLoadPath = System.getenv(QWEN_2B_SIDELOAD_PATH_ENV),
                ),
            )
            val issuer = System.getenv(MODEL_PROVENANCE_ISSUER_ENV)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_PROVENANCE_ISSUER
            val artifactProvenanceIssuerByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to issuer,
                ModelCatalog.QWEN_3_5_2B_Q4 to issuer,
            )
            val artifactProvenanceSignatureByModelId = mapOf(
                ModelCatalog.QWEN_3_5_0_8B_Q4 to resolveProvenanceSignature(
                    envValue = System.getenv(QWEN_0_8B_PROVENANCE_SIG_ENV),
                    issuer = artifactProvenanceIssuerByModelId.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4),
                    modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                    payload = artifactPayloadByModelId.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4),
                ),
                ModelCatalog.QWEN_3_5_2B_Q4 to resolveProvenanceSignature(
                    envValue = System.getenv(QWEN_2B_PROVENANCE_SIG_ENV),
                    issuer = artifactProvenanceIssuerByModelId.getValue(ModelCatalog.QWEN_3_5_2B_Q4),
                    modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                    payload = artifactPayloadByModelId.getValue(ModelCatalog.QWEN_3_5_2B_Q4),
                ),
            )
            val runtimeCompatibilityTag = System.getenv(MODEL_RUNTIME_COMPATIBILITY_ENV)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_RUNTIME_COMPATIBILITY_TAG
            val requireNativeRuntimeForStartupChecks = booleanEnv(
                name = REQUIRE_NATIVE_RUNTIME_STARTUP_ENV,
                defaultValue = true,
            )
            val prefixCacheEnabled = booleanEnv(
                name = PREFIX_CACHE_ENABLED_ENV,
                defaultValue = DEFAULT_PREFIX_CACHE_ENABLED,
            )
            val prefixCacheStrict = booleanEnv(
                name = PREFIX_CACHE_STRICT_ENV,
                defaultValue = DEFAULT_PREFIX_CACHE_STRICT,
            )
            val responseCacheTtlSec = longEnv(
                name = RESPONSE_CACHE_TTL_SEC_ENV,
                defaultValue = DEFAULT_RESPONSE_CACHE_TTL_SEC,
                minValue = 0L,
            )
            val responseCacheMaxEntries = intEnv(
                name = RESPONSE_CACHE_MAX_ENTRIES_ENV,
                defaultValue = DEFAULT_RESPONSE_CACHE_MAX_ENTRIES,
                minValue = 0,
            )
            val streamContractV2Enabled = booleanEnv(
                name = STREAM_CONTRACT_V2_ENV,
                defaultValue = true,
            )
            return RuntimeConfig(
                artifactPayloadByModelId = artifactPayloadByModelId,
                artifactFilePathByModelId = artifactFilePathByModelId,
                artifactSha256ByModelId = artifactSha256ByModelId,
                artifactProvenanceIssuerByModelId = artifactProvenanceIssuerByModelId,
                artifactProvenanceSignatureByModelId = artifactProvenanceSignatureByModelId,
                runtimeCompatibilityTag = runtimeCompatibilityTag,
                requireNativeRuntimeForStartupChecks = requireNativeRuntimeForStartupChecks,
                prefixCacheEnabled = prefixCacheEnabled,
                prefixCacheStrict = prefixCacheStrict,
                responseCacheTtlSec = responseCacheTtlSec,
                responseCacheMaxEntries = responseCacheMaxEntries,
                streamContractV2Enabled = streamContractV2Enabled,
            )
        }

        private fun booleanEnv(name: String, defaultValue: Boolean): Boolean {
            val raw = System.getenv(name)
                ?.trim()
                ?.lowercase()
                ?: return defaultValue
            return when (raw) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> defaultValue
            }
        }

        private fun intEnv(name: String, defaultValue: Int, minValue: Int): Int {
            val parsed = System.getenv(name)?.trim()?.toIntOrNull() ?: return defaultValue
            return parsed.coerceAtLeast(minValue)
        }

        private fun longEnv(name: String, defaultValue: Long, minValue: Long): Long {
            val parsed = System.getenv(name)?.trim()?.toLongOrNull() ?: return defaultValue
            return parsed.coerceAtLeast(minValue)
        }

        private fun resolvePayload(sideLoadPathEnv: String, fallbackSeed: String): ByteArray {
            val sideLoadDefined = System.getenv(sideLoadPathEnv)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return fallbackSeed.encodeToByteArray()
            return "sideload-path:$sideLoadDefined".encodeToByteArray()
        }

        private fun resolveSha(envValue: String?, payload: ByteArray, sideLoadPath: String?): String {
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
            return sha256Hex(payload)
        }

        private fun resolveProvenanceSignature(
            envValue: String?,
            issuer: String,
            modelId: String,
            payload: ByteArray,
        ): String {
            val envSig = envValue?.trim()?.takeIf { it.isNotEmpty() }
            if (envSig != null) {
                return envSig
            }
            val payloadSha = sha256Hex(payload)
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
