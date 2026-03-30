package com.pocketagent.runtime

import com.pocketagent.nativebridge.KvCacheMethod
import com.pocketagent.nativebridge.KvCacheMethodPreset
import com.pocketagent.nativebridge.ModelRuntimeMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeModelMemoryEstimatorTest {
    private val metadata = ModelRuntimeMetadata(
        layerCount = 32,
        embeddingSize = 4096,
        headCountKv = 8,
        keyLength = 128,
        valueLength = 128,
        vocabSize = 32000,
    )
    private val modelSize = 4_000_000_000L

    @Test
    fun `ULTRA estimate is less than AGGRESSIVE`() {
        val aggressive = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelSize,
            metadata = metadata,
            nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.AGGRESSIVE,
            nUbatch = 512,
        )
        val ultra = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelSize,
            metadata = metadata,
            nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.ULTRA,
            nUbatch = 512,
        )
        assertTrue(ultra.kvCacheBytes < aggressive.kvCacheBytes,
            "ULTRA KV cache (${ultra.kvCacheBytes}) should be smaller than AGGRESSIVE (${aggressive.kvCacheBytes})")
    }

    @Test
    fun `EXTREME estimate is less than ULTRA`() {
        val ultra = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelSize,
            metadata = metadata,
            nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.ULTRA,
            nUbatch = 512,
        )
        val extreme = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelSize,
            metadata = metadata,
            nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.EXTREME,
            nUbatch = 512,
        )
        assertTrue(extreme.kvCacheBytes < ultra.kvCacheBytes,
            "EXTREME KV cache (${extreme.kvCacheBytes}) should be smaller than ULTRA (${ultra.kvCacheBytes})")
    }

    @Test
    fun `monotonic ordering SAFE greater than BALANCED greater than AGGRESSIVE greater than ULTRA greater than EXTREME`() {
        val presets = listOf(
            KvCacheMethodPreset.SAFE,
            KvCacheMethodPreset.BALANCED,
            KvCacheMethodPreset.AGGRESSIVE,
            KvCacheMethodPreset.ULTRA,
            KvCacheMethodPreset.EXTREME,
        )
        val estimates = presets.map { preset ->
            RuntimeModelMemoryEstimator.estimate(
                modelFileSizeBytes = modelSize,
                metadata = metadata,
                nCtx = 2048,
                kvCacheMethod = KvCacheMethod.TURBOQUANT,
                kvCacheMethodPreset = preset,
                nUbatch = 512,
            ).kvCacheBytes
        }
        estimates.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue(a > b, "${presets[i].name} KV ($a) should be > ${presets[i+1].name} KV ($b)")
        }
    }

    @Test
    fun `ULTRA has rotation overhead but lower total than SAFE`() {
        val safe = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelSize,
            metadata = metadata,
            nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.SAFE,
            nUbatch = 512,
        )
        val ultra = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = modelSize,
            metadata = metadata,
            nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.ULTRA,
            nUbatch = 512,
        )
        assertTrue(ultra.estimatedBytes < safe.estimatedBytes,
            "ULTRA total (${ultra.estimatedBytes}) should be less than SAFE total (${safe.estimatedBytes})")
    }

    @Test
    fun `small model ULTRA estimate matches BALANCED due to safety clamp`() {
        val smallModelSize = 1_000_000_000L // 1GB
        val ultra = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = smallModelSize, metadata = metadata, nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.ULTRA, nUbatch = 512,
        )
        val balanced = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = smallModelSize, metadata = metadata, nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.BALANCED, nUbatch = 512,
        )
        assertEquals(balanced.kvCacheBytes, ultra.kvCacheBytes,
            "Small model ULTRA should clamp to BALANCED rates (Q8_0/Q8_0)")
    }

    @Test
    fun `small model EXTREME estimate matches AGGRESSIVE due to safety clamp`() {
        val smallModelSize = 1_000_000_000L
        val extreme = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = smallModelSize, metadata = metadata, nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.EXTREME, nUbatch = 512,
        )
        val aggressive = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = smallModelSize, metadata = metadata, nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.AGGRESSIVE, nUbatch = 512,
        )
        assertEquals(aggressive.kvCacheBytes, extreme.kvCacheBytes,
            "Small model EXTREME should clamp to AGGRESSIVE rates (Q8_0/Q4_0)")
    }

    @Test
    fun `large model ULTRA is less than AGGRESSIVE`() {
        val largeModelSize = 4_000_000_000L
        val ultra = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = largeModelSize, metadata = metadata, nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.ULTRA, nUbatch = 512,
        )
        val aggressive = RuntimeModelMemoryEstimator.estimate(
            modelFileSizeBytes = largeModelSize, metadata = metadata, nCtx = 2048,
            kvCacheMethod = KvCacheMethod.TURBOQUANT,
            kvCacheMethodPreset = KvCacheMethodPreset.AGGRESSIVE, nUbatch = 512,
        )
        assertTrue(ultra.kvCacheBytes < aggressive.kvCacheBytes)
    }
}
