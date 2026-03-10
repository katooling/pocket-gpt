package com.pocketagent.android.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RuntimeDiagnosticsSnapshot(
    val backendProfile: String? = null,
    val compiledBackend: String? = null,
    val nativeRuntimeSupported: Boolean? = null,
    val strictAcceleratorFailFast: Boolean? = null,
    val autoBackendCpuFallback: Boolean? = null,
)

internal object RuntimeDiagnosticsSnapshotParser {
    fun parse(exportedDiagnostics: String?): RuntimeDiagnosticsSnapshot {
        if (exportedDiagnostics.isNullOrBlank()) {
            return RuntimeDiagnosticsSnapshot()
        }
        val lines = exportedDiagnostics.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()
        val gpuOffloadLine = lines.lastOrNull { line -> line.startsWith("GPU_OFFLOAD|") }
        val gpuProbeLine = lines.lastOrNull { line -> line.startsWith("GPU_PROBE|") }
        val offloadFields = parsePipeFields(gpuOffloadLine)
        val backendPayload = extractNativeBackendPayload(gpuProbeLine)
        val nativePayloadRoot = parseJsonObject(backendPayload)

        val backendProfile = nativePayloadRoot.stringOrNull("backend_profile")
            ?: offloadFields["backend_profile"]?.trim()?.takeIf { value -> value.isNotEmpty() }
        val compiledBackend = nativePayloadRoot.stringOrNull("compiled_backend")
        val runtimeSupported = nativePayloadRoot.booleanOrNull("runtime_supported")
            ?: offloadFields["runtime_supported"].toBooleanOrNullCompat()
        val strictAcceleratorFailFast = nativePayloadRoot.booleanOrNull("strict_accelerator_fail_fast")
            ?: offloadFields["strict_accelerator_fail_fast"].toBooleanOrNullCompat()
        val autoBackendCpuFallback = nativePayloadRoot.booleanOrNull("auto_backend_cpu_fallback")
            ?: offloadFields["auto_backend_cpu_fallback"].toBooleanOrNullCompat()

        return RuntimeDiagnosticsSnapshot(
            backendProfile = backendProfile,
            compiledBackend = compiledBackend,
            nativeRuntimeSupported = runtimeSupported,
            strictAcceleratorFailFast = strictAcceleratorFailFast,
            autoBackendCpuFallback = autoBackendCpuFallback,
        )
    }

    private fun parsePipeFields(line: String?): Map<String, String> {
        if (line.isNullOrBlank()) {
            return emptyMap()
        }
        return line.split('|')
            .drop(1)
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    val key = token.substring(0, separator).trim()
                    val value = token.substring(separator + 1).trim()
                    if (key.isEmpty()) {
                        null
                    } else {
                        key to value
                    }
                }
            }
            .toMap()
    }

    private fun extractNativeBackendPayload(line: String?): String? {
        if (line.isNullOrBlank()) {
            return null
        }
        val marker = "native_backend_payload="
        val markerIndex = line.indexOf(marker)
        if (markerIndex < 0) {
            return null
        }
        return line.substring(markerIndex + marker.length)
            .trim()
            .takeIf { payload -> payload.isNotEmpty() }
    }

    private fun parseJsonObject(payload: String?): JsonObject {
        if (payload.isNullOrBlank()) {
            return JsonObject(emptyMap())
        }
        return runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        return this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            .toBooleanOrNullCompat()
    }

    private fun String?.toBooleanOrNullCompat(): Boolean? {
        val normalized = this?.trim()?.lowercase() ?: return null
        return when (normalized) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }
}
