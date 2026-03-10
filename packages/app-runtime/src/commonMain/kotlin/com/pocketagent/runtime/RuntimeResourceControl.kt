package com.pocketagent.runtime

interface RuntimeResourceControl {
    fun evictResidentModel(reason: String = "manual"): Boolean
    fun loadModel(modelId: String, modelVersion: String? = null): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.rejected(
            code = com.pocketagent.nativebridge.ModelLifecycleErrorCode.UNKNOWN,
            detail = "runtime_model_load_unsupported",
        )
    }
    fun offloadModel(reason: String = "manual"): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.applied()
    }
    fun loadedModel(): RuntimeLoadedModel? = null
    fun activeGenerationCount(): Int = 0
    fun touchKeepAlive(): Boolean = false
    fun shortenKeepAlive(ttlMs: Long): Boolean = false
    fun onTrimMemory(level: Int): Boolean = false
    fun onAppBackground(): Boolean = false
}
