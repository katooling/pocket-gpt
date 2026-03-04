package com.pocketagent.inference

import java.security.MessageDigest

enum class ArtifactDistributionChannel {
    SIDE_LOAD_MANUAL_INTERNAL,
}

data class ModelArtifact(
    val modelId: String,
    val version: String,
    val fileName: String,
    val expectedSha256: String,
    val distributionChannel: ArtifactDistributionChannel = ArtifactDistributionChannel.SIDE_LOAD_MANUAL_INTERNAL,
    val provenanceIssuer: String = "internal-release",
    val provenanceSignature: String = "internal-signature-v1",
    val runtimeCompatibility: String = "android-arm64-v8a",
)

data class ModelArtifactManifest(
    val models: List<ModelManifestEntry>,
    val activeModelId: String,
    val activeVersion: String?,
)

data class ModelManifestEntry(
    val modelId: String,
    val latestVersion: String?,
    val artifacts: List<ModelArtifact>,
)

enum class ChecksumVerificationStatus {
    PASS,
    CHECKSUM_MISMATCH,
    UNKNOWN_MODEL,
    UNKNOWN_VERSION,
}

data class ChecksumVerificationResult(
    val status: ChecksumVerificationStatus,
    val modelId: String,
    val version: String?,
    val expectedSha256: String?,
    val actualSha256: String,
) {
    val passed: Boolean
        get() = status == ChecksumVerificationStatus.PASS
}

data class ArtifactValidationIssue(
    val modelId: String,
    val version: String,
    val code: String,
    val message: String,
)

enum class ArtifactVerificationStatus {
    PASS,
    PASS_LAST_KNOWN_GOOD,
    MANIFEST_INVALID,
    UNKNOWN_MODEL,
    UNKNOWN_VERSION,
    MISSING_PAYLOAD,
    CHECKSUM_MISMATCH,
    PROVENANCE_ISSUER_MISMATCH,
    PROVENANCE_SIGNATURE_MISMATCH,
    RUNTIME_INCOMPATIBLE,
}

data class ArtifactVerificationResult(
    val status: ArtifactVerificationStatus,
    val modelId: String,
    val version: String?,
    val expectedSha256: String?,
    val actualSha256: String?,
    val expectedIssuer: String?,
    val actualIssuer: String?,
    val expectedRuntimeCompatibility: String?,
    val actualRuntimeCompatibility: String?,
) {
    val passed: Boolean
        get() = status == ArtifactVerificationStatus.PASS || status == ArtifactVerificationStatus.PASS_LAST_KNOWN_GOOD
}

class ModelArtifactManager {
    private val manifests: MutableMap<String, MutableMap<String, ModelArtifact>> = mutableMapOf()
    private var activeModelId: String = ModelCatalog.QWEN_3_5_0_8B_Q4
    private var pinnedActiveVersion: String? = null
    private val lastKnownGoodArtifactByModelId: MutableMap<String, ModelArtifact> = mutableMapOf()

    fun registerArtifact(artifact: ModelArtifact) {
        val versions = manifests.getOrPut(artifact.modelId) { mutableMapOf() }
        versions[artifact.version] = artifact
    }

    fun listArtifacts(): List<ModelArtifact> = manifests.keys.sorted().flatMap { modelId ->
        manifests.getValue(modelId).values.sortedWith(
            Comparator { left, right ->
                compareVersions(left.version, right.version)
            },
        )
    }

    fun setActiveModel(modelId: String): Boolean {
        if (!manifests.containsKey(modelId)) {
            return false
        }
        activeModelId = modelId
        pinnedActiveVersion = null
        return true
    }

    fun setActiveModelVersion(modelId: String, version: String): Boolean {
        val modelVersions = manifests[modelId] ?: return false
        if (!modelVersions.containsKey(version)) {
            return false
        }
        activeModelId = modelId
        pinnedActiveVersion = version
        return true
    }

    fun getActiveModel(): String = activeModelId

    fun getActiveModelVersion(): String? = resolveArtifact(activeModelId, pinnedActiveVersion)?.version

    fun getActiveArtifact(): ModelArtifact? = resolveArtifact(activeModelId, pinnedActiveVersion)

