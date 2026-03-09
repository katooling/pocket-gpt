package com.pocketagent.android.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.pocketagent.android.BuildConfig
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.nativebridge.NativeJniLlamaCppBridge
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
private const val PROBE_LAYER_TIMEOUT_MS = 30_000L
private val PROBE_LAYER_LADDER: List<Int> = listOf(1, 2, 4, 8, 16, 32)

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

    return GpuProbeRequest(
        modelId = candidate.modelId,
        modelVersion = candidate.activeVersion.orEmpty(),
        modelPath = candidate.absolutePath.orEmpty(),
        layerLadder = PROBE_LAYER_LADDER,
    )
}

class AndroidGpuOffloadQualifier(
    context: Context,
    probeClient: GpuProbeClient = AndroidGpuProbeClient(context.applicationContext),
    probeRequestResolver: () -> GpuProbeRequest? = {
        resolveProbeRequestFromStore(AndroidRuntimeProvisioningStore(context.applicationContext))
    },
    nativeDiagnosticsReader: NativeVulkanDiagnosticsReader = NativeVulkanDiagnosticsReader(),
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
                detail = "active_probe_model_unavailable",
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
            return latestResult.copy(status = GpuProbeStatus.PENDING, cacheKey = cacheKey)
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
            val result = runCatching {
                runLayerQualification(request = request)
            }.getOrElse { error ->
                GpuProbeResult(
                    status = GpuProbeStatus.FAILED,
                    failureReason = GpuProbeFailureReason.UNKNOWN,
                    detail = "probe_exception:${error.message ?: error::class.simpleName}",
                )
            }.copy(cacheKey = cacheKey, checkedAtEpochMs = now())

            writeCachedResult(cacheKey = cacheKey, result = result)
            latestResult = result
            inFlightCacheKey = null
            safeLogInfo(
                "GPU_PROBE|status=${result.status}|max_layers=${result.maxStableGpuLayers}|reason=${result.failureReason}|" +
                    "detail=${result.detail.orEmpty()}|cache_key=$cacheKey",
            )
        }
        return pending
    }

    private suspend fun runLayerQualification(request: GpuProbeRequest): GpuProbeResult {
        val orderedLayers = request.layerLadder.filter { it > 0 }.distinct().sorted()
        if (orderedLayers.isEmpty()) {
            return GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.UNKNOWN,
                detail = "probe_layer_ladder_empty",
            )
        }

        var maxStableLayers = 0
        var failedLayer: Int? = null
        var failure: GpuProbeResult? = null

        for (layer in orderedLayers) {
            val layerResult = probeClient.runProbe(
                request = request.copy(layerLadder = listOf(layer)),
                timeoutMs = PROBE_LAYER_TIMEOUT_MS,
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
            val hardFailure = when (failure?.failureReason) {
                GpuProbeFailureReason.PROBE_PROCESS_DIED,
                GpuProbeFailureReason.PROBE_TIMEOUT,
                GpuProbeFailureReason.NATIVE_GENERATE_FAILED,
                    -> true
                else -> false
            }
            val hardFailureResult = failure
            if (hardFailure && hardFailureResult != null) {
                return hardFailureResult.copy(
                    status = GpuProbeStatus.FAILED,
                    maxStableGpuLayers = 0,
                    detail = hardFailureResult.detail ?: "probe_hard_failure_at_layer=${failedLayer ?: "unknown"}",
                )
            }
            val detail = if (failedLayer == null) {
                "probe_success"
            } else {
                "probe_partial_success:last_failed_layer=$failedLayer:reason=${failure?.failureReason ?: "unknown"}"
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
            detail = failure.detail ?: "probe_failed_at_layer=${failedLayer ?: "unknown"}",
        ) ?: GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.UNKNOWN,
            detail = "probe_failed_without_result",
        )
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
        val raw = listOf(
            "fingerprint=$deviceFingerprint",
            "driverName=${nativeDiagnostics.driverName}",
            "driverVersion=${nativeDiagnostics.driverVersion}",
            "build=$appBuildSignature",
            "model=${request.modelId}@${request.modelVersion}",
        ).joinToString(separator = "|")
        return sha256(raw)
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

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(tag, message) }
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
    val storageBuffer16BitAccess: Boolean,
    val shaderFloat16: Boolean,
    val rawPayload: String,
)

class NativeVulkanDiagnosticsReader(
    private val payloadProvider: () -> String? = { NativeJniLlamaCppBridge().vulkanDiagnosticsJson() },
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
            storageBuffer16BitAccess = root.booleanOrDefault("storage_buffer_16bit_access", false),
            shaderFloat16 = root.booleanOrDefault("shader_float16", false),
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
