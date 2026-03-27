package com.pocketagent.core

data class RuntimeExecutionStats(
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    val tokensPerSec: Double? = null,
    val peakRssMb: Double? = null,
    val backendIdentity: String? = null,
    val appliedGpuLayers: Int? = null,
    val appliedDraftGpuLayers: Int? = null,
    val gpuLoadRetryCount: Int? = null,
    val modelLayerCount: Int? = null,
    val estimatedMaxGpuLayers: Int? = null,
    val estimatedMemoryMb: Double? = null,
)