    fun buildManifest(): ModelArtifactManifest {
        val entries = manifests.keys.sorted().map { modelId ->
            val artifacts = manifests.getValue(modelId).values.sortedWith(
                Comparator { left, right ->
                    compareVersions(left.version, right.version)
                },
            )
            ModelManifestEntry(
                modelId = modelId,
                latestVersion = artifacts.maxWithOrNull(
                    Comparator { left, right ->
                        compareVersions(left.version, right.version)
                    },
                )?.version,
                artifacts = artifacts,
            )
        }
        return ModelArtifactManifest(
            models = entries,
            activeModelId = activeModelId,
            activeVersion = getActiveModelVersion(),
        )
    }

    fun verifyChecksumResult(modelId: String, bytes: ByteArray): ChecksumVerificationResult {
        return verifyChecksumResultInternal(modelId = modelId, version = null, bytes = bytes)
    }

    fun verifyChecksumResult(
        modelId: String,
        version: String,
        bytes: ByteArray,
    ): ChecksumVerificationResult {
        return verifyChecksumResultInternal(modelId = modelId, version = version, bytes = bytes)
    }

    fun validateManifest(): List<ArtifactValidationIssue> {
        val issues = mutableListOf<ArtifactValidationIssue>()
        manifests.keys.sorted().forEach { modelId ->
            val artifacts = manifests.getValue(modelId)
            artifacts.keys.sorted().forEach { version ->
                val artifact = artifacts.getValue(version)
                if (artifact.modelId.isBlank()) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "EMPTY_MODEL_ID",
                            message = "Model identifier must not be blank.",
                        ),
                    )
                }
                if (artifact.version.isBlank()) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "EMPTY_VERSION",
                            message = "Artifact version must not be blank.",
                        ),
                    )
                }
                if (artifact.fileName.isBlank()) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "EMPTY_FILE_NAME",
                            message = "Artifact file name must not be blank.",
                        ),
                    )
                }
                if (!SHA256_PATTERN.matches(artifact.expectedSha256)) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "INVALID_SHA256",
                            message = "Expected SHA-256 must be exactly 64 hex characters.",
                        ),
                    )
                }
                if (artifact.distributionChannel != ArtifactDistributionChannel.SIDE_LOAD_MANUAL_INTERNAL) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "UNSUPPORTED_DISTRIBUTION_CHANNEL",
                            message = "Only side-load/manual-internal distribution is allowed in this phase.",
                        ),
                    )
                }
                if (artifact.provenanceIssuer.isBlank()) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "EMPTY_PROVENANCE_ISSUER",
                            message = "Artifact provenance issuer must not be blank.",
                        ),
                    )
                }
                if (artifact.provenanceSignature.isBlank()) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "EMPTY_PROVENANCE_SIGNATURE",
                            message = "Artifact provenance signature must not be blank.",
                        ),
                    )
                }
                if (artifact.runtimeCompatibility.isBlank()) {
                    issues.add(
                        ArtifactValidationIssue(
                            modelId = modelId,
                            version = version,
                            code = "EMPTY_RUNTIME_COMPATIBILITY",
                            message = "Artifact runtime compatibility tag must not be blank.",
                        ),
                    )
                }
            }
        }
        return issues
    }

    fun getLastKnownGoodArtifact(modelId: String): ModelArtifact? = lastKnownGoodArtifactByModelId[modelId]

    fun verifyArtifactForLoad(
        modelId: String,
        version: String?,
        payload: ByteArray? = null,
        payloadSha256: String? = null,
        payloadPresent: Boolean = payload != null || !payloadSha256.isNullOrBlank(),
        provenanceIssuer: String,
        provenanceSignature: String,
        runtimeCompatibility: String,
    ): ArtifactVerificationResult {
        val manifestIssues = validateManifest()
        if (manifestIssues.isNotEmpty()) {
            return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.MANIFEST_INVALID,
                modelId = modelId,
                version = version,
                expectedSha256 = null,
                actualSha256 = null,
                expectedIssuer = null,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = null,
                actualRuntimeCompatibility = runtimeCompatibility,
            )
        }

        if (!manifests.containsKey(modelId)) {
            return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.UNKNOWN_MODEL,
                modelId = modelId,
                version = version,
                expectedSha256 = null,
                actualSha256 = resolveActualSha(payload = payload, payloadSha256 = payloadSha256),
                expectedIssuer = null,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = null,
                actualRuntimeCompatibility = runtimeCompatibility,
            )
        }

        val artifact = resolveArtifact(modelId, version)
            ?: return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.UNKNOWN_VERSION,
                modelId = modelId,
                version = version,
                expectedSha256 = null,
                actualSha256 = resolveActualSha(payload = payload, payloadSha256 = payloadSha256),
                expectedIssuer = null,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = null,
                actualRuntimeCompatibility = runtimeCompatibility,
            )

        val actualSha = resolveActualSha(payload = payload, payloadSha256 = payloadSha256)
        if (!payloadPresent || actualSha == null) {
            val knownGood = lastKnownGoodArtifactByModelId[modelId]
            if (knownGood != null && knownGood.version == artifact.version) {
                return ArtifactVerificationResult(
                    status = ArtifactVerificationStatus.PASS_LAST_KNOWN_GOOD,
                    modelId = knownGood.modelId,
                    version = knownGood.version,
                    expectedSha256 = knownGood.expectedSha256,
                    actualSha256 = actualSha,
                    expectedIssuer = knownGood.provenanceIssuer,
                    actualIssuer = provenanceIssuer,
                    expectedRuntimeCompatibility = knownGood.runtimeCompatibility,
                    actualRuntimeCompatibility = runtimeCompatibility,
                )
            }
            return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.MISSING_PAYLOAD,
                modelId = artifact.modelId,
                version = artifact.version,
                expectedSha256 = artifact.expectedSha256,
                actualSha256 = actualSha,
                expectedIssuer = artifact.provenanceIssuer,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = artifact.runtimeCompatibility,
                actualRuntimeCompatibility = runtimeCompatibility,
            )
        }
        if (!actualSha.equals(artifact.expectedSha256, ignoreCase = true)) {
            return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.CHECKSUM_MISMATCH,
                modelId = artifact.modelId,
                version = artifact.version,
                expectedSha256 = artifact.expectedSha256,
                actualSha256 = actualSha,
                expectedIssuer = artifact.provenanceIssuer,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = artifact.runtimeCompatibility,
                actualRuntimeCompatibility = runtimeCompatibility,
            )
        }
        if (provenanceIssuer != artifact.provenanceIssuer) {
            return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.PROVENANCE_ISSUER_MISMATCH,
                modelId = artifact.modelId,
                version = artifact.version,
                expectedSha256 = artifact.expectedSha256,
                actualSha256 = actualSha,
                expectedIssuer = artifact.provenanceIssuer,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = artifact.runtimeCompatibility,
                actualRuntimeCompatibility = runtimeCompatibility,
            )
        }
        if (provenanceSignature != artifact.provenanceSignature) {
            return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.PROVENANCE_SIGNATURE_MISMATCH,
                modelId = artifact.modelId,
                version = artifact.version,
                expectedSha256 = artifact.expectedSha256,
                actualSha256 = actualSha,
                expectedIssuer = artifact.provenanceIssuer,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = artifact.runtimeCompatibility,
                actualRuntimeCompatibility = runtimeCompatibility,
            )
        }
        if (runtimeCompatibility != artifact.runtimeCompatibility) {
            return ArtifactVerificationResult(
                status = ArtifactVerificationStatus.RUNTIME_INCOMPATIBLE,
                modelId = artifact.modelId,
                version = artifact.version,
                expectedSha256 = artifact.expectedSha256,
                actualSha256 = actualSha,
                expectedIssuer = artifact.provenanceIssuer,
                actualIssuer = provenanceIssuer,
                expectedRuntimeCompatibility = artifact.runtimeCompatibility,
                actualRuntimeCompatibility = runtimeCompatibility,
            )
        }

        lastKnownGoodArtifactByModelId[modelId] = artifact
        return ArtifactVerificationResult(
            status = ArtifactVerificationStatus.PASS,
            modelId = artifact.modelId,
            version = artifact.version,
            expectedSha256 = artifact.expectedSha256,
            actualSha256 = actualSha,
            expectedIssuer = artifact.provenanceIssuer,
            actualIssuer = provenanceIssuer,
            expectedRuntimeCompatibility = artifact.runtimeCompatibility,
            actualRuntimeCompatibility = runtimeCompatibility,
        )
    }

    fun verifyChecksum(modelId: String, bytes: ByteArray): Boolean {
        return verifyChecksumResult(modelId, bytes).passed
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder()
        digest.forEach { b -> builder.append("%02x".format(b)) }
        return builder.toString()
    }

    private fun resolveActualSha(payload: ByteArray?, payloadSha256: String?): String? {
        val normalizedSha = payloadSha256
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (normalizedSha != null) {
            return normalizedSha
        }
        return payload?.let(::sha256Hex)
    }

    private fun verifyChecksumResultInternal(
        modelId: String,
        version: String?,
        bytes: ByteArray,
    ): ChecksumVerificationResult {
        val actual = sha256Hex(bytes)
        if (!manifests.containsKey(modelId)) {
            return ChecksumVerificationResult(
                status = ChecksumVerificationStatus.UNKNOWN_MODEL,
                modelId = modelId,
                version = version,
                expectedSha256 = null,
                actualSha256 = actual,
            )
        }

        val artifact = resolveArtifact(modelId, version)
        if (artifact == null) {
            return ChecksumVerificationResult(
                status = ChecksumVerificationStatus.UNKNOWN_VERSION,
                modelId = modelId,
                version = version,
                expectedSha256 = null,
                actualSha256 = actual,
            )
        }

        val status = if (actual.equals(artifact.expectedSha256, ignoreCase = true)) {
            ChecksumVerificationStatus.PASS
        } else {
            ChecksumVerificationStatus.CHECKSUM_MISMATCH
        }
        return ChecksumVerificationResult(
            status = status,
            modelId = artifact.modelId,
            version = artifact.version,
            expectedSha256 = artifact.expectedSha256,
            actualSha256 = actual,
        )
    }

    private fun resolveArtifact(modelId: String, version: String?): ModelArtifact? {
        val modelVersions = manifests[modelId] ?: return null
        return if (version == null) {
            modelVersions.values.maxWithOrNull(
                Comparator { left, right ->
                    compareVersions(left.version, right.version)
                },
            )
        } else {
            modelVersions[version]
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = parseVersion(left)
        val rightParts = parseVersion(right)

        val maxSegments = maxOf(leftParts.coreSegments.size, rightParts.coreSegments.size)
        for (index in 0 until maxSegments) {
            val leftToken = leftParts.coreSegments.getOrElse(index) { "0" }
            val rightToken = rightParts.coreSegments.getOrElse(index) { "0" }
            val coreCmp = compareVersionToken(leftToken, rightToken)
            if (coreCmp != 0) {
                return coreCmp
            }
        }

        val leftPre = leftParts.preRelease
        val rightPre = rightParts.preRelease
        if (leftPre == null && rightPre != null) {
            return 1
        }
        if (leftPre != null && rightPre == null) {
            return -1
        }
        if (leftPre != null && rightPre != null) {
            val preCmp = comparePreRelease(leftPre, rightPre)
            if (preCmp != 0) {
                return preCmp
            }
        }
        return left.compareTo(right)
    }

    private fun parseVersion(version: String): ParsedVersion {
        val split = version.trim().split("-", limit = 2)
        val core = split[0].ifBlank { "0" }
        return ParsedVersion(
            coreSegments = core.split("."),
            preRelease = split.getOrNull(1),
        )
    }

    private fun comparePreRelease(left: String, right: String): Int {
        val leftParts = left.split(".")
        val rightParts = right.split(".")
        val maxParts = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxParts) {
            val leftToken = leftParts.getOrElse(index) { "0" }
            val rightToken = rightParts.getOrElse(index) { "0" }
            val cmp = comparePreReleaseToken(leftToken, rightToken)
            if (cmp != 0) {
                return cmp
            }
        }
        return 0
    }

    private fun compareVersionToken(left: String, right: String): Int {
        val leftInt = left.toIntOrNull()
        val rightInt = right.toIntOrNull()
        if (leftInt != null && rightInt != null) {
            return leftInt.compareTo(rightInt)
        }
        if (leftInt != null && rightInt == null) {
            return 1
        }
        if (leftInt == null && rightInt != null) {
            return -1
        }
        return left.compareTo(right)
    }

    private fun comparePreReleaseToken(left: String, right: String): Int {
        val leftInt = left.toIntOrNull()
        val rightInt = right.toIntOrNull()
        if (leftInt != null && rightInt != null) {
            return leftInt.compareTo(rightInt)
        }
        if (leftInt != null && rightInt == null) {
            return -1
        }
        if (leftInt == null && rightInt != null) {
            return 1
        }
        return left.compareTo(right)
    }

    private data class ParsedVersion(
        val coreSegments: List<String>,
        val preRelease: String?,
    )

    private companion object {
        val SHA256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
    }
}
