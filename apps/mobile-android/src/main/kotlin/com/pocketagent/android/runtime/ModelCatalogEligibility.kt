package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.ModelRuntimeFormats

enum class ModelSupportLevel {
    SUPPORTED,
    EXPERIMENTAL,
    UNSUPPORTED,
}

enum class ModelEligibilityReason {
    NONE,
    RUNTIME_COMPATIBILITY_MISMATCH,
    MODEL_NOT_RUNTIME_ENABLED,
    DEVICE_GPU_CLASS_UNSUPPORTED,
    GPU_RUNTIME_UNAVAILABLE,
    GPU_QUALIFICATION_PENDING,
    GPU_QUALIFICATION_FAILED,
}

data class ModelVersionEligibility(
    val supportLevel: ModelSupportLevel,
    val catalogVisible: Boolean,
    val downloadAllowed: Boolean,
    val loadAllowed: Boolean,
    val reason: ModelEligibilityReason = ModelEligibilityReason.NONE,
    val technicalDetail: String? = null,
) {
    val experimental: Boolean
        get() = supportLevel == ModelSupportLevel.EXPERIMENTAL

    companion object {
        fun supported(): ModelVersionEligibility {
            return ModelVersionEligibility(
                supportLevel = ModelSupportLevel.SUPPORTED,
                catalogVisible = true,
                downloadAllowed = true,
                loadAllowed = true,
            )
        }

        fun unsupported(
            reason: ModelEligibilityReason,
            technicalDetail: String,
            catalogVisible: Boolean = false,
        ): ModelVersionEligibility {
            return ModelVersionEligibility(
                supportLevel = ModelSupportLevel.UNSUPPORTED,
                catalogVisible = catalogVisible,
                downloadAllowed = false,
                loadAllowed = false,
                reason = reason,
                technicalDetail = technicalDetail,
            )
        }

        fun experimental(
            reason: ModelEligibilityReason,
            technicalDetail: String,
        ): ModelVersionEligibility {
            return ModelVersionEligibility(
                supportLevel = ModelSupportLevel.EXPERIMENTAL,
                catalogVisible = true,
                downloadAllowed = true,
                loadAllowed = true,
                reason = reason,
                technicalDetail = technicalDetail,
            )
        }
    }
}

data class ModelCatalogEligibilitySnapshot(
    val versionEligibilityByKey: Map<String, ModelVersionEligibility> = emptyMap(),
    val signals: ModelEligibilitySignals = ModelEligibilitySignals.assumeSupported(),
) {
    fun eligibilityFor(
        modelId: String,
        version: String,
    ): ModelVersionEligibility {
        return versionEligibilityByKey[modelVersionEligibilityKey(modelId, version)]
            ?: ModelVersionEligibility.supported()
    }
}

data class ModelEligibilitySignals(
    val runtimeCompatibilityTag: String,
    val runtimeSupportsGpuOffload: Boolean,
    val deviceAdvisory: DeviceGpuOffloadAdvisory,
    val gpuProbeResult: GpuProbeResult,
    val runtimeDiagnostics: RuntimeDiagnosticsSnapshot? = null,
) {
    fun backendCapability(family: RuntimeBackendFamily): RuntimeBackendCapability? {
        return runtimeDiagnostics?.backendCapability(family)
    }

    companion object {
        fun assumeSupported(
            runtimeCompatibilityTag: String = "android-arm64-v8a",
        ): ModelEligibilitySignals {
            return ModelEligibilitySignals(
                runtimeCompatibilityTag = runtimeCompatibilityTag,
                runtimeSupportsGpuOffload = true,
                deviceAdvisory = DeviceGpuOffloadAdvisory(),
                gpuProbeResult = GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 32,
                    detail = "assume_supported",
                ),
            )
        }
    }
}

interface ModelEligibilitySignalsProvider {
    fun currentSignals(): ModelEligibilitySignals

    companion object {
        val ASSUME_SUPPORTED = object : ModelEligibilitySignalsProvider {
            override fun currentSignals(): ModelEligibilitySignals = ModelEligibilitySignals.assumeSupported()
        }
    }
}

class AndroidModelEligibilitySignalsProvider(
    private val runtimeCompatibilityTag: String,
    private val deviceGpuOffloadSupport: DeviceGpuOffloadSupport,
    private val gpuOffloadQualifier: GpuOffloadQualifier,
    private val runtimeSupportProvider: () -> Boolean,
    private val runtimeDiagnosticsProvider: () -> RuntimeDiagnosticsSnapshot? = { null },
) : ModelEligibilitySignalsProvider {
    override fun currentSignals(): ModelEligibilitySignals {
        val advisory = deviceGpuOffloadSupport.advisory()
        val runtimeDiagnostics = runtimeDiagnosticsProvider()
        val runtimeSupported = runtimeDiagnostics?.nativeRuntimeSupported ?: runtimeSupportProvider()
        val probe = gpuOffloadQualifier.evaluate(
            runtimeSupported = runtimeSupported,
            deviceAdvisory = advisory,
        )
        return ModelEligibilitySignals(
            runtimeCompatibilityTag = runtimeCompatibilityTag,
            runtimeSupportsGpuOffload = runtimeSupported,
            deviceAdvisory = advisory,
            gpuProbeResult = probe,
            runtimeDiagnostics = runtimeDiagnostics,
        )
    }
}

data class ModelEligibilityCandidate(
    val modelId: String,
    val version: String? = null,
    val runtimeCompatibility: String = "",
)

interface ModelCatalogEligibilityEvaluator {
    fun evaluate(
        manifest: ModelDistributionManifest,
        snapshot: RuntimeProvisioningSnapshot?,
        signals: ModelEligibilitySignals,
    ): ModelCatalogEligibilitySnapshot

