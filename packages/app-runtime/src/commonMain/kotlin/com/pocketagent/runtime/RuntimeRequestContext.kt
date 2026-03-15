package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState

data class RuntimeRequestContext(
    val deviceState: DeviceState,
    val maxTokens: Int = 128,
    val keepModelLoaded: Boolean = false,
    val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    val requestId: String = defaultRequestId(),
    val previousResponseId: String? = null,
    val performanceConfig: PerformanceRuntimeConfig = PerformanceRuntimeConfig.default(),
    val residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy(),
    val samplingOverrides: SamplingOverrides? = null,
)
