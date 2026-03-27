package com.pocketagent.android.runtime

import android.content.Context
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import com.pocketagent.nativebridge.OpenClRuntimePolicy
import java.io.File
import java.util.Locale
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext

private val ADRENO_MODEL_REGEX = Regex("""adreno\s*(?:\(tm\)\s*)?(\d{3})""", RegexOption.IGNORE_CASE)

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

    override fun advisory(): DeviceGpuOffloadAdvisory {
        return runCatching {
            val isArm64V8a = deviceProbe.isArm64V8a()
            val isEmulator = deviceProbe.isProbablyEmulator()
            val hasAdreno = deviceProbe.isAdrenoFamily()
            val hasDotProd = deviceProbe.hasArmDotProd()
            val hasI8mm = deviceProbe.hasArmI8mm()
            val adrenoGen = if (hasAdreno) deviceProbe.adrenoGeneration() else 0
            val supportedForProbe = isArm64V8a && !isEmulator && hasAdreno && hasDotProd && hasI8mm
            val automaticOpenClEligible = supportedForProbe &&
                adrenoGen >= OpenClRuntimePolicy.MIN_AUTOMATIC_ADRENO_GENERATION
            val reason = when {
                !isArm64V8a -> "abi_not_arm64_v8a"
                isEmulator -> "emulator_detected"
                !hasAdreno -> "adreno_family_missing"
                !hasDotProd -> "arm_dotprod_missing"
                !hasI8mm -> "arm_i8mm_missing"
                adrenoGen <= 0 -> "adreno_generation_unknown"
                adrenoGen < OpenClRuntimePolicy.MIN_AUTOMATIC_ADRENO_GENERATION -> "adreno_generation_below_7xx"
                else -> "advisory_qualified"
            }
            safeLogInfo(
                tag,
                "GPU_OFFLOAD|arm64_v8a=$isArm64V8a|emulator=$isEmulator|has_adreno=$hasAdreno|" +
                    "has_dotprod=$hasDotProd|has_i8mm=$hasI8mm|adreno_gen=$adrenoGen|" +
                    "supported_for_probe=$supportedForProbe|automatic_opencl_eligible=$automaticOpenClEligible|" +
                    "reason=$reason",
            )
            DeviceGpuOffloadAdvisory(
                isArm64V8a = isArm64V8a,
                isEmulator = isEmulator,
                isAdrenoFamily = hasAdreno,
                hasArmDotProd = hasDotProd,
                hasArmI8mm = hasI8mm,
                adrenoGeneration = adrenoGen,
                supportedForProbe = supportedForProbe,
                automaticOpenClEligible = automaticOpenClEligible,
                reason = reason,
            )
        }.getOrElse {
            safeLogWarning(tag, "GPU_OFFLOAD|feature_query_failed", it)
            DeviceGpuOffloadAdvisory(
                supportedForProbe = false,
                automaticOpenClEligible = false,
                reason = "feature_query_failed:${it.message ?: it::class.simpleName}",
            )
        }
    }

    private fun safeLogInfo(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
    }

    private fun safeLogWarning(tag: String, message: String, throwable: Throwable) {
        runCatching { Log.w(tag, message, throwable) }
    }

    internal interface DeviceProbe {
        fun isArm64V8a(): Boolean
        fun isProbablyEmulator(): Boolean
        fun isAdrenoFamily(): Boolean
        fun hasArmDotProd(): Boolean
        fun hasArmI8mm(): Boolean
        /** Returns the Adreno series (e.g. 7 for Adreno 7xx, 8 for 8xx). 0 if unknown. */
        fun adrenoGeneration(): Int
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
                cachedGpuRenderer,
                readBuildField("SOC_MODEL"),
                readBuildField("SOC_MANUFACTURER"),
                context.packageManager.systemAvailableFeatures?.joinToString(separator = ",") { it.name.orEmpty() },
            ).joinToString(separator = " ").lowercase(Locale.ROOT)
        }

        private val cachedGpuRenderer: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
            queryGpuRenderer().lowercase(Locale.ROOT)
        }

        override fun isArm64V8a(): Boolean = Build.SUPPORTED_ABIS.any { abi -> abi == "arm64-v8a" }

        override fun isProbablyEmulator(): Boolean {
            val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
            val product = Build.PRODUCT.lowercase(Locale.ROOT)
            val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
            val model = Build.MODEL.lowercase(Locale.ROOT)
            return fingerprint.contains("generic") ||
                fingerprint.contains("emulator") ||
                fingerprint.contains("sdk_gphone") ||
                product.contains("sdk") ||
                product.contains("emulator") ||
                hardware.contains("ranchu") ||
                hardware.contains("goldfish") ||
                model.contains("emulator")
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

        override fun adrenoGeneration(): Int {
            // Extract series digit from "Adreno (TM) 730", "Adreno 830", etc.
            val match = ADRENO_MODEL_REGEX.find(cachedDeviceSignature) ?: return 0
            val model = match.groupValues.getOrNull(1)?.firstOrNull()?.digitToIntOrNull() ?: return 0
            return model
        }

        private fun readBuildField(fieldName: String): String {
            return runCatching {
                Build::class.java.getDeclaredField(fieldName).get(null)?.toString().orEmpty()
            }.getOrDefault("")
        }

        private fun queryGpuRenderer(): String {
            return runCatching {
                val egl = EGLContext.getEGL() as? EGL10 ?: return ""
                val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
                if (display == EGL10.EGL_NO_DISPLAY) {
                    return ""
                }
                val versionArray = IntArray(2)
                if (!egl.eglInitialize(display, versionArray)) {
                    return ""
                }
                try {
                    val configSpec = intArrayOf(
                        EGL10.EGL_RENDERABLE_TYPE, 4,
                        EGL10.EGL_NONE,
                    )
                    val configsCount = IntArray(1)
                    val configs = arrayOfNulls<EGLConfig>(1)
                    if (!egl.eglChooseConfig(display, configSpec, configs, 1, configsCount) || configsCount[0] <= 0) {
                        return ""
                    }
                    val context = egl.eglCreateContext(
                        display,
                        configs[0],
                        EGL10.EGL_NO_CONTEXT,
                        intArrayOf(0x3098, 2, EGL10.EGL_NONE),
                    )
                    if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                        return ""
                    }
                    try {
                        val surface = egl.eglCreatePbufferSurface(
                            display,
                            configs[0],
                            intArrayOf(
                                EGL10.EGL_WIDTH, 1,
                                EGL10.EGL_HEIGHT, 1,
                                EGL10.EGL_NONE,
                            ),
                        )
                        if (surface == null || surface == EGL10.EGL_NO_SURFACE) {
                            return ""
                        }
                        try {
                            if (!egl.eglMakeCurrent(display, surface, surface, context)) {
                                return ""
                            }
                            return GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
                        } finally {
                            egl.eglMakeCurrent(
                                display,
                                EGL10.EGL_NO_SURFACE,
                                EGL10.EGL_NO_SURFACE,
                                EGL10.EGL_NO_CONTEXT,
                            )
                            egl.eglDestroySurface(display, surface)
                        }
                    } finally {
                        egl.eglDestroyContext(display, context)
                    }
                } finally {
                    egl.eglTerminate(display)
                }
            }.getOrDefault("")
        }
    }
}
