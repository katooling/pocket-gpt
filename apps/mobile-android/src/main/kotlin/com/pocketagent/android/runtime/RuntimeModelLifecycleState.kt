package com.pocketagent.android.runtime

import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLoadingStage
import com.pocketagent.nativebridge.ModelLifecycleState
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult

enum class RuntimeModelLifecyclePhase {
    UNLOADED,
    LOADING,
    LOADED,
    OFFLOADING,
    FAILED,
}

data class RuntimeModelLifecycleSnapshot(
    val state: ModelLifecycleState = ModelLifecycleState.UNLOADED,
    val loadedModel: RuntimeLoadedModel? = null,
    val requestedModel: RuntimeLoadedModel? = null,
    val lastUsedModel: RuntimeLoadedModel? = null,
    val errorCode: ModelLifecycleErrorCode? = null,
    val errorDetail: String? = null,
    val loadingDetail: String? = null,
    val loadingStage: ModelLoadingStage? = null,
    val loadingProgress: Float? = null,
    val queuedOffload: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun initial(): RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot()
    }
}

fun RuntimeModelLifecycleSnapshot.phase(): RuntimeModelLifecyclePhase {
    return when (state) {
        ModelLifecycleState.UNLOADED -> RuntimeModelLifecyclePhase.UNLOADED
        ModelLifecycleState.LOADING -> RuntimeModelLifecyclePhase.LOADING
        ModelLifecycleState.LOADED -> RuntimeModelLifecyclePhase.LOADED
        ModelLifecycleState.OFFLOADING -> RuntimeModelLifecyclePhase.OFFLOADING
        ModelLifecycleState.FAILED -> RuntimeModelLifecyclePhase.FAILED
    }
}

fun RuntimeModelLifecycleSnapshot.isLoading(): Boolean = phase() == RuntimeModelLifecyclePhase.LOADING

fun RuntimeModelLifecycleSnapshot.isOffloading(): Boolean = phase() == RuntimeModelLifecyclePhase.OFFLOADING

fun RuntimeModelLifecycleSnapshot.isFailed(): Boolean = phase() == RuntimeModelLifecyclePhase.FAILED

fun RuntimeModelLifecycleSnapshot.errorCodeName(): String? = errorCode?.name

fun RuntimeModelLifecycleCommandResult.errorCodeName(): String? = errorCode?.name

const val MODEL_OFFLOAD_REASON_MANUAL = "manual_model_offload"