    fun evaluateCandidate(
        candidate: ModelEligibilityCandidate,
        signals: ModelEligibilitySignals,
    ): ModelVersionEligibility
}

class DefaultModelCatalogEligibilityEvaluator : ModelCatalogEligibilityEvaluator {
    override fun evaluate(
        manifest: ModelDistributionManifest,
        snapshot: RuntimeProvisioningSnapshot?,
        signals: ModelEligibilitySignals,
    ): ModelCatalogEligibilitySnapshot {
        val entries = linkedMapOf<String, ModelVersionEligibility>()
        manifest.models.forEach { model ->
            model.versions.forEach { version ->
                entries[modelVersionEligibilityKey(version.modelId, version.version)] = evaluateCandidate(
                    candidate = ModelEligibilityCandidate(
                        modelId = version.modelId,
                        version = version.version,
                        runtimeCompatibility = version.runtimeCompatibility,
                    ),
                    signals = signals,
                )
            }
        }
        snapshot?.models.orEmpty().forEach { model ->
            model.installedVersions.forEach { version ->
                val key = modelVersionEligibilityKey(version.modelId, version.version)
                entries[key] = evaluateCandidate(
                    candidate = ModelEligibilityCandidate(
                        modelId = version.modelId,
                        version = version.version,
                        runtimeCompatibility = version.runtimeCompatibility,
                    ),
                    signals = signals,
                )
            }
        }
        return ModelCatalogEligibilitySnapshot(
            versionEligibilityByKey = entries,
            signals = signals,
        )
    }

    override fun evaluateCandidate(
        candidate: ModelEligibilityCandidate,
        signals: ModelEligibilitySignals,
    ): ModelVersionEligibility {
        val modelId = candidate.modelId
        val version = candidate.version ?: "unknown"
        val runtimeCompatibility = candidate.runtimeCompatibility
        val descriptor = ModelCatalog.descriptorFor(modelId)
        if (descriptor != null && !descriptor.bridgeSupported) {
            return ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.MODEL_NOT_RUNTIME_ENABLED,
                technicalDetail = "model=$modelId|bridge_supported=false",
            )
        }
        if (runtimeCompatibility.isNotBlank() && runtimeCompatibility != signals.runtimeCompatibilityTag) {
            return ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.RUNTIME_COMPATIBILITY_MISMATCH,
                technicalDetail = "model=$modelId|version=$version|runtime=$runtimeCompatibility|expected=${signals.runtimeCompatibilityTag}",
            )
        }

        val formatHint = ModelRuntimeFormats.infer(
            modelId = modelId,
            modelVersion = version,
            modelPath = null,
        )
        if (!formatHint.requiresQualifiedGpu) {
            return ModelVersionEligibility.supported()
        }

        if (!signals.runtimeSupportsGpuOffload) {
            return ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.GPU_RUNTIME_UNAVAILABLE,
                technicalDetail = buildString {
                    append("model=").append(modelId)
                    append("|version=").append(version)
                    append("|format=").append(formatHint.normalizedToken ?: formatHint.family.name.lowercase())
                    append("|runtime_gpu_supported=false")
                    appendBackendDetail(signals)
                },
            )
        }
        if (!signals.deviceAdvisory.supportedForProbe) {
            return ModelVersionEligibility.unsupported(
                reason = ModelEligibilityReason.DEVICE_GPU_CLASS_UNSUPPORTED,
                technicalDetail = buildString {
                    append("model=").append(modelId)
                    append("|version=").append(version)
                    append("|reason=").append(signals.deviceAdvisory.reason)
                    appendBackendDetail(signals)
                },
            )
        }

        return when (signals.gpuProbeResult.status) {
            GpuProbeStatus.QUALIFIED ->
                ModelVersionEligibility.supported()

            GpuProbeStatus.PENDING ->
                ModelVersionEligibility.experimental(
                    reason = ModelEligibilityReason.GPU_QUALIFICATION_PENDING,
                    technicalDetail = "model=$modelId|version=$version|probe_status=pending",
                )

            GpuProbeStatus.FAILED ->
                ModelVersionEligibility.experimental(
                    reason = ModelEligibilityReason.GPU_QUALIFICATION_FAILED,
                    technicalDetail = buildString {
                        append("model=").append(modelId)
                        append("|version=").append(version)
                        append("|probe_status=failed")
                        signals.gpuProbeResult.failureReason?.let { reason ->
                            append("|probe_reason=").append(reason.name.lowercase())
                        }
                        appendBackendDetail(signals)
                    },
                )
        }
    }

    private fun StringBuilder.appendBackendDetail(signals: ModelEligibilitySignals) {
        val opencl = signals.backendCapability(RuntimeBackendFamily.OPENCL)
        if (opencl != null) {
            append("|opencl_compiled=").append(opencl.compiled)
            append("|opencl_registered=").append(opencl.registered)
            append("|opencl_discovered=").append(opencl.discovered ?: "unknown")
            append("|opencl_device_count=").append(opencl.deviceCount ?: -1)
            append("|opencl_runtime_available=").append(opencl.runtimeAvailable ?: "unknown")
            append("|opencl_qualified=").append(opencl.qualified ?: "unknown")
        }
        val activeBackend = signals.runtimeDiagnostics?.activeBackend
        if (!activeBackend.isNullOrBlank()) {
            append("|active_backend=").append(activeBackend)
        }
    }
}

internal fun modelVersionEligibilityKey(
    modelId: String,
    version: String,
): String = "$modelId::$version"
