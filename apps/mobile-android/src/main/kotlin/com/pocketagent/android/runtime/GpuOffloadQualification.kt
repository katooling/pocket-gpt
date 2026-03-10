package com.pocketagent.android.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.pocketagent.android.BuildConfig
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class GpuProbeStatus {
    PENDING,
    QUALIFIED,
    FAILED,
}

enum class GpuProbeFailureReason {
    RUNTIME_UNSUPPORTED,
    MODEL_UNAVAILABLE,
    PROBE_PROCESS_DIED,
    PROBE_TIMEOUT,
    PROBE_BIND_FAILED,
    NATIVE_LOAD_FAILED,
    NATIVE_GENERATE_FAILED,
    NATIVE_RUNTIME_UNAVAILABLE,
    UNKNOWN,
}

data class GpuProbeResult(
    val status: GpuProbeStatus,
    val maxStableGpuLayers: Int = 0,
    val failureReason: GpuProbeFailureReason? = null,
    val detail: String? = null,
    val cacheKey: String? = null,
    val checkedAtEpochMs: Long = System.currentTimeMillis(),
)

data class GpuProbeRequest(
    val modelId: String,
    val modelVersion: String,
    val modelPath: String,
    val layerLadder: List<Int>,
    val modelContentFingerprint: String? = null,
    val modelFileSizeBytes: Long = 0L,
)

interface GpuProbeClient {
    suspend fun runProbe(request: GpuProbeRequest, timeoutMs: Long): GpuProbeResult
}

internal interface GpuProbeResultStore {
    fun get(cacheKey: String): String?
    fun put(cacheKey: String, value: String)
}

interface GpuOffloadQualifier {
    fun evaluate(runtimeSupported: Boolean): GpuProbeResult
    fun diagnosticsLine(): String
    fun reportRuntimeFailure(
        reason: GpuProbeFailureReason,
        detail: String? = null,
    ) = Unit

    companion object {
        val DISABLED: GpuOffloadQualifier = object : GpuOffloadQualifier {
            override fun evaluate(runtimeSupported: Boolean): GpuProbeResult {
                return if (runtimeSupported) {
                    GpuProbeResult(
                        status = GpuProbeStatus.QUALIFIED,
                        maxStableGpuLayers = 32,
                        detail = "gpu_qualifier_passthrough",
                    )
                } else {
                    GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                        detail = "runtime_reports_unsupported",
                    )
                }
            }

            override fun diagnosticsLine(): String = "GPU_PROBE|status=disabled_passthrough"
        }
    }
}

private const val PREFS_NAME = "pocketagent_gpu_probe_cache"
private const val RESULT_PREFIX = "gpu_probe_result_"
private const val KEY_LAST_WRITE_EPOCH_MS = "gpu_probe_last_write_ms"
private const val PROBE_POLICY_VERSION = 2
private const val PROBE_TOTAL_TIMEOUT_MS = 2 * 60_000L
private const val PROBE_MIN_LAYER_TIMEOUT_MS = 12_000L
private const val PROBE_MAX_LAYER_TIMEOUT_MS = 45_000L
private const val PROBE_STALE_PENDING_TIMEOUT_MS = PROBE_TOTAL_TIMEOUT_MS + 30_000L
private const val VULKAN_API_1_2 = (1L shl 22) or (2L shl 12)
private const val MIB = 1024L * 1024L
private const val MIN_GPU_HEADROOM_BYTES = 256L * MIB
private val PROBE_LAYER_LADDER_FULL: List<Int> = listOf(1, 2, 4, 8, 16, 32)
private val PROBE_LAYER_LADDER_NO_HALF: List<Int> = listOf(1, 2, 4, 8)
private val PROBE_LAYER_LADDER_PRE_12: List<Int> = listOf(1, 2, 4, 8, 16)

