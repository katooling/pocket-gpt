package com.pocketagent.inference

import java.security.MessageDigest

data class ModelArtifact(
    val modelId: String,
    val version: String,
    val fileName: String,
    val expectedSha256: String,
)

class ModelArtifactManager {
    private val manifests: MutableMap<String, ModelArtifact> = mutableMapOf()
    private var activeModelId: String = ModelCatalog.QWEN_3_5_0_8B_Q4

    fun registerArtifact(artifact: ModelArtifact) {
        manifests[artifact.modelId] = artifact
    }

    fun listArtifacts(): List<ModelArtifact> = manifests.values.sortedBy { it.modelId }

    fun setActiveModel(modelId: String): Boolean {
        if (!manifests.containsKey(modelId)) {
            return false
        }
        activeModelId = modelId
        return true
    }

    fun getActiveModel(): String = activeModelId

    fun verifyChecksum(modelId: String, bytes: ByteArray): Boolean {
        val manifest = manifests[modelId] ?: return false
        val actual = sha256Hex(bytes)
        return actual.equals(manifest.expectedSha256, ignoreCase = true)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder()
        digest.forEach { b -> builder.append("%02x".format(b)) }
        return builder.toString()
    }
}
