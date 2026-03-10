package com.pocketagent.runtime

import com.pocketagent.nativebridge.RuntimeGenerationConfig
import com.pocketagent.nativebridge.RuntimeSamplingConfig

internal fun PerformanceRuntimeConfig.toRuntimeGenerationConfig(): RuntimeGenerationConfig {
    return RuntimeGenerationConfig(
        nThreads = nThreads,
        nThreadsBatch = nThreadsBatch,
        nBatch = nBatch,
        nUbatch = nUbatch,
        nCtx = nCtx,
        gpuEnabled = gpuEnabled,
        gpuLayers = gpuLayers,
        quantizedKvCache = quantizedKvCache,
        sampling = RuntimeSamplingConfig(
            temperature = temperature,
            topK = topK,
            topP = topP,
        ),
        speculativeEnabled = speculativeEnabled,
        speculativeDraftModelId = speculativeDraftModelId,
        speculativeMaxDraftTokens = speculativeMaxDraftTokens,
        speculativeMinDraftTokens = speculativeMinDraftTokens,
        speculativeDraftGpuLayers = speculativeDraftGpuLayers,
        useMmap = useMmap,
        useMlock = useMlock,
        nKeep = nKeep,
    )
}
