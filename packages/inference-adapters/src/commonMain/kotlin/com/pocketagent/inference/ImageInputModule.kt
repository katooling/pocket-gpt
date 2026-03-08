package com.pocketagent.inference

data class ImageRequest(
    val imagePath: String,
    val prompt: String,
    val maxTokens: Int,
)

interface ImageInputModule {
    fun analyzeImage(request: ImageRequest): String

    fun analyzeImageResult(request: ImageRequest): ImageInputResult {
        return ImageInputResult.fromLegacy(analyzeImage(request))
    }
}

sealed interface ImageInputResult {
    data class Success(val content: String) : ImageInputResult

    data class ValidationFailure(
        val code: String,
        val detail: String,
    ) : ImageInputResult

    data class RuntimeFailure(
        val code: String,
        val detail: String,
    ) : ImageInputResult

    companion object {
        fun fromLegacy(raw: String): ImageInputResult {
            if (raw.startsWith("IMAGE_VALIDATION_ERROR:")) {
                val parts = raw.split(":", limit = 3)
                val code = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "IMAGE_VALIDATION_ERROR" }
                val detail = parts.getOrNull(2)?.trim().orEmpty().ifBlank { raw }
                return ValidationFailure(code = code, detail = detail)
            }
            if (raw.startsWith("IMAGE_RUNTIME_ERROR:")) {
                val parts = raw.split(":", limit = 3)
                val code = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "IMAGE_RUNTIME_ERROR" }
                val detail = parts.getOrNull(2)?.trim().orEmpty().ifBlank { raw }
                return RuntimeFailure(code = code, detail = detail)
            }
            return Success(raw)
        }
    }
}

class RuntimeImageInputModule(
    private val inferenceModule: InferenceModule,
) : ImageInputModule {
    override fun analyzeImage(request: ImageRequest): String {
        return analyzeImageResult(request).toLegacyString()
    }

    override fun analyzeImageResult(request: ImageRequest): ImageInputResult {
        val path = request.imagePath.trim()
        if (path.isEmpty()) {
            return ImageInputResult.ValidationFailure(
                code = "MISSING_IMAGE_PATH",
                detail = "image_path is required",
            )
        }

        val promptSummary = request.prompt
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_PROMPT_CHARS)
        if (promptSummary.isBlank()) {
            return ImageInputResult.ValidationFailure(
                code = "MISSING_PROMPT",
                detail = "prompt is required",
            )
        }

        val extension = normalizeExtension(path)
        if (extension !in SUPPORTED_EXTENSIONS) {
            return ImageInputResult.ValidationFailure(
                code = "UNSUPPORTED_EXTENSION",
                detail = "extension '$extension' is not supported",
            )
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
            return ImageInputResult.RuntimeFailure(
                code = "RUNTIME_GENERATION_FAILED",
                detail = reason,
            )
        }

        val response = output.toString().trim()
        if (response.isBlank()) {
            return ImageInputResult.RuntimeFailure(
                code = "EMPTY_RESPONSE",
                detail = "runtime returned no image tokens",
            )
        }
        return ImageInputResult.Success(response)
    }

    private fun normalizeExtension(imagePath: String): String {
        val extension = imagePath.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
        return if (extension.isBlank()) "unknown" else extension
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

private fun ImageInputResult.toLegacyString(): String {
    return when (this) {
        is ImageInputResult.Success -> content
        is ImageInputResult.ValidationFailure -> "IMAGE_VALIDATION_ERROR:$code:$detail"
        is ImageInputResult.RuntimeFailure -> "IMAGE_RUNTIME_ERROR:$code:$detail"
    }
}
