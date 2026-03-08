package com.pocketagent.android.runtime

import android.content.pm.PackageManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidGpuOffloadSupportTest {
    @Test
    fun `reports supported when vulkan compute feature is present`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = FakeFeatureProbe(
                supports = mapOf(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE to true),
            ),
        )

        assertTrue(support.isSupported())
    }

    @Test
    fun `reports supported when vulkan level feature is present`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = FakeFeatureProbe(
                supports = mapOf(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL to true),
            ),
        )

        assertTrue(support.isSupported())
    }

    @Test
    fun `reports unsupported when vulkan feature signals are absent`() {
        val support = AndroidGpuOffloadSupport(featureProbe = FakeFeatureProbe())

        assertFalse(support.isSupported())
    }

    @Test
    fun `reports unsupported when feature query throws`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = object : AndroidGpuOffloadSupport.FeatureProbe {
                override fun hasSystemFeature(name: String, version: Int): Boolean {
                    error("feature query failed")
                }
            },
        )

        assertFalse(support.isSupported())
    }
}

private class FakeFeatureProbe(
    private val supports: Map<String, Boolean> = emptyMap(),
) : AndroidGpuOffloadSupport.FeatureProbe {
    override fun hasSystemFeature(name: String, version: Int): Boolean {
        return supports[name] ?: false
    }
}
