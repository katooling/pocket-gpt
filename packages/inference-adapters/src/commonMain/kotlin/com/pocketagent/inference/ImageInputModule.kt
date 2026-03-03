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
        val extension = request.imagePath.substringAfterLast('.', missingDelimiterValue = "unknown")
        return "IMAGE_ANALYSIS(extension=$extension): ${request.prompt.take(120)}"
    }
}
