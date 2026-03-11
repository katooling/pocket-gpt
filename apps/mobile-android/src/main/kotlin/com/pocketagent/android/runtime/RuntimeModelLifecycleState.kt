package com.pocketagent.android.runtime

import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.runtime.RuntimeLoadedModel

data class RuntimeModelLifecycleSnapshot(
    val state: ModelLifecycleState = ModelLifecycleState.UNLOADED,
    val loadedModel: RuntimeLoadedModel? = null,
    val requestedModel: RuntimeLoadedModel? = null,
    val lastUsedModel: RuntimeLoadedModel? = null,
    val errorCode: ModelLifecycleErrorCode? = null,
    val errorDetail: String? = null,
    val loadingDetail: String? = null,
    val queuedOffload: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun initial(): RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot()
    }
}

const val MODEL_OFFLOAD_REASON_MANUAL = "manual_model_offload"
