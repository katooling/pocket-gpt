package com.pocketagent.core

data class RuntimeExecutionStats(
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    val tokensPerSec: Double? = null,
    val peakRssMb: Double? = null,
)
