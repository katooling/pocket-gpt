package com.pocketagent.android.ui.state

data class UiError(
    val code: String,
    val userMessage: String,
    val technicalDetail: String? = null,
)

object UiErrorMapper {
    private const val STARTUP_CODE = "UI-STARTUP-001"
    private const val IMAGE_VALIDATION_CODE = "UI-IMG-VAL-001"
    private const val TOOL_SCHEMA_CODE = "UI-TOOL-SCHEMA-001"
    private const val RUNTIME_CODE = "UI-RUNTIME-001"

    fun startupFailure(startupChecks: List<String>): UiError? {
        if (startupChecks.isEmpty()) {
            return null
        }
        return UiError(
            code = STARTUP_CODE,
            userMessage = "Model startup is unavailable right now. Check setup and try again.",
            technicalDetail = startupChecks.joinToString(" | "),
        )
    }

    fun fromImageResult(result: String): UiError? {
        if (!result.startsWith("IMAGE_VALIDATION_ERROR:")) {
            return null
        }
        return UiError(
            code = IMAGE_VALIDATION_CODE,
            userMessage = "That image could not be processed. Use a supported file and try again.",
            technicalDetail = result,
        )
    }

    fun fromToolResult(result: String): UiError? {
        if (result.startsWith("TOOL_VALIDATION_ERROR:")) {
            return UiError(
                code = TOOL_SCHEMA_CODE,
                userMessage = "That tool request was rejected for safety. Update input and retry.",
                technicalDetail = result,
            )
        }
        if (result.contains("TOOL_VALIDATION_ERROR:")) {
            return UiError(
                code = TOOL_SCHEMA_CODE,
                userMessage = "That tool request was rejected for safety. Update input and retry.",
                technicalDetail = result,
            )
        }
        if (result.startsWith("Tool error:")) {
            return runtimeFailure(result)
        }
        return null
    }

    fun runtimeFailure(detail: String?): UiError {
        return UiError(
            code = RUNTIME_CODE,
            userMessage = "Request failed. Please try again.",
            technicalDetail = detail,
        )
    }
}
