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

    internal constructor(featureProbe: FeatureProbe) {
        this.featureProbe = featureProbe
    }

    override fun isSupported(): Boolean {
        return runCatching {
            // Vulkan features are versioned features and should be queried via hasSystemFeature(name, version).
            val hasCompute = featureProbe.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE, 0)
            val hasLevel = featureProbe.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 0)
            val hasVersion = featureProbe.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0)
            val supported = hasCompute || hasLevel || hasVersion
            safeLogInfo(
                tag,
                "GPU_OFFLOAD|has_compute=$hasCompute|has_level=$hasLevel|has_version=$hasVersion|supported=$supported",
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
}
