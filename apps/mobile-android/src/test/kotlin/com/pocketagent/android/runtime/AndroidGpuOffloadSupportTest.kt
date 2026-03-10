package com.pocketagent.android.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidGpuOffloadSupportTest {
    @Test
    fun `reports supported when adreno and cpu features are present`() {
        val support = AndroidGpuOffloadSupport(
            deviceProbe = FakeDeviceProbe(
                adreno = true,
                dotProd = true,
                i8mm = true,
            ),
        )

        assertTrue(support.isSupported())
    }

    @Test
    fun `reports unsupported when adreno family is missing`() {
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
    fun `reports unsupported when required cpu features are missing`() {
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
    fun `reports unsupported when probing throws`() {
        val support = AndroidGpuOffloadSupport(
            deviceProbe = object : AndroidGpuOffloadSupport.DeviceProbe {
                override fun isAdrenoFamily(): Boolean = error("probe failed")
                override fun hasArmDotProd(): Boolean = true
                override fun hasArmI8mm(): Boolean = true
            },
        )

        assertFalse(support.isSupported())
    }
}

private class FakeDeviceProbe(
    private val adreno: Boolean,
    private val dotProd: Boolean,
    private val i8mm: Boolean,
) : AndroidGpuOffloadSupport.DeviceProbe {
    override fun isAdrenoFamily(): Boolean = adreno

    override fun hasArmDotProd(): Boolean = dotProd

    override fun hasArmI8mm(): Boolean = i8mm
}
