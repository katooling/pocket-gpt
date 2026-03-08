package com.pocketagent.android.ui.state

import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ToolExecutionResult

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
            normalized.contains("model_artifact_config_missing") ->
                "Runtime artifact metadata is missing. Reinstall or re-provision models, then refresh checks."
            normalized.contains("checksum_mismatch") || normalized.contains("checksum mismatch") ->
                "Model verification failed (checksum mismatch). Re-download or re-import the model."
            normalized.contains("provenance_issuer_mismatch") ||
                normalized.contains("provenance_signature_mismatch") ||
                normalized.contains("provenance") ->
                "Model verification failed (provenance mismatch). Use a trusted model source and retry."
            normalized.contains("runtime_incompatible") || normalized.contains("runtime compatibility") ->
                "Model runtime compatibility failed. Import a compatible model build and refresh checks."
            normalized.contains("build is missing native runtime library") ||
                normalized.contains("libpocket_llama.so") ->
                "This app build is missing the native runtime. Install a full build and retry."
            normalized.contains("runtime backend is adb_fallback") || normalized.contains("runtime backend is unavailable") ->
                "Native runtime backend is unavailable. Confirm device/runtime setup and retry."
            normalized.contains("template_unavailable") || normalized.contains("model profile missing") ->
                "Model interaction template is unavailable. Reinstall/update model setup, then refresh checks."
            normalized.contains("startup checks timed out") || normalized.contains("timed out") ->
                "Runtime checks timed out. Refresh runtime checks before sending a message."
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

    fun fromImageResult(result: ImageAnalysisResult): UiError? {
        return when (result) {
            is ImageAnalysisResult.Success -> null
            is ImageAnalysisResult.Failure -> when (result.failure) {
                is com.pocketagent.runtime.ImageFailure.Validation -> UiError(
                    code = IMAGE_VALIDATION_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
                else -> UiError(
                    code = RUNTIME_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
            }
        }
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

    fun fromToolResult(result: ToolExecutionResult): UiError? {
        return when (result) {
            is ToolExecutionResult.Success -> null
            is ToolExecutionResult.Failure -> when (result.failure) {
                is com.pocketagent.runtime.ToolFailure.Validation -> UiError(
                    code = TOOL_SCHEMA_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
                else -> UiError(
                    code = RUNTIME_CODE,
                    userMessage = result.failure.userMessage,
                    technicalDetail = result.failure.technicalDetail,
                )
            }
        }
    }

    fun runtimeFailure(detail: String?): UiError {
        return UiError(
            code = RUNTIME_CODE,
            userMessage = "Request failed. Please try again.",
            technicalDetail = detail,
        )
    }

    fun runtimeCancelled(reason: String?): UiError {
        val normalized = reason?.trim().orEmpty().ifBlank { "cancelled" }
        return UiError(
            code = RUNTIME_CODE,
            userMessage = "Request was cancelled. Send again when ready.",
            technicalDetail = "Generation cancelled: $normalized.",
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
