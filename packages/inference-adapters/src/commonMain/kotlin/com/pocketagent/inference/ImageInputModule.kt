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
        val path = request.imagePath.trim()
        if (path.isEmpty()) {
            return validationError("MISSING_IMAGE_PATH", "image_path is required")
        }

        val promptSummary = request.prompt
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_PROMPT_CHARS)
        if (promptSummary.isBlank()) {
            return validationError("MISSING_PROMPT", "prompt is required")
        }

        val extension = normalizeExtension(request.imagePath)
        if (extension !in SUPPORTED_EXTENSIONS) {
            return validationError("UNSUPPORTED_EXTENSION", "extension '$extension' is not supported")
        }

        val tokenCap = request.maxTokens.coerceAtLeast(0)
        return "IMAGE_ANALYSIS(v=1,extension=$extension,max_tokens=$tokenCap): $promptSummary"
    }

    private fun normalizeExtension(imagePath: String): String {
        val extension = imagePath.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
        return if (extension.isBlank()) "unknown" else extension
    }

    private fun validationError(code: String, detail: String): String {
        return "IMAGE_VALIDATION_ERROR:$code:$detail"
    }

    companion object {
        private const val MAX_PROMPT_CHARS = 120
        private val SUPPORTED_EXTENSIONS = setOf(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "bmp",
            "heic",
            "heif",
            "pdf",
        )
    }
}
