package com.pocketagent.android.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Locale

class AndroidGpuOffloadSupport : DeviceGpuOffloadSupport {
    private val tag = "AndroidGpuSupport"
    private val deviceProbe: DeviceProbe

    constructor(context: Context) {
        deviceProbe = SystemDeviceProbe(context.applicationContext)
    }

    internal constructor(
        deviceProbe: DeviceProbe,
    ) {
        this.deviceProbe = deviceProbe
    }

    override fun isSupported(): Boolean {
        return runCatching {
            // Advisory-only signal; final eligibility remains runtime + probe validation.
            val hasAdreno = deviceProbe.isAdrenoFamily()
            val hasDotProd = deviceProbe.hasArmDotProd()
            val hasI8mm = deviceProbe.hasArmI8mm()
            val supportedForProbe = hasAdreno && hasDotProd && hasI8mm
            safeLogInfo(
                tag,
                "GPU_OFFLOAD|has_adreno=$hasAdreno|has_dotprod=$hasDotProd|has_i8mm=$hasI8mm|" +
                    "supported_for_probe=$supportedForProbe",
            )
            supportedForProbe
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

    internal interface DeviceProbe {
        fun isAdrenoFamily(): Boolean
        fun hasArmDotProd(): Boolean
        fun hasArmI8mm(): Boolean
    }

    private class SystemDeviceProbe(
        private val context: Context,
    ) : DeviceProbe {
        private val cachedCpuInfoFeatures: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
            runCatching {
                File("/proc/cpuinfo")
                    .readText()
                    .lowercase(Locale.ROOT)
            }.getOrDefault("")
        }

        private val cachedDeviceSignature: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
            listOf(
                Build.HARDWARE,
                Build.BOARD,
                Build.DEVICE,
                Build.MODEL,
                Build.PRODUCT,
                readBuildField("SOC_MODEL"),
                readBuildField("SOC_MANUFACTURER"),
                context.packageManager.systemAvailableFeatures?.joinToString(separator = ",") { it.name.orEmpty() },
            ).joinToString(separator = " ").lowercase(Locale.ROOT)
        }

        override fun isAdrenoFamily(): Boolean {
            val raw = cachedDeviceSignature
            return raw.contains("adreno") || raw.contains("qcom") || raw.contains("qualcomm")
        }

        override fun hasArmDotProd(): Boolean {
            val raw = cachedCpuInfoFeatures
            return raw.contains("dotprod") || raw.contains("asimddp")
        }

        override fun hasArmI8mm(): Boolean {
            val raw = cachedCpuInfoFeatures
            return raw.contains("i8mm")
        }

        private fun readBuildField(fieldName: String): String {
            return runCatching {
                Build::class.java.getDeclaredField(fieldName).get(null)?.toString().orEmpty()
            }.getOrDefault("")
        }
    }
}
