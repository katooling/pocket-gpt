package com.pocketagent.android.ui

import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult

internal interface ModelOperationHandler {
    suspend fun loadModel(modelId: String, version: String): RuntimeModelLifecycleCommandResult?

    suspend fun loadLastUsedModel(): RuntimeModelLifecycleCommandResult?

    suspend fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult?
}
