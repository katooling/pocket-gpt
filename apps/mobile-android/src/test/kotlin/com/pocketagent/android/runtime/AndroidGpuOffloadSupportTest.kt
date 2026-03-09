package com.pocketagent.android.runtime

import android.content.pm.PackageManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidGpuOffloadSupportTest {
    @Test
    fun `reports supported when vulkan compute and version 1_2 are present`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = FakeFeatureProbe(
                supportedFeatures = setOf(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE),
                featureVersions = mapOf(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION to VULKAN_1_2),
            ),
        )

        assertTrue(support.isSupported())
    }

    @Test
    fun `reports unsupported when vulkan compute is present but version is below 1_2`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = FakeFeatureProbe(
                supportedFeatures = setOf(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE),
                featureVersions = mapOf(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION to VULKAN_1_1),
            ),
        )

        assertFalse(support.isSupported())
    }

    @Test
    fun `reports unsupported when vulkan feature signals are absent`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = FakeFeatureProbe(),
        )

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

    @Test
    fun `reports unsupported when vulkan version 1_2 is present but compute feature is absent`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = FakeFeatureProbe(
                featureVersions = mapOf(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION to VULKAN_1_2),
            ),
        )

        assertFalse(support.isSupported())
    }

    @Test
    fun `reports unsupported when vulkan compute is present but version feature is absent`() {
        val support = AndroidGpuOffloadSupport(
            featureProbe = FakeFeatureProbe(
                supportedFeatures = setOf(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE),
            ),
        )

        assertFalse(support.isSupported())
    }
}

private class FakeFeatureProbe(
    private val supportedFeatures: Set<String> = emptySet(),
    private val featureVersions: Map<String, Int> = emptyMap(),
) : AndroidGpuOffloadSupport.FeatureProbe {
    override fun hasSystemFeature(name: String, version: Int): Boolean {
        if (version <= 0) {
            return supportedFeatures.contains(name) || featureVersions.containsKey(name)
        }
        val maxVersion = featureVersions[name] ?: return false
        return maxVersion >= version
    }
}

private const val VULKAN_1_1 = (1 shl 22) or (1 shl 12)
private const val VULKAN_1_2 = (1 shl 22) or (2 shl 12)
