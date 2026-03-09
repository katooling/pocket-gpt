package com.pocketagent.android.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

class AndroidGpuOffloadSupport : DeviceGpuOffloadSupport {
    private val tag = "AndroidGpuSupport"
    private val featureProbe: FeatureProbe

    constructor(context: Context) {
        featureProbe = PackageManagerFeatureProbe(context.applicationContext.packageManager)
    }

    internal constructor(
        featureProbe: FeatureProbe,
    ) {
        this.featureProbe = featureProbe
    }

    override fun isSupported(): Boolean {
        return runCatching {
            // Vulkan features are versioned features and should be queried via hasSystemFeature(name, version).
            val hasCompute = featureProbe.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE, 0)
            val hasLevel = featureProbe.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 0)
            val hasVersionAny = featureProbe.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0)
            val hasVersion12 = featureProbe.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
                MIN_VULKAN_VERSION_1_2,
            )
            // Keep this gate strict to avoid native hard-crashes on devices that expose Vulkan
            // metadata but fail in runtime model load when GPU layers are enabled.
            val supported = hasCompute && hasVersion12
            safeLogInfo(
                tag,
                "GPU_OFFLOAD|has_compute=$hasCompute|has_level=$hasLevel|has_version_any=$hasVersionAny|has_version_1_2=$hasVersion12|supported=$supported",
            )
            supported
        }.getOrElse {
            safeLogWarning(tag, "GPU_OFFLOAD|feature_query_failed", it)
            false
        }
    }

    private fun safeLogInfo(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
    }

    private fun safeLogWarning(tag: String, message: String, throwable: Throwable) {
        runCatching { Log.w(tag, message, throwable) }
    }

    internal interface FeatureProbe {
        fun hasSystemFeature(name: String, version: Int): Boolean
    }

    private class PackageManagerFeatureProbe(
        private val packageManager: PackageManager,
    ) : FeatureProbe {
        override fun hasSystemFeature(name: String, version: Int): Boolean {
            return packageManager.hasSystemFeature(name, version)
        }
    }

    private companion object {
        // Encoded via VK_MAKE_API_VERSION variant used by PackageManager feature versions.
        private const val MIN_VULKAN_VERSION_1_2 = (1 shl 22) or (2 shl 12)
    }
}
