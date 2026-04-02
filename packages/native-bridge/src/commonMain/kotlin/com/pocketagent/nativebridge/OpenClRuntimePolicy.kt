package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog

enum class OpenClQuantCompatibility {
    SAFE,
    UNSUPPORTED,
    UNKNOWN,
}

object OpenClRuntimePolicy {
    const val MIN_AUTOMATIC_ADRENO_GENERATION = 7

    private val safeQuantRegex = Regex(
        """(?:^|[._-])(q4[._-]?0|q6[._-]?k)(?:[._-]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val knownQuantRegex = Regex(
        """(?:^|[._-])(q[1-8](?:[._-][0-9a-z_]+)|iq[1-4](?:[._-][a-z]+)?|f16|f32|fp16|fp32|mxfp4(?:[._-]moe)?)(?:[._-]|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun releaseQuantCompatibility(
        modelPath: String?,
        modelId: String,
        modelVersion: String?,
    ): OpenClQuantCompatibility {
        val normalizedModelId = modelId.trim().lowercase()
        val normalizedModelPath = modelPath
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            .orEmpty()
            .trim()
            .lowercase()
        if (
            normalizedModelId == ModelCatalog.BONSAI_8B_Q1_0_G128 ||
            normalizedModelPath.contains("bonsai-8b")
        ) {
            return OpenClQuantCompatibility.SAFE
        }
        val versionCompatibility = classifyQuantHint(modelVersion.orEmpty())
        if (versionCompatibility != OpenClQuantCompatibility.UNKNOWN) {
            return versionCompatibility
        }
        val filenameStem = modelPath
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            .orEmpty()
            .lowercase()
        return buildList {
            if (filenameStem.isNotBlank()) {
                add(filenameStem)
            }
            if (normalizedModelId.isNotBlank()) {
                add(normalizedModelId)
            }
        }.asSequence()
            .map(::classifyQuantHint)
            .firstOrNull { compatibility -> compatibility != OpenClQuantCompatibility.UNKNOWN }
            ?: OpenClQuantCompatibility.UNKNOWN
    }

    fun isReleaseSafeQuantization(
        modelPath: String?,
        modelId: String,
        modelVersion: String?,
    ): Boolean = releaseQuantCompatibility(
        modelPath = modelPath,
        modelId = modelId,
        modelVersion = modelVersion,
    ) == OpenClQuantCompatibility.SAFE

    private fun classifyQuantHint(rawHint: String): OpenClQuantCompatibility {
        if (rawHint.isBlank()) {
            return OpenClQuantCompatibility.UNKNOWN
        }
        return when {
            safeQuantRegex.containsMatchIn(rawHint) -> OpenClQuantCompatibility.SAFE
            knownQuantRegex.containsMatchIn(rawHint) -> OpenClQuantCompatibility.UNSUPPORTED
            else -> OpenClQuantCompatibility.UNKNOWN
        }
    }
}