private data class GpuProbePolicy(
    val layerLadder: List<Int>,
    val totalTimeoutMs: Long,
) {
    fun timeoutForLayer(layer: Int, nativeDiagnostics: NativeVulkanDiagnostics): Long {
        val layerTimeout = when {
            layer <= 2 -> 20_000L
            layer <= 4 -> 25_000L
            layer <= 8 -> 30_000L
            layer <= 16 -> 38_000L
            else -> 45_000L
        }
        val halfPrecisionSlowdownPenalty = if (
            !nativeDiagnostics.storageBuffer16BitAccess || !nativeDiagnostics.shaderFloat16
        ) {
            8_000L
        } else {
            0L
        }
        val apiVersionPenalty = if (
            nativeDiagnostics.selectedDeviceApiVersion in 1L until VULKAN_API_1_2
        ) {
            8_000L
        } else {
            0L
        }
        return (layerTimeout + halfPrecisionSlowdownPenalty + apiVersionPenalty)
            .coerceIn(PROBE_MIN_LAYER_TIMEOUT_MS, PROBE_MAX_LAYER_TIMEOUT_MS)
    }
}

private fun resolveProbePolicy(nativeDiagnostics: NativeVulkanDiagnostics): GpuProbePolicy {
    val hasHalfPrecision = nativeDiagnostics.storageBuffer16BitAccess && nativeDiagnostics.shaderFloat16
    val apiAtLeast12 = nativeDiagnostics.selectedDeviceApiVersion >= VULKAN_API_1_2
    val ladder = when {
        !hasHalfPrecision -> PROBE_LAYER_LADDER_NO_HALF
        !apiAtLeast12 -> PROBE_LAYER_LADDER_PRE_12
        else -> PROBE_LAYER_LADDER_FULL
    }
    return GpuProbePolicy(
        layerLadder = ladder,
        totalTimeoutMs = PROBE_TOTAL_TIMEOUT_MS,
    )
}

private fun resolveProbeRequestFromStore(store: AndroidRuntimeProvisioningStore): GpuProbeRequest? {
    val snapshot = store.snapshot()
    val defaultModelId = ModelRegistry.default().defaultGetReadyModelId(profile = ModelRuntimeProfile.PROD)
    val orderedModels = buildList {
        val defaultState = snapshot.models.firstOrNull { state -> state.modelId == defaultModelId }
        if (defaultState != null) {
            add(defaultState)
        }
        addAll(snapshot.models.filter { state -> state.modelId != defaultModelId })
    }

    val candidate = orderedModels.firstOrNull { state ->
        !state.absolutePath.isNullOrBlank() && !state.activeVersion.isNullOrBlank() && state.isProvisioned
    } ?: return null
    val activeVersion = candidate.activeVersion.orEmpty()
    val activeDescriptor = candidate.installedVersions.firstOrNull { version ->
        version.version == activeVersion && version.absolutePath.isNotBlank()
    }
    val modelPath = activeDescriptor?.absolutePath ?: candidate.absolutePath.orEmpty()
    val modelFingerprint = activeDescriptor?.sha256 ?: candidate.sha256
    val modelFileSizeBytes = activeDescriptor?.fileSizeBytes?.coerceAtLeast(0L) ?: 0L

    return GpuProbeRequest(
        modelId = candidate.modelId,
        modelVersion = activeVersion,
        modelPath = modelPath,
        layerLadder = PROBE_LAYER_LADDER_FULL,
        modelContentFingerprint = modelFingerprint,
        modelFileSizeBytes = modelFileSizeBytes,
    )
}

