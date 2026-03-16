package com.pocketagent.android.runtime

import android.content.Context
import android.os.Build
import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry
import com.pocketagent.nativebridge.KvCacheType
import com.pocketagent.runtime.MemoryBudgetTracker
import com.pocketagent.runtime.PerformanceRuntimeConfig
import org.json.JSONArray
import org.json.JSONObject

interface RuntimeTuning {
    fun applyRecommendedConfig(
        modelIdHint: String?,
        baseConfig: PerformanceRuntimeConfig,
        gpuQualifiedLayers: Int,
    ): PerformanceRuntimeConfig = baseConfig

    fun recordSuccess(
        modelId: String?,
        appliedConfig: PerformanceRuntimeConfig,
        targetConfig: PerformanceRuntimeConfig,
        runtimeStats: RuntimeExecutionStats?,
        thermalThrottled: Boolean,
    ) = Unit

    fun recordFailure(
        modelId: String?,
        appliedConfig: PerformanceRuntimeConfig,
        targetConfig: PerformanceRuntimeConfig,
        errorCode: String?,
        thermalThrottled: Boolean,
    ) = Unit

    fun diagnosticsReport(): String = ""

    companion object {
        val DISABLED: RuntimeTuning = object : RuntimeTuning {}
    }
}

data class RuntimeTuningRecommendation(
    val gpuLayers: Int? = null,
    val kvCacheType: KvCacheType? = null,
    val speculativeEnabled: Boolean? = null,
    val speculativeDraftGpuLayers: Int? = null,
    val useMmap: Boolean? = null,
    val nBatch: Int? = null,
    val nUbatch: Int? = null,
    val targetGpuLayers: Int? = null,
    val targetSpeculativeEnabled: Boolean? = null,
    val targetSpeculativeDraftGpuLayers: Int? = null,
    val targetUseMmap: Boolean? = null,
    val targetNBatch: Int? = null,
    val targetNUbatch: Int? = null,
    val lastPeakRssMb: Double? = null,
    val lastFirstTokenMs: Long? = null,
    val lastTokensPerSec: Double? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val benchmarkWinCount: Int = 0,
    val promotionCount: Int = 0,
    val lastDecision: String = "none",
    val updatedAtEpochMs: Long = 0L,
)

data class RuntimeTuningObservation(
    val success: Boolean,
    val firstTokenMs: Long? = null,
    val tokensPerSec: Double? = null,
    val peakRssMb: Double? = null,
    val errorCode: String? = null,
    val thermalThrottled: Boolean = false,
)

data class RuntimeTuningSample(
    val timestampEpochMs: Long,
    val success: Boolean,
    val firstTokenMs: Long? = null,
    val tokensPerSec: Double? = null,
    val peakRssMb: Double? = null,
    val thermalThrottled: Boolean = false,
    val errorCode: String? = null,
    val appliedGpuLayers: Int,
    val appliedSpeculativeEnabled: Boolean,
    val appliedSpeculativeDraftGpuLayers: Int,
    val appliedUseMmap: Boolean,
    val appliedNBatch: Int,
    val appliedNUbatch: Int,
    val decision: String,
)

