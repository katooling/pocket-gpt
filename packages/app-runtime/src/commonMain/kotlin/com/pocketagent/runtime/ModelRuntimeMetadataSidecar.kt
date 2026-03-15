package com.pocketagent.runtime

import com.pocketagent.nativebridge.ModelRuntimeMetadata
import java.io.File

internal object ModelRuntimeMetadataSidecar {
    fun read(modelPath: String): ModelRuntimeMetadata? {
        val normalizedPath = modelPath.trim()
        if (normalizedPath.isEmpty()) {
            return null
        }
        val modelFile = File(normalizedPath)
        val metadataFile = File("$normalizedPath.meta.json")
        if (!metadataFile.exists() || !metadataFile.isFile) {
            return null
        }
        val content = runCatching { metadataFile.readText() }.getOrNull() ?: return null
        val metadata = ModelRuntimeMetadata(
            layerCount = extractInt(content, "blockCount"),
            sizeBytes = modelFile.takeIf { it.exists() && it.isFile }?.length()?.takeIf { it > 0L },
            contextLength = extractInt(content, "contextLength"),
            embeddingSize = extractInt(content, "embeddingSize"),
            headCountKv = extractInt(content, "headCountKv"),
            keyLength = extractInt(content, "keyLength"),
            valueLength = extractInt(content, "valueLength"),
            vocabSize = extractInt(content, "vocabSize"),
            slidingWindow = extractInt(content, "slidingWindow"),
            architecture = extractString(content, "architecture"),
        )
        return metadata.takeIf { candidate ->
            candidate.layerCount != null ||
                candidate.sizeBytes != null ||
                candidate.contextLength != null ||
                candidate.embeddingSize != null ||
                candidate.headCountKv != null ||
                candidate.keyLength != null ||
                candidate.valueLength != null ||
                candidate.vocabSize != null ||
                candidate.slidingWindow != null ||
                !candidate.architecture.isNullOrBlank()
        }
    }

    private fun extractInt(content: String, key: String): Int? {
        return Regex(
            "\"$key\"\\s*:\\s*(\\d+)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractString(content: String, key: String): String? {
        return Regex(
            "\"$key\"\\s*:\\s*\"([^\"]+)\"",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(content)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
