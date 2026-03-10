package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.RuntimeGenerationConfig
import java.io.File
import java.security.MessageDigest

data class ResolvedRuntimePlan(
    val modelId: String,
    val baseDefaults: PerformanceRuntimeConfig,
    val requestConfig: PerformanceRuntimeConfig,
    val effectiveConfig: PerformanceRuntimeConfig,
    val generationConfig: RuntimeGenerationConfig,
    val prefixCacheSlotId: String,
    val keepAliveMs: Long,
    val diagnostics: List<String>,
)

internal class RuntimePlanResolver(
    private val availableCpuThreads: () -> Int = { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) },
    private val modelFileSizeBytes: (String?) -> Long = { path ->
        path?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }
            ?.length()
            ?.coerceAtLeast(0L)
            ?: 0L
    },
) {
    fun resolve(
        sessionId: String,
        modelId: String,
        taskType: String,
        stopSequences: List<String>,
        requestConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
        deviceState: DeviceState,
        nativeInference: LlamaCppInferenceModule?,
    ): ResolvedRuntimePlan {
        val diagnostics = mutableListOf<String>()
        val baseDefaults = PerformanceRuntimeConfig.forProfile(
            profile = requestConfig.profile,
            availableCpuThreads = availableCpuThreads(),
            gpuEnabled = requestConfig.gpuEnabled,
            gpuLayers = requestConfig.gpuLayers,
        )
        if (baseDefaults != requestConfig) {
            diagnostics += "layer=request_override"
        }

        val modelSizeBytes = resolveModelSizeBytes(modelId = modelId, nativeInference = nativeInference)
        val modelLayerCount = nativeInference?.cachedModelLayerCount(modelId)

        var effectiveConfig = requestConfig.applyContextBucket(taskType = taskType, deviceState = deviceState)
        if (effectiveConfig != requestConfig) {
            diagnostics += "layer=context_bucket"
        }

        val thermalAdjustedConfig = effectiveConfig.withThermalAdaptiveOverrides(deviceState)
        if (thermalAdjustedConfig != effectiveConfig) {
            diagnostics += "layer=thermal_override"
        }
        effectiveConfig = thermalAdjustedConfig

        val estimatedGpuLayers = nativeInference?.cachedEstimatedMaxGpuLayers(modelId, effectiveConfig.nCtx)
        val gpuEstimatedConfig = effectiveConfig.applyGpuLayerEstimate(estimatedGpuLayers)
        if (gpuEstimatedConfig != effectiveConfig) {
            diagnostics += "layer=gpu_layer_estimate"
        }
        effectiveConfig = gpuEstimatedConfig

        val gpuClampedConfig = effectiveConfig.applyGpuLayerCeiling(modelLayerCount)
        if (gpuClampedConfig != effectiveConfig) {
            diagnostics += "layer=gpu_layer_ceiling"
        }
        effectiveConfig = gpuClampedConfig

        val speculativeGated = effectiveConfig.gateSpeculative(
            modelId = modelId,
            deviceState = deviceState,
            nativeInference = nativeInference,
        )
        if (speculativeGated != effectiveConfig) {
            diagnostics += "layer=speculative_gate"
        }
        effectiveConfig = speculativeGated

        val mmapAdjustedConfig = effectiveConfig.applyMmapStrategy(
            deviceState = deviceState,
            modelSizeBytes = modelSizeBytes,
        )
        if (mmapAdjustedConfig != effectiveConfig) {
            diagnostics += "layer=mmap_strategy"
        }
        effectiveConfig = mmapAdjustedConfig

        val generationConfig = effectiveConfig.toRuntimeGenerationConfig().copy(
            useMlock = shouldUseMlock(deviceState = deviceState, modelSizeBytes = modelSizeBytes),
            nKeep = resolveNKeep(taskType = taskType, nCtx = effectiveConfig.nCtx),
        )
        val loadFingerprint = sha256Hex(
            listOf(
                modelId,
                generationConfig.toLoadConfig().toString(),
            ).joinToString("|"),
        )
        val templateFingerprint = sha256Hex(
            listOf(modelId, taskType, stopSequences.joinToString(separator = "\\u001f")).joinToString("|"),
        )
        val prefixCacheSlotId = sha256Hex(
            listOf("slot", sessionId, templateFingerprint, loadFingerprint).joinToString("|"),
        )
        val keepAliveMs = residencyPolicy.resolveKeepAliveMs(
            deviceState = deviceState,
            modelSizeBytes = modelSizeBytes,
        )
        return ResolvedRuntimePlan(
            modelId = modelId,
            baseDefaults = baseDefaults,
            requestConfig = requestConfig,
            effectiveConfig = effectiveConfig,
            generationConfig = generationConfig,
            prefixCacheSlotId = prefixCacheSlotId,
            keepAliveMs = keepAliveMs,
            diagnostics = diagnostics,
        )
    }

    private fun resolveModelSizeBytes(
        modelId: String,
        nativeInference: LlamaCppInferenceModule?,
    ): Long {
        return nativeInference?.cachedModelSizeBytes(modelId)
            ?: nativeInference?.registeredModelPath(modelId)?.let(modelFileSizeBytes)
            ?: 0L
    }
}