class RuntimeTuningDecider(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun nextRecommendation(
        current: RuntimeTuningRecommendation?,
        appliedConfig: PerformanceRuntimeConfig,
        targetConfig: PerformanceRuntimeConfig,
        observation: RuntimeTuningObservation,
    ): RuntimeTuningRecommendation {
        val previous = current ?: RuntimeTuningRecommendation(
            gpuLayers = appliedConfig.gpuLayers,
            kvCacheType = appliedConfig.kvCacheTypeV,
            speculativeEnabled = appliedConfig.speculativeEnabled,
            speculativeDraftGpuLayers = appliedConfig.speculativeDraftGpuLayers,
            useMmap = appliedConfig.useMmap,
            nBatch = appliedConfig.nBatch,
            nUbatch = appliedConfig.nUbatch,
        )
        var gpuLayers = previous.gpuLayers ?: appliedConfig.gpuLayers
        var kvCacheType = previous.kvCacheType ?: appliedConfig.kvCacheTypeV
        var speculativeEnabled = previous.speculativeEnabled ?: appliedConfig.speculativeEnabled
        var speculativeDraftGpuLayers = previous.speculativeDraftGpuLayers ?: appliedConfig.speculativeDraftGpuLayers
        var useMmap = previous.useMmap ?: appliedConfig.useMmap
        var nBatch = previous.nBatch ?: appliedConfig.nBatch
        var nUbatch = previous.nUbatch ?: appliedConfig.nUbatch
        var benchmarkWinCount = previous.benchmarkWinCount
        var promotionCount = previous.promotionCount
        var lastDecision = "hold"

        val normalizedError = observation.errorCode.orEmpty().trim().lowercase()
        val memoryPressure = observation.peakRssMb?.let { it >= HIGH_PEAK_RSS_MB } == true ||
            normalizedError.contains("memory") ||
            normalizedError.contains("alloc") ||
            normalizedError.contains("oom")
        val gpuRegression = appliedConfig.gpuEnabled && appliedConfig.gpuLayers > 0 && (
            normalizedError.contains("gpu") ||
                normalizedError.contains("opencl") ||
                normalizedError.contains("hexagon") ||
                normalizedError.contains("backend") ||
                normalizedError.contains("jni") ||
                normalizedError.contains("remote_process_died")
            )
        val speculativeRegression = speculativeEnabled && (
            normalizedError.contains("draft") ||
                normalizedError.contains("speculative") ||
                observation.thermalThrottled ||
                (
                    observation.success &&
                        observation.firstTokenMs != null &&
                        observation.firstTokenMs >= SLOW_FIRST_TOKEN_MS &&
                        (observation.tokensPerSec ?: 0.0) < SLOW_TOKENS_PER_SEC
                    )
            )
        val mmapRegression = useMmap && (
            normalizedError.contains("mmap") ||
                normalizedError.contains("readahead")
            )
        val benchmarkQualityWin = observation.success &&
            !observation.thermalThrottled &&
            (observation.peakRssMb == null || observation.peakRssMb <= PROMOTION_SAFE_PEAK_RSS_MB) &&
            (observation.firstTokenMs == null || observation.firstTokenMs <= PROMOTION_FIRST_TOKEN_MS) &&
            (observation.tokensPerSec == null || observation.tokensPerSec >= PROMOTION_TOKENS_PER_SEC)

        when {
            memoryPressure -> {
                kvCacheType = KvCacheType.Q8_0
                nBatch = demotedBatch(nBatch)
                nUbatch = demotedBatch(nUbatch)
                gpuLayers = demotedGpuLayers(gpuLayers)
                speculativeDraftGpuLayers = demotedDraftGpuLayers(speculativeDraftGpuLayers)
                benchmarkWinCount = 0
                lastDecision = "demote_memory_pressure"
            }

            gpuRegression -> {
                gpuLayers = demotedGpuLayers(gpuLayers)
                speculativeDraftGpuLayers = demotedDraftGpuLayers(speculativeDraftGpuLayers)
                benchmarkWinCount = 0
                lastDecision = "demote_gpu_regression"
            }

            speculativeRegression -> {
                speculativeEnabled = false
                speculativeDraftGpuLayers = 0
                benchmarkWinCount = 0
                lastDecision = "demote_speculative"
            }

            mmapRegression -> {
                useMmap = false
                benchmarkWinCount = 0
                lastDecision = "demote_use_mmap"
            }

            benchmarkQualityWin -> {
                benchmarkWinCount += 1
                if (benchmarkWinCount >= BENCHMARK_PROMOTION_THRESHOLD) {
                    when {
                        appliedConfig.gpuEnabled && gpuLayers < targetConfig.gpuLayers -> {
                            gpuLayers = promotedGpuLayers(current = gpuLayers, target = targetConfig.gpuLayers)
                            promotionCount += 1
                            benchmarkWinCount = 0
                            lastDecision = "promote_gpu_layers"
                        }

                        nBatch < targetConfig.nBatch || nUbatch < targetConfig.nUbatch -> {
                            nBatch = promotedBatch(current = nBatch, target = targetConfig.nBatch)
                            nUbatch = promotedBatch(current = nUbatch, target = targetConfig.nUbatch)
                            promotionCount += 1
                            benchmarkWinCount = 0
                            lastDecision = "promote_batch"
                        }

                        !speculativeEnabled && targetConfig.speculativeEnabled -> {
                            speculativeEnabled = true
                            promotionCount += 1
                            benchmarkWinCount = 0
                            lastDecision = "promote_speculative"
                        }

                        speculativeEnabled && speculativeDraftGpuLayers < targetConfig.speculativeDraftGpuLayers -> {
                            speculativeDraftGpuLayers = promotedDraftGpuLayers(
                                current = speculativeDraftGpuLayers,
                                target = targetConfig.speculativeDraftGpuLayers,
                            )
                            promotionCount += 1
                            benchmarkWinCount = 0
                            lastDecision = "promote_draft_gpu_layers"
                        }

                        !useMmap && targetConfig.useMmap -> {
                            useMmap = true
                            promotionCount += 1
                            benchmarkWinCount = 0
                            lastDecision = "promote_use_mmap"
                        }

                        else -> {
                            lastDecision = "benchmark_win_no_change"
                        }
                    }
                } else {
                    lastDecision = "benchmark_win_${benchmarkWinCount}"
                }
            }

            observation.success -> {
                benchmarkWinCount = 0
                lastDecision = "stable_success"
            }

            else -> {
                benchmarkWinCount = 0
                lastDecision = if (normalizedError.isBlank()) "failure" else "failure_${sanitizeDiagnosticValue(normalizedError)}"
            }
        }

        return previous.copy(
            gpuLayers = gpuLayers.coerceAtLeast(0),
            kvCacheType = kvCacheType,
            speculativeEnabled = speculativeEnabled,
            speculativeDraftGpuLayers = speculativeDraftGpuLayers.coerceAtLeast(0),
            useMmap = useMmap,
            nBatch = nBatch.coerceAtLeast(MIN_TUNED_BATCH),
            nUbatch = nUbatch.coerceAtLeast(MIN_TUNED_BATCH),
            targetGpuLayers = targetConfig.gpuLayers.coerceAtLeast(0),
            targetSpeculativeEnabled = targetConfig.speculativeEnabled,
            targetSpeculativeDraftGpuLayers = targetConfig.speculativeDraftGpuLayers.coerceAtLeast(0),
            targetUseMmap = targetConfig.useMmap,
            targetNBatch = targetConfig.nBatch,
            targetNUbatch = targetConfig.nUbatch,
            lastPeakRssMb = observation.peakRssMb ?: previous.lastPeakRssMb,
            lastFirstTokenMs = observation.firstTokenMs ?: previous.lastFirstTokenMs,
            lastTokensPerSec = observation.tokensPerSec ?: previous.lastTokensPerSec,
            successCount = previous.successCount + if (observation.success) 1 else 0,
            failureCount = previous.failureCount + if (observation.success) 0 else 1,
            benchmarkWinCount = benchmarkWinCount,
            promotionCount = promotionCount,
            lastDecision = lastDecision,
            updatedAtEpochMs = nowMs(),
        )
    }

    private fun demotedGpuLayers(current: Int): Int {
        return if (current <= 4) 0 else (current * 3 / 4).coerceAtLeast(0)
    }

    private fun promotedGpuLayers(current: Int, target: Int): Int {
        if (current <= 0) {
            return minOf(target, 4)
        }
        return minOf(target, current + maxOf(2, current / 4))
    }

    private fun demotedDraftGpuLayers(current: Int): Int {
        return if (current <= 1) 0 else (current * 3 / 4).coerceAtLeast(0)
    }

    private fun promotedDraftGpuLayers(current: Int, target: Int): Int {
        if (current <= 0) {
            return minOf(target, 1)
        }
        return minOf(target, current + 1)
    }

    private fun demotedBatch(current: Int): Int {
        return (current / 2).coerceAtLeast(MIN_TUNED_BATCH)
    }

    private fun promotedBatch(current: Int, target: Int): Int {
        return minOf(target, current + maxOf(128, current / 4))
    }

    private companion object {
        private const val HIGH_PEAK_RSS_MB = 2800.0
        private const val PROMOTION_SAFE_PEAK_RSS_MB = 2200.0
        private const val SLOW_FIRST_TOKEN_MS = 3500L
        private const val SLOW_TOKENS_PER_SEC = 8.0
        private const val PROMOTION_FIRST_TOKEN_MS = 2200L
        private const val PROMOTION_TOKENS_PER_SEC = 12.0
        private const val MIN_TUNED_BATCH = 128
        private const val BENCHMARK_PROMOTION_THRESHOLD = 3
    }
}

