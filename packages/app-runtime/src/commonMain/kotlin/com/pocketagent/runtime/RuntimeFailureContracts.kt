package com.pocketagent.runtime

sealed interface RuntimeFailure {
    val code: String
    val userMessage: String
    val technicalDetail: String?
}

sealed interface ToolFailure : RuntimeFailure {
    data class Validation(
        override val code: String,
        override val userMessage: String,
        override val technicalDetail: String?,
    ) : ToolFailure

    data class Execution(
        override val code: String,
        override val userMessage: String,
        override val technicalDetail: String?,
    ) : ToolFailure

    data class PolicyDenied(
        override val code: String = "tool_policy_denied",
        override val userMessage: String = "Tool request blocked by policy.",
        override val technicalDetail: String? = null,
    ) : ToolFailure
}

sealed interface ImageFailure : RuntimeFailure {
    data class Validation(
        override val code: String,
        override val userMessage: String,
        override val technicalDetail: String?,
    ) : ImageFailure

    data class Runtime(
        override val code: String,
        override val userMessage: String,
        override val technicalDetail: String?,
    ) : ImageFailure

    data class PolicyDenied(
        override val code: String = "image_policy_denied",
        override val userMessage: String = "Image request blocked by policy.",
        override val technicalDetail: String? = null,
    ) : ImageFailure
}

sealed interface ToolExecutionResult {
    data class Success(val content: String) : ToolExecutionResult
    data class Failure(val failure: ToolFailure) : ToolExecutionResult

    companion object {
        fun fromLegacy(raw: String): ToolExecutionResult {
            if (raw.startsWith("TOOL_VALIDATION_ERROR:")) {
                val parts = raw.split(":", limit = 3)
                val code = parts.getOrNull(1)?.ifBlank { null } ?: "tool_validation_error"
                val detail = parts.getOrNull(2)
                return Failure(
                    ToolFailure.Validation(
                        code = code.lowercase(),
                        userMessage = "That tool request was rejected for safety.",
                        technicalDetail = detail ?: raw,
                    ),
                )
            }
            if (raw.startsWith("Tool error:")) {
                return Failure(
                    ToolFailure.Execution(
                        code = "tool_runtime_error",
                        userMessage = "Tool request failed.",
                        technicalDetail = raw,
                    ),
                )
            }
            return Success(raw)
        }
    }
}

sealed interface ImageAnalysisResult {
    data class Success(val content: String) : ImageAnalysisResult
    data class Failure(val failure: ImageFailure) : ImageAnalysisResult

    companion object {
        fun fromLegacy(raw: String): ImageAnalysisResult {
            if (raw.startsWith("IMAGE_VALIDATION_ERROR:")) {
                val parts = raw.split(":", limit = 3)
                val code = parts.getOrNull(1)?.ifBlank { null } ?: "image_validation_error"
                val detail = parts.getOrNull(2)
                return Failure(
                    ImageFailure.Validation(
                        code = code.lowercase(),
                        userMessage = "That image could not be processed.",
                        technicalDetail = detail ?: raw,
                    ),
                )
            }
            if (raw.startsWith("IMAGE_RUNTIME_ERROR:")) {
                val parts = raw.split(":", limit = 3)
                val code = parts.getOrNull(1)?.ifBlank { null } ?: "image_runtime_error"
                val detail = parts.getOrNull(2)
                return Failure(
                    ImageFailure.Runtime(
                        code = code.lowercase(),
                        userMessage = "Image analysis failed.",
                        technicalDetail = detail ?: raw,
                    ),
                )
            }
            return Success(raw)
        }
    }
}

fun ToolExecutionResult.toLegacyString(): String {
    return when (this) {
        is ToolExecutionResult.Success -> content
        is ToolExecutionResult.Failure -> when (val typed = failure) {
            is ToolFailure.Validation -> {
                val detail = typed.technicalDetail ?: typed.userMessage
                "TOOL_VALIDATION_ERROR:${typed.code.uppercase()}:$detail"
            }
            is ToolFailure.PolicyDenied -> "Tool error: ${typed.technicalDetail ?: typed.userMessage}"
            is ToolFailure.Execution -> "Tool error: ${typed.technicalDetail ?: typed.userMessage}"
        }
    }
}

fun ImageAnalysisResult.toLegacyString(): String {
    return when (this) {
        is ImageAnalysisResult.Success -> content
        is ImageAnalysisResult.Failure -> when (val typed = failure) {
            is ImageFailure.Validation -> {
                val detail = typed.technicalDetail ?: typed.userMessage
                "IMAGE_VALIDATION_ERROR:${typed.code.uppercase()}:$detail"
            }
            is ImageFailure.PolicyDenied -> {
                val detail = typed.technicalDetail ?: typed.userMessage
                "IMAGE_RUNTIME_ERROR:${typed.code.uppercase()}:$detail"
            }
            is ImageFailure.Runtime -> {
                val detail = typed.technicalDetail ?: typed.userMessage
                "IMAGE_RUNTIME_ERROR:${typed.code.uppercase()}:$detail"
            }
        }
    }
}
