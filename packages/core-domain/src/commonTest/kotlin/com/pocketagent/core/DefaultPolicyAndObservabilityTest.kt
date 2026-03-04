package com.pocketagent.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultPolicyAndObservabilityTest {
    @Test
    fun `policy allows expected stage3 event families including observability`() {
        val policy = DefaultPolicyModule()

        assertTrue(policy.enforceDataBoundary("routing.model_select"))
        assertTrue(policy.enforceDataBoundary("inference.generate"))
        assertTrue(policy.enforceDataBoundary("memory.write_user_turn"))
        assertTrue(policy.enforceDataBoundary("tool.execute"))
        assertTrue(policy.enforceDataBoundary("observability.export"))
    }

    @Test
    fun `policy rejects unknown event family`() {
        val policy = DefaultPolicyModule()

        assertFalse(policy.enforceDataBoundary("network.send_payload"))
    }
}
