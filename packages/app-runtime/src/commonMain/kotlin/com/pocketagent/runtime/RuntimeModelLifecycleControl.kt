package com.pocketagent.runtime

import com.pocketagent.nativebridge.ModelLifecycleErrorCode

// Re-export ModelLifecycleErrorCode for use by UI layer
@Suppress("unused")
typealias ModelLifecycleErrorCode = com.pocketagent.nativebridge.ModelLifecycleErrorCode

data class RuntimeLoadedModel(
    val modelId: String,
    val modelVersion: String? = null,
)

data class RuntimeModelLifecycleCommandResult(
    val success: Boolean,
    val queued: Boolean = false,
    val loadedModel: RuntimeLoadedModel? = null,
    val errorCode: ModelLifecycleErrorCode? = null,
    val detail: String? = null,
) {
    companion object {
        fun applied(loadedModel: RuntimeLoadedModel? = null): RuntimeModelLifecycleCommandResult {
            return RuntimeModelLifecycleCommandResult(
                success = true,
                queued = false,
                loadedModel = loadedModel,
            )
        }

        fun queued(loadedModel: RuntimeLoadedModel? = null, detail: String? = null): RuntimeModelLifecycleCommandResult {
            return RuntimeModelLifecycleCommandResult(
                success = true,
                queued = true,
                loadedModel = loadedModel,
                detail = detail,
            )
        }

        fun rejected(
            code: ModelLifecycleErrorCode,
            detail: String? = null,
            loadedModel: RuntimeLoadedModel? = null,
        ): RuntimeModelLifecycleCommandResult {
            return RuntimeModelLifecycleCommandResult(
                success = false,
                queued = false,
                loadedModel = loadedModel,
                errorCode = code,
                detail = detail,
            )
        }
    }
}
