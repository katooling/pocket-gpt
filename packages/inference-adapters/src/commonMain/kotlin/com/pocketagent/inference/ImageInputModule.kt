package com.pocketagent.inference

data class ImageRequest(
    val imagePath: String,
    val prompt: String,
    val maxTokens: Int,
)

interface ImageInputModule {
    fun analyzeImage(request: ImageRequest): String
}

class RuntimeImageInputModule(
    private val inferenceModule: InferenceModule,
) : ImageInputModule {
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

        val extension = normalizeExtension(path)
        if (extension !in SUPPORTED_EXTENSIONS) {
            return validationError("UNSUPPORTED_EXTENSION", "extension '$extension' is not supported")
        }

        val tokenCap = request.maxTokens.coerceAtLeast(1)
        val runtimePrompt = "image_extension=$extension image_prompt=$promptSummary"
        val output = StringBuilder()
        val generationResult = runCatching {
            inferenceModule.generateStream(
                InferenceRequest(
                    prompt = runtimePrompt,
                    maxTokens = tokenCap,
                ),
            ) { token ->
                output.append(token)
            }
        }
        if (generationResult.isFailure) {
            val reason = generationResult.exceptionOrNull()?.message?.take(MAX_ERROR_CHARS)?.ifBlank { "runtime failure" }
                ?: "runtime failure"
            return "IMAGE_RUNTIME_ERROR:RUNTIME_GENERATION_FAILED:$reason"
        }

        val response = output.toString().trim()
        if (response.isBlank()) {
            return "IMAGE_RUNTIME_ERROR:EMPTY_RESPONSE:runtime returned no image tokens"
        }
        return response
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
        private const val MAX_ERROR_CHARS = 120
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