private fun PerformanceRuntimeConfig.applyGpuLayerEstimate(maxGpuLayers: Int?): PerformanceRuntimeConfig {
    val estimate = maxGpuLayers?.takeIf { it >= 0 } ?: return this
    if (!gpuEnabled) {
        return copy(gpuLayers = 0, speculativeDraftGpuLayers = 0)
    }
    val resolvedGpuLayers = when {
        gpuLayers < 0 -> estimate
        else -> minOf(gpuLayers, estimate)
    }
    return copy(
        gpuLayers = resolvedGpuLayers.coerceAtLeast(0),
        speculativeDraftGpuLayers = minOf(speculativeDraftGpuLayers, resolvedGpuLayers.coerceAtLeast(0)),
    )
}

private fun PerformanceRuntimeConfig.applyContextBucket(
    taskType: String,
    deviceState: DeviceState,
): PerformanceRuntimeConfig {
    val ramClassGb = deviceState.ramClassGb.coerceAtLeast(1)
    val cappedCtx = when {
        ramClassGb <= 4 -> 1024
        ramClassGb <= 6 -> if (taskType == "long_text") 1536 else 1024
        ramClassGb <= 8 -> if (taskType == "long_text") 2048 else 1536
        else -> nCtx
    }
    return copy(nCtx = minOf(nCtx, cappedCtx))
}

private fun PerformanceRuntimeConfig.applyGpuLayerCeiling(modelLayerCount: Int?): PerformanceRuntimeConfig {
    val ceiling = modelLayerCount?.takeIf { it > 0 } ?: return this
    return copy(
        gpuLayers = if (gpuLayers == -1) -1 else minOf(gpuLayers, ceiling),
        speculativeDraftGpuLayers = minOf(speculativeDraftGpuLayers, ceiling),
    )
}

private fun PerformanceRuntimeConfig.applyMmapStrategy(
    deviceState: DeviceState,
    modelSizeBytes: Long,
): PerformanceRuntimeConfig {
    val modelSizeMb = modelSizeBytes.toDouble() / (1024.0 * 1024.0)
    val shouldUseMmap = useMmap && (deviceState.ramClassGb > 4 || modelSizeMb > 2048.0)
    return copy(useMmap = shouldUseMmap)
}

private fun shouldUseMlock(deviceState: DeviceState, modelSizeBytes: Long): Boolean {
    val modelSizeMb = modelSizeBytes.toDouble() / (1024.0 * 1024.0)
    return deviceState.ramClassGb >= 12 && modelSizeMb in 1.0..2048.0
}

private fun resolveNKeep(taskType: String, nCtx: Int): Int {
    val baseline = if (taskType == "long_text") 256 else 128
    return baseline.coerceAtLeast(64).coerceAtMost((nCtx / 8).coerceAtLeast(64))
}

private fun PerformanceRuntimeConfig.gateSpeculative(
    modelId: String,
    deviceState: DeviceState,
    nativeInference: LlamaCppInferenceModule?,
): PerformanceRuntimeConfig {
    if (!speculativeEnabled) {
        return this
    }
    if (deviceState.ramClassGb < 8) {
        return copy(speculativeEnabled = false, speculativeDraftModelId = null, speculativeDraftGpuLayers = 0)
    }
    val draftModelId = speculativeDraftModelId?.trim().orEmpty()
    if (draftModelId.isEmpty()) {
        return copy(speculativeEnabled = false, speculativeDraftModelId = null, speculativeDraftGpuLayers = 0)
    }
    val draftPath = nativeInference?.registeredModelPath(draftModelId)
    val targetPath = nativeInference?.registeredModelPath(modelId)
    if (draftPath.isNullOrBlank() || draftPath == targetPath) {
        return copy(speculativeEnabled = false, speculativeDraftModelId = null, speculativeDraftGpuLayers = 0)
    }
    return this
}

private fun ModelResidencyPolicy.resolveKeepAliveMs(
    deviceState: DeviceState,
    modelSizeBytes: Long,
): Long {
    if (!keepLoadedWhileAppForeground) {
        return 1L
    }
    if (!adaptiveIdleTtl) {
        return idleUnloadTtlMs.coerceAtLeast(1L)
    }
    val deviceRamMb = deviceState.ramClassGb.coerceAtLeast(1) * 1024.0
    val modelSizeMb = modelSizeBytes.toDouble() / (1024.0 * 1024.0)
    val pressureRatio = if (modelSizeMb > 0.0) modelSizeMb / deviceRamMb else 0.0
    val baseTtl = when {
        pressureRatio > 0.5 -> 2 * 60_000L
        pressureRatio > 0.3 -> 5 * 60_000L
        else -> 15 * 60_000L
    }
    val thermalAdjusted = if (deviceState.thermalLevel >= 5) {
        baseTtl / 2L
    } else {
        baseTtl
    }
    val batteryAdjusted = when {
        deviceState.batteryPercent < 20 -> minOf(thermalAdjusted, 60_000L)
        deviceState.batteryPercent < 35 -> minOf(thermalAdjusted, 180_000L)
        else -> thermalAdjusted
    }
    return minOf(idleUnloadTtlMs, batteryAdjusted).coerceAtLeast(1L)
}

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
