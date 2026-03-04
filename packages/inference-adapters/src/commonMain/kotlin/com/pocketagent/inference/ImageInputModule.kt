package com.pocketagent.inference

data class ImageRequest(
    val imagePath: String,
    val prompt: String,
    val maxTokens: Int,
)

interface ImageInputModule {
    fun analyzeImage(request: ImageRequest): String
}

class SmokeImageInputModule : ImageInputModule {
    override fun analyzeImage(request: ImageRequest): String {
        val extension = normalizeExtension(request.imagePath)
        val promptSummary = request.prompt
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_PROMPT_CHARS)
        val tokenCap = request.maxTokens.coerceAtLeast(0)
        return "IMAGE_ANALYSIS(v=1,extension=$extension,max_tokens=$tokenCap): $promptSummary"
    }

    private fun normalizeExtension(imagePath: String): String {
        val extension = imagePath.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
        return if (extension.isBlank()) "unknown" else extension
    }

    companion object {
        private const val MAX_PROMPT_CHARS = 120
    }
}