class AndroidGpuOffloadQualifier(
    context: Context,
    probeClient: GpuProbeClient = AndroidGpuProbeClient(context.applicationContext),
    probeRequestResolver: () -> GpuProbeRequest? = {
        resolveProbeRequestFromStore(AndroidRuntimeProvisioningStore(context.applicationContext))
    },
    nativeDiagnosticsReader: NativeVulkanDiagnosticsReader = NativeVulkanDiagnosticsReader(
        payloadProvider = {
            createDefaultAndroidRuntimeBridge(context.applicationContext).vulkanDiagnosticsJson()
        },
    ),
    now: () -> Long = { System.currentTimeMillis() },
) : GpuOffloadQualifier {
    private val appContext = context.applicationContext
    private val appBuildSignature: String = runCatching {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${packageInfo.longVersionCode}:${packageInfo.lastUpdateTime}"
    }.getOrDefault("${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}")

    private val delegate = InternalAndroidGpuOffloadQualifier(
        probeClient = probeClient,
        probeRequestResolver = probeRequestResolver,
        nativeDiagnosticsReader = nativeDiagnosticsReader,
        now = now,
        appBuildSignature = appBuildSignature,
        deviceFingerprint = Build.FINGERPRINT,
        resultStore = SharedPrefsGpuProbeResultStore(
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

    override fun evaluate(runtimeSupported: Boolean): GpuProbeResult = delegate.evaluate(runtimeSupported)

    override fun diagnosticsLine(): String = delegate.diagnosticsLine()

    override fun reportRuntimeFailure(reason: GpuProbeFailureReason, detail: String?) {
        delegate.reportRuntimeFailure(reason = reason, detail = detail)
    }
}

internal class InternalAndroidGpuOffloadQualifier(
    private val probeClient: GpuProbeClient,
    private val probeRequestResolver: () -> GpuProbeRequest?,
    private val nativeDiagnosticsReader: NativeVulkanDiagnosticsReader,
    private val now: () -> Long,
    private val appBuildSignature: String,
    private val deviceFingerprint: String,
    private val resultStore: GpuProbeResultStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GpuOffloadQualifier {
    private val tag = "GpuOffloadQualifier"

    @Volatile
    private var latestResult: GpuProbeResult = GpuProbeResult(
        status = GpuProbeStatus.PENDING,
        detail = "qualification_not_started",
    )

    @Volatile
    private var inFlightCacheKey: String? = null
    @Volatile
    private var latestNativeDiagnosticsPayload: String = ""

    override fun evaluate(runtimeSupported: Boolean): GpuProbeResult {
        val request = probeRequestResolver()
        if (!runtimeSupported) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
                detail = "native_runtime_support=false",
                checkedAtEpochMs = now(),
            ).also { latestResult = it }
        }
        if (request == null) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.MODEL_UNAVAILABLE,
                detail = "download_model_to_validate_gpu",
                checkedAtEpochMs = now(),
            ).also { latestResult = it }
        }

        val nativeDiag = nativeDiagnosticsReader.read()
        latestNativeDiagnosticsPayload = nativeDiag.rawPayload
        val cacheKey = computeCacheKey(request = request, nativeDiagnostics = nativeDiag)
        readCachedResult(cacheKey = cacheKey)?.let { cached ->
            latestResult = cached
            return cached
        }
        if (latestResult.cacheKey == cacheKey && latestResult.status != GpuProbeStatus.PENDING) {
            return latestResult
        }

        if (inFlightCacheKey == cacheKey) {
            val pendingAgeMs = (now() - latestResult.checkedAtEpochMs).coerceAtLeast(0L)
            if (pendingAgeMs > PROBE_STALE_PENDING_TIMEOUT_MS) {
                safeLogWarning(
                    "GPU_PROBE|stale_pending_detected|age_ms=$pendingAgeMs|cache_key=$cacheKey",
                )
                inFlightCacheKey = null
            } else {
                return latestResult.copy(status = GpuProbeStatus.PENDING, cacheKey = cacheKey)
            }
        }

        inFlightCacheKey = cacheKey
        val pending = GpuProbeResult(
            status = GpuProbeStatus.PENDING,
            detail = "probe_in_progress",
            cacheKey = cacheKey,
            checkedAtEpochMs = now(),
        )
        latestResult = pending
        scope.launch {
            try {
                val result = runCatching {
                    runLayerQualification(
                        request = request,
                        nativeDiagnostics = nativeDiag,
                    )
                }.getOrElse { error ->
                    GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.UNKNOWN,
                        detail = "probe_exception:${error.message ?: error::class.simpleName}",
                    )
                }.copy(cacheKey = cacheKey, checkedAtEpochMs = now())

                runCatching { writeCachedResult(cacheKey = cacheKey, result = result) }
                    .onFailure { cacheError ->
                        safeLogWarning(
                            "GPU_PROBE|cache_write_failed|cache_key=$cacheKey|detail=${cacheError.message ?: cacheError::class.simpleName}",
                        )
                    }
                latestResult = result
                safeLogInfo(
                    "GPU_PROBE|status=${result.status}|max_layers=${result.maxStableGpuLayers}|reason=${result.failureReason}|" +
                        "detail=${result.detail.orEmpty()}|cache_key=$cacheKey",
                )
            } finally {
                inFlightCacheKey = null
            }
        }
        return pending
    }

    override fun reportRuntimeFailure(
        reason: GpuProbeFailureReason,
        detail: String?,
    ) {
        val request = probeRequestResolver()
        val nativeDiag = nativeDiagnosticsReader.read()
        latestNativeDiagnosticsPayload = nativeDiag.rawPayload
        val cacheKey = when {
            request != null -> computeCacheKey(request = request, nativeDiagnostics = nativeDiag)
            !latestResult.cacheKey.isNullOrBlank() -> latestResult.cacheKey.orEmpty()
            else -> {
                safeLogWarning("GPU_PROBE|runtime_failure_demote_skipped|reason=$reason|detail=${detail.orEmpty()}")
                return
            }
        }
        val demoted = GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            maxStableGpuLayers = 0,
            failureReason = reason,
            detail = "runtime_failure_demoted:${detail.orEmpty()}",
            cacheKey = cacheKey,
            checkedAtEpochMs = now(),
        )
        latestResult = demoted
        inFlightCacheKey = null
        runCatching { writeCachedResult(cacheKey = cacheKey, result = demoted) }
            .onFailure { cacheError ->
                safeLogWarning(
                    "GPU_PROBE|demote_cache_write_failed|cache_key=$cacheKey|detail=${cacheError.message ?: cacheError::class.simpleName}",
                )
            }
        safeLogInfo(
            "GPU_PROBE|demoted=true|reason=$reason|detail=${detail.orEmpty()}|cache_key=$cacheKey",
        )
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(tag, message) }
    }

    private fun safeLogWarning(message: String) {
        runCatching { Log.w(tag, message) }
    }

    private fun safeLogWarning(message: String, throwable: Throwable) {
        runCatching { Log.w(tag, message, throwable) }
    }

    private suspend fun runLayerQualification(
        request: GpuProbeRequest,
        nativeDiagnostics: NativeVulkanDiagnostics,
    ): GpuProbeResult {
        val policy = resolveProbePolicy(nativeDiagnostics)
        val requestLadder = request.layerLadder.filter { it > 0 }.distinct().sorted()
        val orderedLayers = requestLadder.filter { candidate -> policy.layerLadder.contains(candidate) }
            .ifEmpty { policy.layerLadder }
        val estimatedLayerCap = estimateLayerCapForProbe(
            request = request,
            nativeDiagnostics = nativeDiagnostics,
            maxCandidateLayer = orderedLayers.maxOrNull() ?: 0,
        )
        val cappedOrderedLayers = applyProbeLayerCap(
            layers = orderedLayers,
            maxLayers = estimatedLayerCap,
        )
        val layerCapDetail = estimatedLayerCap?.toString() ?: "none"
        if (cappedOrderedLayers.isEmpty()) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.UNKNOWN,
                detail = "probe_layer_ladder_empty:layer_cap=$layerCapDetail",
            )
        }

        var maxStableLayers = 0
        var failedLayer: Int? = null
        var failure: GpuProbeResult? = null

        val startedAtMs = now()
        for (layer in cappedOrderedLayers) {
            val elapsedMs = (now() - startedAtMs).coerceAtLeast(0L)
            val remainingBudgetMs = (policy.totalTimeoutMs - elapsedMs).coerceAtLeast(0L)
            if (remainingBudgetMs < PROBE_MIN_LAYER_TIMEOUT_MS) {
                failure = GpuProbeResult(
                    status = GpuProbeStatus.FAILED,
                    failureReason = GpuProbeFailureReason.PROBE_TIMEOUT,
                    detail = "probe_total_timeout_exhausted:elapsed_ms=$elapsedMs:layer_cap=$layerCapDetail",
                )
                failedLayer = layer
                break
            }
            val timeoutMs = minOf(
                policy.timeoutForLayer(layer = layer, nativeDiagnostics = nativeDiagnostics),
                remainingBudgetMs,
            )
            val layerResult = probeClient.runProbe(
                request = request.copy(layerLadder = listOf(layer)),
                timeoutMs = timeoutMs,
            )
            if (layerResult.status == GpuProbeStatus.QUALIFIED && layerResult.maxStableGpuLayers > 0) {
                val stableForLayer = layerResult.maxStableGpuLayers.coerceAtMost(layer)
                maxStableLayers = max(maxStableLayers, stableForLayer)
                if (stableForLayer >= layer) {
                    continue
                }
            }
            failedLayer = layer
            failure = layerResult
            break
        }

        if (maxStableLayers > 0) {
            val detail = if (failedLayer == null) {
                "probe_success:layer_cap=$layerCapDetail"
            } else {
                "probe_partial_success:max_stable=$maxStableLayers:last_failed_layer=$failedLayer:" +
                    "reason=${failure?.failureReason ?: "unknown"}:layer_cap=$layerCapDetail"
            }
            return GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = maxStableLayers,
                detail = detail,
            )
        }

        return failure?.copy(
            status = GpuProbeStatus.FAILED,
            maxStableGpuLayers = 0,
            detail = failure.detail ?: "probe_failed_at_layer=${failedLayer ?: "unknown"}:layer_cap=$layerCapDetail",
        ) ?: GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.UNKNOWN,
            detail = "probe_failed_without_result:layer_cap=$layerCapDetail",
        )
    }

    private fun estimateLayerCapForProbe(
        request: GpuProbeRequest,
        nativeDiagnostics: NativeVulkanDiagnostics,
        maxCandidateLayer: Int,
    ): Int? {
        val modelBytes = request.modelFileSizeBytes.takeIf { it > 0L } ?: return null
        val heapBytes = nativeDiagnostics.deviceLocalHeapBytes.takeIf { it > 0L } ?: return null
        val maxLayer = maxCandidateLayer.coerceAtLeast(1)
        val reservedBytes = maxOf(MIN_GPU_HEADROOM_BYTES, heapBytes / 4L)
        val usableBytes = (heapBytes - reservedBytes).coerceAtLeast(0L)
        if (usableBytes <= 0L) {
            return 1
        }
        val scaled = (usableBytes.toDouble() * maxLayer.toDouble()) / modelBytes.toDouble()
        return scaled.toInt().coerceIn(1, maxLayer)
    }

    private fun applyProbeLayerCap(layers: List<Int>, maxLayers: Int?): List<Int> {
        if (layers.isEmpty()) {
            return emptyList()
        }
        val cap = maxLayers ?: return layers
        return layers.filter { layer -> layer <= cap }
            .ifEmpty { listOf(layers.minOrNull() ?: 1) }
            .distinct()
            .sorted()
    }

    override fun diagnosticsLine(): String {
        val result = latestResult
        return "GPU_PROBE|status=${result.status}|max_layers=${result.maxStableGpuLayers}|reason=${result.failureReason ?: "none"}|" +
            "detail=${result.detail.orEmpty()}|cache_key=${result.cacheKey.orEmpty()}|native_vulkan_payload=${latestNativeDiagnosticsPayload}"
    }

    private fun computeCacheKey(
        request: GpuProbeRequest,
        nativeDiagnostics: NativeVulkanDiagnostics,
    ): String {
        val policy = resolveProbePolicy(nativeDiagnostics)
        val raw = listOf(
            "policy=$PROBE_POLICY_VERSION",
            "fingerprint=$deviceFingerprint",
            "driverName=${nativeDiagnostics.driverName}",
            "driverVersion=${nativeDiagnostics.driverVersion}",
            "vkApi=${nativeDiagnostics.selectedDeviceApiVersion}",
            "vramBytes=${nativeDiagnostics.deviceLocalHeapBytes}",
            "half16=${nativeDiagnostics.storageBuffer16BitAccess && nativeDiagnostics.shaderFloat16}",
            "build=$appBuildSignature",
            "model=${request.cacheIdentity()}",
            "ladder=${policy.layerLadder.joinToString(",")}",
        ).joinToString(separator = "|")
        return sha256(raw)
    }

    private fun GpuProbeRequest.cacheIdentity(): String {
        val fingerprint = modelContentFingerprint?.trim().orEmpty()
        val versionOrFingerprint = if (fingerprint.isNotEmpty()) {
            "sha256=$fingerprint"
        } else {
            "version=${modelVersion.ifBlank { "unknown" }}"
        }
        val fileSizePart = modelFileSizeBytes.takeIf { it > 0L }?.let { bytes -> "|bytes=$bytes" }.orEmpty()
        return "${modelId}|$versionOrFingerprint$fileSizePart"
    }

    private fun readCachedResult(cacheKey: String): GpuProbeResult? {
        val encoded = resultStore.get(cacheKey) ?: return null
        val payload = runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull() ?: return null
        val status = payload["status"]?.jsonPrimitive?.content?.let { raw ->
            runCatching { GpuProbeStatus.valueOf(raw) }.getOrNull()
        } ?: return null
        val reason = payload["failureReason"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { GpuProbeFailureReason.valueOf(raw) }.getOrNull() }
        return GpuProbeResult(
            status = status,
            maxStableGpuLayers = payload["maxStableGpuLayers"]?.jsonPrimitive?.intOrNull ?: 0,
            failureReason = reason,
            detail = payload["detail"]?.jsonPrimitive?.contentOrNull,
            cacheKey = cacheKey,
            checkedAtEpochMs = payload["checkedAtEpochMs"]?.jsonPrimitive?.longOrNull ?: now(),
        )
    }

    private fun writeCachedResult(cacheKey: String, result: GpuProbeResult) {
        val encoded = buildJsonObject {
            put("status", result.status.name)
            put("maxStableGpuLayers", result.maxStableGpuLayers)
            put("failureReason", result.failureReason?.name ?: "")
            put("detail", result.detail.orEmpty())
            put("checkedAtEpochMs", result.checkedAtEpochMs)
        }.toString()
        resultStore.put(cacheKey, encoded)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

}

