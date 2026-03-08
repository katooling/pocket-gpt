package com.pocketagent.android.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

class AndroidGpuOffloadSupport(
    context: Context,
) : DeviceGpuOffloadSupport {
    private val tag = "AndroidGpuSupport"
    private val packageManager = context.applicationContext.packageManager

    override fun isSupported(): Boolean {
        return runCatching {
            val hasCompute = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)
            val hasVersion = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            Log.i(tag, "GPU_OFFLOAD|has_compute=$hasCompute|has_version=$hasVersion")
            hasCompute && hasVersion
        }.getOrElse {
            Log.w(tag, "GPU_OFFLOAD|feature_query_failed", it)
            false
        }
    }
}