class AndroidRuntimeTuningStore(
    context: Context,
    private val deviceKey: String = defaultDeviceKey(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val provisioningStore: AndroidRuntimeProvisioningStore = AndroidRuntimeProvisioningStore(context.applicationContext),
    private val decider: RuntimeTuningDecider = RuntimeTuningDecider(nowMs),
    val memoryBudgetTracker: MemoryBudgetTracker = MemoryBudgetTracker(nowMs),
) : RuntimeTuning {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun applyRecommendedConfig(
        modelIdHint: String?,
        baseConfig: PerformanceRuntimeConfig,
        gpuQualifiedLayers: Int,
    ): PerformanceRuntimeConfig {
        val modelId = resolveModelId(modelIdHint) ?: return baseConfig
        val recommendation = readRecommendation(prefKeyFor(modelId, baseConfig)) ?: return baseConfig
        val speculativeEnabled = recommendation.speculativeEnabled ?: baseConfig.speculativeEnabled
        return baseConfig.copy(
            gpuLayers = if (baseConfig.gpuEnabled) {
                (recommendation.gpuLayers ?: baseConfig.gpuLayers)
                    .coerceAtLeast(0)
                    .coerceAtMost(gpuQualifiedLayers.coerceAtLeast(0))
            } else {
                0
            },
            kvCacheTypeK = safeKvCacheType(recommendation.kvCacheType ?: baseConfig.kvCacheTypeK),
            kvCacheTypeV = safeKvCacheType(recommendation.kvCacheType ?: baseConfig.kvCacheTypeV),
            speculativeEnabled = speculativeEnabled,
            speculativeDraftGpuLayers = if (baseConfig.gpuEnabled && speculativeEnabled) {
                (recommendation.speculativeDraftGpuLayers ?: baseConfig.speculativeDraftGpuLayers)
                    .coerceAtLeast(0)
                    .coerceAtMost(gpuQualifiedLayers.coerceAtLeast(0))
            } else {
                0
            },
            useMmap = recommendation.useMmap ?: baseConfig.useMmap,
            nBatch = recommendation.nBatch ?: baseConfig.nBatch,
            nUbatch = recommendation.nUbatch ?: baseConfig.nUbatch,
        )
    }

    override fun recordSuccess(
        modelId: String?,
        appliedConfig: PerformanceRuntimeConfig,
        targetConfig: PerformanceRuntimeConfig,
        runtimeStats: RuntimeExecutionStats?,
        thermalThrottled: Boolean,
    ) {
        val resolvedModelId = resolveModelId(modelId) ?: return
        val prefKey = prefKeyFor(resolvedModelId, targetConfig)
        val next = decider.nextRecommendation(
            current = readRecommendation(prefKey),
            appliedConfig = appliedConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = true,
                firstTokenMs = runtimeStats?.prefillMs,
                tokensPerSec = runtimeStats?.tokensPerSec,
                peakRssMb = runtimeStats?.peakRssMb,
                thermalThrottled = thermalThrottled,
            ),
        )
        writeRecommendation(prefKey, resolvedModelId, targetConfig, next)
        runtimeStats?.peakRssMb?.let { peakRss ->
            memoryBudgetTracker.recordSuccessfulLoad(resolvedModelId, peakRss)
        }
        appendSample(
            historyKey = historyKeyFor(resolvedModelId, targetConfig),
            sample = RuntimeTuningSample(
                timestampEpochMs = nowMs(),
                success = true,
                firstTokenMs = runtimeStats?.prefillMs,
                tokensPerSec = runtimeStats?.tokensPerSec,
                peakRssMb = runtimeStats?.peakRssMb,
                thermalThrottled = thermalThrottled,
                appliedGpuLayers = appliedConfig.gpuLayers,
                appliedSpeculativeEnabled = appliedConfig.speculativeEnabled,
                appliedSpeculativeDraftGpuLayers = appliedConfig.speculativeDraftGpuLayers,
                appliedUseMmap = appliedConfig.useMmap,
                appliedNBatch = appliedConfig.nBatch,
                appliedNUbatch = appliedConfig.nUbatch,
                decision = next.lastDecision,
            ),
        )
    }

    override fun recordFailure(
        modelId: String?,
        appliedConfig: PerformanceRuntimeConfig,
        targetConfig: PerformanceRuntimeConfig,
        errorCode: String?,
        thermalThrottled: Boolean,
    ) {
        val normalizedError = errorCode.orEmpty().trim().lowercase()
        if (normalizedError.isBlank() || normalizedError == "cancelled" || normalizedError == "timeout") {
            return
        }
        val resolvedModelId = resolveModelId(modelId) ?: return
        val prefKey = prefKeyFor(resolvedModelId, targetConfig)
        val next = decider.nextRecommendation(
            current = readRecommendation(prefKey),
            appliedConfig = appliedConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = false,
                errorCode = normalizedError,
                thermalThrottled = thermalThrottled,
            ),
        )
        writeRecommendation(prefKey, resolvedModelId, targetConfig, next)
        appendSample(
            historyKey = historyKeyFor(resolvedModelId, targetConfig),
            sample = RuntimeTuningSample(
                timestampEpochMs = nowMs(),
                success = false,
                thermalThrottled = thermalThrottled,
                errorCode = normalizedError,
                appliedGpuLayers = appliedConfig.gpuLayers,
                appliedSpeculativeEnabled = appliedConfig.speculativeEnabled,
                appliedSpeculativeDraftGpuLayers = appliedConfig.speculativeDraftGpuLayers,
                appliedUseMmap = appliedConfig.useMmap,
                appliedNBatch = appliedConfig.nBatch,
                appliedNUbatch = appliedConfig.nUbatch,
                decision = next.lastDecision,
            ),
        )
    }

    override fun diagnosticsReport(): String {
        val recommendationKeys = prefs.all.keys
            .filter { it.startsWith(RECOMMENDATION_KEY_PREFIX) }
            .sorted()
        if (recommendationKeys.isEmpty()) {
            return "RUNTIME_TUNING|device_key=${sanitizeDiagnosticValue(deviceKey)}|entries=0\n${memoryBudgetTracker.diagnosticsLine()}"
        }
        return buildString {
            recommendationKeys.forEachIndexed { index, prefKey ->
                val payload = prefs.getString(prefKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEachIndexed
                if (index > 0) {
                    appendLine()
                }
                append(recommendationDiagnosticsLine(payload))
                val historyPayload = prefs.getString(historyKeyForPayload(payload), null)
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
                    ?: JSONArray()
                val startIndex = maxOf(0, historyPayload.length() - HISTORY_EXPORT_LIMIT)
                for (sampleIndex in startIndex until historyPayload.length()) {
                    val sample = historyPayload.optJSONObject(sampleIndex) ?: continue
                    appendLine()
                    append(sampleDiagnosticsLine(payload = payload, sample = sample, exportIndex = sampleIndex - startIndex))
                }
            }
            appendLine()
            append(memoryBudgetTracker.diagnosticsLine())
        }
    }

    private fun resolveModelId(modelIdHint: String?): String? {
        val hinted = modelIdHint?.trim()?.takeIf { it.isNotEmpty() }
        if (hinted != null) {
            return hinted
        }
        val snapshot = provisioningStore.snapshot()
        val defaultModelId = ModelRegistry.default().defaultGetReadyModelId(profile = ModelRuntimeProfile.PROD)
        val preferred = snapshot.models.firstOrNull { model ->
            model.modelId == defaultModelId && model.isProvisioned && !model.activeVersion.isNullOrBlank()
        }
        return preferred?.modelId ?: snapshot.models.firstOrNull { model ->
            model.isProvisioned && !model.activeVersion.isNullOrBlank()
        }?.modelId
    }

    private fun recommendationDiagnosticsLine(payload: JSONObject): String {
        return buildString {
            append("RUNTIME_TUNING")
            append("|device_key=${sanitizeDiagnosticValue(payload.optString("deviceKey", deviceKey))}")
            append("|model=${sanitizeDiagnosticValue(payload.optString("modelId"))}")
            append("|profile=${sanitizeDiagnosticValue(payload.optString("profile"))}")
            append("|mode=${sanitizeDiagnosticValue(payload.optString("mode"))}")
            append("|recommended_gpu_layers=${payload.optIntOrMinusOne("gpuLayers")}")
            append("|target_gpu_layers=${payload.optIntOrMinusOne("targetGpuLayers")}")
            append("|recommended_speculative=${payload.optStringOr("speculativeEnabled", "unknown")}")
            append("|target_speculative=${payload.optStringOr("targetSpeculativeEnabled", "unknown")}")
            append("|recommended_draft_gpu_layers=${payload.optIntOrMinusOne("speculativeDraftGpuLayers")}")
            append("|target_draft_gpu_layers=${payload.optIntOrMinusOne("targetSpeculativeDraftGpuLayers")}")
            append("|recommended_use_mmap=${payload.optStringOr("useMmap", "unknown")}")
            append("|target_use_mmap=${payload.optStringOr("targetUseMmap", "unknown")}")
            append("|recommended_n_batch=${payload.optIntOrMinusOne("nBatch")}")
            append("|target_n_batch=${payload.optIntOrMinusOne("targetNBatch")}")
            append("|recommended_n_ubatch=${payload.optIntOrMinusOne("nUbatch")}")
            append("|target_n_ubatch=${payload.optIntOrMinusOne("targetNUbatch")}")
            append("|benchmark_win_count=${payload.optInt("benchmarkWinCount", 0)}")
            append("|promotion_count=${payload.optInt("promotionCount", 0)}")
            append("|last_decision=${sanitizeDiagnosticValue(payload.optString("lastDecision", "none"))}")
            append("|success_count=${payload.optInt("successCount", 0)}")
            append("|failure_count=${payload.optInt("failureCount", 0)}")
            append("|last_first_token_ms=${payload.optLongOrMinusOne("lastFirstTokenMs")}")
            append("|last_tokens_per_sec=${payload.optDoubleOrMinusOne("lastTokensPerSec")}")
            append("|last_peak_rss_mb=${payload.optDoubleOrMinusOne("lastPeakRssMb")}")
            append("|updated_at_epoch_ms=${payload.optLong("updatedAtEpochMs", 0L)}")
        }
    }

    private fun sampleDiagnosticsLine(payload: JSONObject, sample: JSONObject, exportIndex: Int): String {
        return buildString {
            append("RUNTIME_TUNING_SAMPLE")
            append("|model=${sanitizeDiagnosticValue(payload.optString("modelId"))}")
            append("|profile=${sanitizeDiagnosticValue(payload.optString("profile"))}")
            append("|mode=${sanitizeDiagnosticValue(payload.optString("mode"))}")
            append("|sample_index=$exportIndex")
            append("|success=${sample.optBoolean("success", false)}")
            append("|decision=${sanitizeDiagnosticValue(sample.optString("decision", "unknown"))}")
            append("|first_token_ms=${sample.optLongOrMinusOne("firstTokenMs")}")
            append("|tokens_per_sec=${sample.optDoubleOrMinusOne("tokensPerSec")}")
            append("|peak_rss_mb=${sample.optDoubleOrMinusOne("peakRssMb")}")
            append("|thermal_throttled=${sample.optBoolean("thermalThrottled", false)}")
            append("|error_code=${sanitizeDiagnosticValue(sample.optString("errorCode", "none"))}")
            append("|applied_gpu_layers=${sample.optInt("appliedGpuLayers", 0)}")
            append("|applied_speculative=${sample.optBoolean("appliedSpeculativeEnabled", false)}")
            append("|applied_draft_gpu_layers=${sample.optInt("appliedSpeculativeDraftGpuLayers", 0)}")
            append("|applied_use_mmap=${sample.optBoolean("appliedUseMmap", true)}")
            append("|applied_n_batch=${sample.optInt("appliedNBatch", -1)}")
            append("|applied_n_ubatch=${sample.optInt("appliedNUbatch", -1)}")
            append("|timestamp_epoch_ms=${sample.optLong("timestampEpochMs", 0L)}")
        }
    }

    private fun historyKeyForPayload(payload: JSONObject): String {
        val modelId = payload.optString("modelId")
        val profile = payload.optString("profile")
        val mode = payload.optString("mode")
        return "$HISTORY_KEY_PREFIX${sanitizeKey(deviceKey)}__${profile}__${mode}__${sanitizeKey(modelId)}"
    }

    private fun prefKeyFor(modelId: String, targetConfig: PerformanceRuntimeConfig): String {
        val mode = if (targetConfig.gpuEnabled) "gpu" else "cpu"
        return "$RECOMMENDATION_KEY_PREFIX${sanitizeKey(deviceKey)}__${targetConfig.profile.name}__${mode}__${sanitizeKey(modelId)}"
    }

    private fun historyKeyFor(modelId: String, targetConfig: PerformanceRuntimeConfig): String {
        val mode = if (targetConfig.gpuEnabled) "gpu" else "cpu"
        return "$HISTORY_KEY_PREFIX${sanitizeKey(deviceKey)}__${targetConfig.profile.name}__${mode}__${sanitizeKey(modelId)}"
    }

    private fun readRecommendation(prefKey: String): RuntimeTuningRecommendation? {
        val raw = prefs.getString(prefKey, null) ?: return null
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return RuntimeTuningRecommendation(
            gpuLayers = payload.takeIf { it.has("gpuLayers") }?.getInt("gpuLayers"),
            kvCacheType = when {
                payload.has("kvCacheTypeCode") -> KvCacheType.fromCode(payload.getInt("kvCacheTypeCode"))
                payload.has("quantizedKvCache") -> KvCacheType.fromBoolean(payload.getBoolean("quantizedKvCache"))
                else -> null
            },
            speculativeEnabled = payload.takeIf { it.has("speculativeEnabled") }?.getBoolean("speculativeEnabled"),
            speculativeDraftGpuLayers = payload.takeIf { it.has("speculativeDraftGpuLayers") }?.getInt("speculativeDraftGpuLayers"),
            useMmap = payload.takeIf { it.has("useMmap") }?.getBoolean("useMmap"),
            nBatch = payload.takeIf { it.has("nBatch") }?.getInt("nBatch"),
            nUbatch = payload.takeIf { it.has("nUbatch") }?.getInt("nUbatch"),
            targetGpuLayers = payload.takeIf { it.has("targetGpuLayers") }?.getInt("targetGpuLayers"),
            targetSpeculativeEnabled = payload.takeIf { it.has("targetSpeculativeEnabled") }?.getBoolean("targetSpeculativeEnabled"),
            targetSpeculativeDraftGpuLayers = payload.takeIf { it.has("targetSpeculativeDraftGpuLayers") }?.getInt("targetSpeculativeDraftGpuLayers"),
            targetUseMmap = payload.takeIf { it.has("targetUseMmap") }?.getBoolean("targetUseMmap"),
            targetNBatch = payload.takeIf { it.has("targetNBatch") }?.getInt("targetNBatch"),
            targetNUbatch = payload.takeIf { it.has("targetNUbatch") }?.getInt("targetNUbatch"),
            lastPeakRssMb = payload.takeIf { it.has("lastPeakRssMb") }?.getDouble("lastPeakRssMb"),
            lastFirstTokenMs = payload.takeIf { it.has("lastFirstTokenMs") }?.getLong("lastFirstTokenMs"),
            lastTokensPerSec = payload.takeIf { it.has("lastTokensPerSec") }?.getDouble("lastTokensPerSec"),
            successCount = payload.optInt("successCount", 0),
            failureCount = payload.optInt("failureCount", 0),
            benchmarkWinCount = payload.optInt("benchmarkWinCount", 0),
            promotionCount = payload.optInt("promotionCount", 0),
            lastDecision = payload.optString("lastDecision", "none"),
            updatedAtEpochMs = payload.optLong("updatedAtEpochMs", 0L),
        )
    }

    private fun writeRecommendation(
        prefKey: String,
        modelId: String,
        targetConfig: PerformanceRuntimeConfig,
        recommendation: RuntimeTuningRecommendation,
    ) {
        val payload = JSONObject().apply {
            put("deviceKey", deviceKey)
            put("modelId", modelId)
            put("profile", targetConfig.profile.name)
            put("mode", if (targetConfig.gpuEnabled) "gpu" else "cpu")
            recommendation.gpuLayers?.let { put("gpuLayers", it) }
            recommendation.kvCacheType?.let { put("kvCacheTypeCode", it.code) }
            recommendation.speculativeEnabled?.let { put("speculativeEnabled", it) }
            recommendation.speculativeDraftGpuLayers?.let { put("speculativeDraftGpuLayers", it) }
            recommendation.useMmap?.let { put("useMmap", it) }
            recommendation.nBatch?.let { put("nBatch", it) }
            recommendation.nUbatch?.let { put("nUbatch", it) }
            recommendation.targetGpuLayers?.let { put("targetGpuLayers", it) }
            recommendation.targetSpeculativeEnabled?.let { put("targetSpeculativeEnabled", it) }
            recommendation.targetSpeculativeDraftGpuLayers?.let { put("targetSpeculativeDraftGpuLayers", it) }
            recommendation.targetUseMmap?.let { put("targetUseMmap", it) }
            recommendation.targetNBatch?.let { put("targetNBatch", it) }
            recommendation.targetNUbatch?.let { put("targetNUbatch", it) }
            recommendation.lastPeakRssMb?.let { put("lastPeakRssMb", it) }
            recommendation.lastFirstTokenMs?.let { put("lastFirstTokenMs", it) }
            recommendation.lastTokensPerSec?.let { put("lastTokensPerSec", it) }
            put("successCount", recommendation.successCount)
            put("failureCount", recommendation.failureCount)
            put("benchmarkWinCount", recommendation.benchmarkWinCount)
            put("promotionCount", recommendation.promotionCount)
            put("lastDecision", recommendation.lastDecision)
            put("updatedAtEpochMs", recommendation.updatedAtEpochMs.takeIf { it > 0L } ?: nowMs())
        }
        prefs.edit().putString(prefKey, payload.toString()).apply()
    }

    private fun appendSample(historyKey: String, sample: RuntimeTuningSample) {
        val existing = prefs.getString(historyKey, null)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: JSONArray()
        val trimmed = JSONArray()
        val start = maxOf(0, existing.length() - (MAX_HISTORY_SAMPLES - 1))
        for (index in start until existing.length()) {
            trimmed.put(existing.get(index))
        }
        trimmed.put(
            JSONObject().apply {
                put("timestampEpochMs", sample.timestampEpochMs)
                put("success", sample.success)
                sample.firstTokenMs?.let { put("firstTokenMs", it) }
                sample.tokensPerSec?.let { put("tokensPerSec", it) }
                sample.peakRssMb?.let { put("peakRssMb", it) }
                put("thermalThrottled", sample.thermalThrottled)
                sample.errorCode?.let { put("errorCode", it) }
                put("appliedGpuLayers", sample.appliedGpuLayers)
                put("appliedSpeculativeEnabled", sample.appliedSpeculativeEnabled)
                put("appliedNBatch", sample.appliedNBatch)
                put("appliedNUbatch", sample.appliedNUbatch)
                put("decision", sample.decision)
            },
        )
        prefs.edit().putString(historyKey, trimmed.toString()).apply()
    }

    private companion object {
        private const val PREFS_NAME = "pocketagent_runtime_tuning"
        private const val RECOMMENDATION_KEY_PREFIX = "runtime_tuning_rec__"
        private const val HISTORY_KEY_PREFIX = "runtime_tuning_hist__"
        private const val MAX_HISTORY_SAMPLES = 8
        private const val HISTORY_EXPORT_LIMIT = 3

        private fun defaultDeviceKey(): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown_manufacturer" }
            val model = Build.MODEL.orEmpty().ifBlank { "unknown_model" }
            return "$manufacturer|$model|sdk_${Build.VERSION.SDK_INT}"
        }
    }
}

private fun sanitizeDiagnosticValue(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "none" }
}

private fun sanitizeKey(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }
}

private fun JSONObject.optIntOrMinusOne(key: String): Int {
    return if (has(key)) getInt(key) else -1
}

private fun JSONObject.optLongOrMinusOne(key: String): Long {
    return if (has(key)) getLong(key) else -1L
}

private fun JSONObject.optDoubleOrMinusOne(key: String): String {
    return if (has(key)) "%.2f".format(getDouble(key)) else "-1"
}

private fun JSONObject.optStringOr(key: String, fallback: String): String {
    return if (has(key)) sanitizeDiagnosticValue(optString(key, fallback)) else fallback
}

/** Q4_0 KV cache causes garbled output on small models — clamp to at least Q8_0. */
private fun safeKvCacheType(type: KvCacheType): KvCacheType {
    return if (type.code > KvCacheType.Q8_0.code) KvCacheType.Q8_0 else type
}
