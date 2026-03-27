package com.pocketagent.android.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidGpuOffloadSupportTest {
    @Test
    fun `reports advisory supported and release eligible when adreno 7xx cpu features are present`() {
        val support = AndroidGpuOffloadSupport(
            deviceProbe = FakeDeviceProbe(
                adreno = true,
                dotProd = true,
                i8mm = true,
                adrenoGeneration = 7,
            ),
        )

        val advisory = support.advisory()
        assertTrue(advisory.supportedForProbe)
        assertTrue(advisory.automaticOpenClEligible)
    }

    @Test
    fun `reports advisory unsupported when adreno family is missing`() {
        val support = AndroidGpuOffloadSupport(
            deviceProbe = FakeDeviceProbe(
                adreno = false,
                dotProd = true,
                i8mm = true,
            ),
        )

        assertFalse(support.isSupported())
    }

    @Test
    fun `reports advisory unsupported when required cpu features are missing`() {
        val support = AndroidGpuOffloadSupport(
            deviceProbe = FakeDeviceProbe(
                adreno = true,
                dotProd = true,
                i8mm = false,
            ),
        )

        assertFalse(support.isSupported())
    }

    @Test
    fun `adreno 6xx remains probe supported but not auto qualified for opencl`() {
        val support = AndroidGpuOffloadSupport(
            deviceProbe = FakeDeviceProbe(
                adreno = true,
                dotProd = true,
                i8mm = true,
                adrenoGeneration = 6,
            ),
        )

        val advisory = support.advisory()
        assertTrue(advisory.supportedForProbe)
        assertFalse(advisory.automaticOpenClEligible)
        assertEquals("adreno_generation_below_7xx", advisory.reason)
    }

    @Test
    fun `reports unsupported when probing throws`() {
        val support = AndroidGpuOffloadSupport(
            deviceProbe = object : AndroidGpuOffloadSupport.DeviceProbe {
                override fun isArm64V8a(): Boolean = true
                override fun isProbablyEmulator(): Boolean = false
                override fun isAdrenoFamily(): Boolean = error("probe failed")
                override fun hasArmDotProd(): Boolean = true
                override fun hasArmI8mm(): Boolean = true
                override fun adrenoGeneration(): Int = 0
            },
        )

        assertFalse(support.isSupported())
    }
}

private class FakeDeviceProbe(
    private val adreno: Boolean,
    private val dotProd: Boolean,
    private val i8mm: Boolean,
    private val adrenoGeneration: Int = 7,
) : AndroidGpuOffloadSupport.DeviceProbe {
    override fun isArm64V8a(): Boolean = true

    override fun isProbablyEmulator(): Boolean = false

    override fun isAdrenoFamily(): Boolean = adreno

    override fun hasArmDotProd(): Boolean = dotProd

    override fun hasArmI8mm(): Boolean = i8mm

    override fun adrenoGeneration(): Int = adrenoGeneration
}