private class SharedPrefsGpuProbeResultStore(
    private val prefs: SharedPreferences,
) : GpuProbeResultStore {
    override fun get(cacheKey: String): String? = prefs.getString("$RESULT_PREFIX$cacheKey", null)

    override fun put(cacheKey: String, value: String) {
        prefs.edit()
            .putString("$RESULT_PREFIX$cacheKey", value)
            .putLong(KEY_LAST_WRITE_EPOCH_MS, System.currentTimeMillis())
            .apply()
    }
}

data class NativeVulkanDiagnostics(
    val runtimeSupported: Boolean,
    val driverName: String,
    val driverVersion: Long,
    val instanceApiVersion: Long,
    val selectedDeviceApiVersion: Long,
    val deviceLocalHeapBytes: Long,
    val storageBuffer16BitAccess: Boolean,
    val shaderFloat16: Boolean,
    val flashAttnActive: Boolean,
    val rawPayload: String,
)

class NativeVulkanDiagnosticsReader(
    private val payloadProvider: () -> String? = { null },
) {
    fun read(): NativeVulkanDiagnostics {
        val payload = runCatching { payloadProvider() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return NativeVulkanDiagnostics(
            runtimeSupported = root.booleanOrDefault("runtime_supported", false),
            driverName = root.stringOrDefault("driver_name", ""),
            driverVersion = root.longOrDefault("driver_version", 0L),
            instanceApiVersion = root.longOrDefault("instance_api_version", 0L),
            selectedDeviceApiVersion = root.longOrDefault("selected_device_api_version", 0L),
            deviceLocalHeapBytes = root.longOrDefault("device_local_heap_bytes", 0L),
            storageBuffer16BitAccess = root.booleanOrDefault("storage_buffer_16bit_access", false),
            shaderFloat16 = root.booleanOrDefault("shader_float16", false),
            flashAttnActive = root.booleanOrDefault("flashAttnActive", false),
            rawPayload = payload,
        )
    }
}

private fun JsonObject.stringOrDefault(key: String, defaultValue: String): String {
    return this[key]?.jsonPrimitive?.contentOrNull ?: defaultValue
}

private fun JsonObject.longOrDefault(key: String, defaultValue: Long): Long {
    return this[key]?.jsonPrimitive?.longOrNull ?: defaultValue
}

private fun JsonObject.booleanOrDefault(key: String, defaultValue: Boolean): Boolean {
    return this[key]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.let { raw ->
        when (raw) {
            "true", "1" -> true
            "false", "0" -> false
            else -> defaultValue
        }
    } ?: defaultValue
}
