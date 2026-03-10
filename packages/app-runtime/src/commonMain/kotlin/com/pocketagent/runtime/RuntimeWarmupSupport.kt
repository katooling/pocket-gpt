package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.RuntimeReloadReason

interface RuntimeWarmupSupport {
    fun warmupActiveModel(): WarmupResult
}

data class WarmupResult(
    val attempted: Boolean,
    val warmed: Boolean,
    val residentHit: Boolean,
    val loadDurationMs: Long? = null,
    val warmupDurationMs: Long? = null,
    val errorCode: String? = null,
) {
    companion object {
        fun skipped(reason: String): WarmupResult {
            return WarmupResult(
                attempted = false,
                warmed = false,
                residentHit = false,
                errorCode = reason,
            )
        }
    }
}

internal class RuntimeWarmupCoordinator(
    private val inferenceModule: InferenceModule,
    private val artifactVerifier: ArtifactVerifier,
    private val observabilityModule: ObservabilityModule,
    private val cancelIdleUnload: () -> Unit,
    private val scheduleIdleUnload: (Long) -> Unit,
    private val unloadNow: () -> Unit,
    private val availableCpuThreads: () -> Int = { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun warmup(residencyPolicy: ModelResidencyPolicy = ModelResidencyPolicy()): WarmupResult {
        if (!residencyPolicy.warmupOnStartup) {
            return WarmupResult.skipped("warmup_disabled")
        }
        val nativeInference = inferenceModule as? LlamaCppInferenceModule
            ?: return WarmupResult.skipped("warmup_requires_native_bridge")
        val modelId = artifactVerifier.manager().getActiveModel().trim()
        if (modelId.isEmpty()) {
            return WarmupResult.skipped("warmup_model_missing")
        }
        if (!artifactVerifier.manager().setActiveModel(modelId)) {
            return WarmupResult.skipped("warmup_model_unregistered")
        }
        val verification = artifactVerifier.verifyArtifactForModel(modelId)
        if (!verification.passed) {
            return WarmupResult.skipped("warmup_artifact_unverified")
        }
        if (!inferenceModule.listAvailableModels().contains(modelId)) {
            return WarmupResult.skipped("warmup_model_unavailable")
        }

        val performanceConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = availableCpuThreads(),
            gpuEnabled = nativeInference.supportsGpuOffload(),
        )
        nativeInference.setRuntimeGenerationConfig(performanceConfig.toRuntimeGenerationConfig())

        cancelIdleUnload()
        val loadStartedAtMs = nowMs()
        val loaded = inferenceModule.loadModel(modelId)
        val loadDurationMs = (nowMs() - loadStartedAtMs).coerceAtLeast(0L)
        if (!loaded) {
            unloadNow()
            return WarmupResult(
                attempted = true,
                warmed = false,
                residentHit = false,
                loadDurationMs = loadDurationMs,
                errorCode = nativeInference.lastBridgeError()?.code ?: "warmup_load_failed",
            )
        }

        val warmupStartedAtMs = nowMs()
        val warmupResult = runCatching {
            nativeInference.generateStreamWithCache(
                requestId = "warmup-$modelId-${warmupStartedAtMs}",
                request = InferenceRequest(prompt = WARMUP_PROMPT, maxTokens = 1),
                cacheKey = null,
                cachePolicy = CachePolicy.OFF,
                onToken = {},
            )
        }.getOrElse { error ->
            unloadNow()
            return WarmupResult(
                attempted = true,
                warmed = false,
                residentHit = false,
                loadDurationMs = loadDurationMs,
                errorCode = error.message ?: error::class.simpleName,
            )
        }
        val warmupDurationMs = (nowMs() - warmupStartedAtMs).coerceAtLeast(0L)
        if (!warmupResult.success) {
            unloadNow()
            return WarmupResult(
                attempted = true,
                warmed = false,
                residentHit = false,
                loadDurationMs = loadDurationMs,
                warmupDurationMs = warmupDurationMs,
                errorCode = warmupResult.errorCode ?: "warmup_generate_failed",
            )
        }

        nativeInference.recordWarmup(warmupDurationMs)
        val residencyState = nativeInference.residencyState()
        recordResidencyMetrics(
            observabilityModule = observabilityModule,
            residencyState = residencyState,
            loadDurationMs = loadDurationMs,
            thermalThrottled = false,
        )
        observabilityModule.recordLatencyMetric("inference.warmup_ms", warmupDurationMs.toDouble())
        if (residencyPolicy.keepLoadedWhileAppForeground) {
            cancelIdleUnload()
        } else {
            scheduleIdleUnload(residencyPolicy.idleUnloadTtlMs)
        }
        return WarmupResult(
            attempted = true,
            warmed = true,
            residentHit = residencyState.residentHit,
            loadDurationMs = loadDurationMs,
            warmupDurationMs = warmupDurationMs,
        )
    }

    private companion object {
        private const val WARMUP_PROMPT = "Warm up the active runtime."
    }
}

internal fun recordResidencyMetrics(
    observabilityModule: ObservabilityModule,
    residencyState: com.pocketagent.nativebridge.RuntimeResidencyState,
    loadDurationMs: Long?,
    thermalThrottled: Boolean,
) {
    loadDurationMs?.let { observabilityModule.recordLatencyMetric("inference.model_load_ms", it.toDouble()) }
    observabilityModule.recordLatencyMetric(
        "inference.resident_hit",
        if (residencyState.residentHit) 1.0 else 0.0,
    )
    observabilityModule.recordLatencyMetric(
        "inference.thermal_throttled",
        if (thermalThrottled) 1.0 else 0.0,
    )
    val reloadReason = residencyState.reloadReason ?: return
    observabilityModule.recordLatencyMetric(
        "inference.reload_reason.${reloadReason.metricName()}",
        1.0,
    )
}

private fun RuntimeReloadReason.metricName(): String {
    return name.lowercase()
}
