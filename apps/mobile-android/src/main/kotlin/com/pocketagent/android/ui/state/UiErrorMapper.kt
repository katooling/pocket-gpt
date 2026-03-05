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
        val detail = startupChecks.joinToString(" | ")
        val normalized = detail.lowercase()
        val userMessage = when {
            normalized.contains("missing runtime model") ->
                "Runtime setup is incomplete. Download or import required models, then refresh checks."
            normalized.contains("checksum_mismatch") || normalized.contains("checksum mismatch") ->
                "Model verification failed (checksum mismatch). Re-download or re-import the model."
            normalized.contains("provenance_issuer_mismatch") ||
                normalized.contains("provenance_signature_mismatch") ||
                normalized.contains("provenance") ->
                "Model verification failed (provenance mismatch). Use a trusted model source and retry."
            normalized.contains("runtime_incompatible") || normalized.contains("runtime compatibility") ->
                "Model runtime compatibility failed. Import a compatible model build and refresh checks."
            normalized.contains("runtime backend is adb_fallback") || normalized.contains("runtime backend is unavailable") ->
                "Native runtime backend is unavailable. Confirm device/runtime setup and retry."
            else ->
                "Runtime setup is incomplete. Open model setup, refresh checks, and retry."
        }
        return UiError(
            code = STARTUP_CODE,
            userMessage = userMessage,
            technicalDetail = detail,
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

    fun runtimeTimeout(timeoutMs: Long): UiError {
        val seconds = (timeoutMs / 1000L).coerceAtLeast(1L)
        return UiError(
            code = RUNTIME_CODE,
            userMessage = "Request timed out. Confirm runtime readiness and retry.",
            technicalDetail = "Generation timed out after ${seconds}s.",
        )
    }
}
