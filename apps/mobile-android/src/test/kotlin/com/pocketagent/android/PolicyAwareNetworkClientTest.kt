package com.pocketagent.android

import com.pocketagent.core.PolicyModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyAwareNetworkClientTest {
    @Test
    fun `enforce denies network action when policy is offline only`() {
        val client = PolicyAwareNetworkClient(
            policyModule = FakePolicyModule(allowlistedNetworkActions = emptySet()),
        )

        val decision = client.enforce("runtime.offline_probe")

        assertFalse(decision.allowed)
        assertEquals("NETWORK_POLICY_DENIED:runtime.offline_probe", decision.detail)
    }

    @Test
    fun `startup checks flag cleartext and missing config`() {
        val client = PolicyAwareNetworkClient(
            policyModule = FakePolicyModule(allowlistedNetworkActions = emptySet()),
            platformProbe = StaticPlatformNetworkPolicyProbe(
                internetPermissionDeclared = false,
                cleartextTrafficPermitted = true,
                networkSecurityConfigPresent = false,
            ),
        )

        val checks = client.startupChecks()

        assertTrue(checks.any { it.contains("cleartext traffic must be disabled") })
        assertTrue(checks.any { it.contains("Network security config missing") })
    }

    @Test
    fun `allowlisted action can execute guarded request`() {
        val client = PolicyAwareNetworkClient(
            policyModule = FakePolicyModule(allowlistedNetworkActions = setOf("policy.health_probe")),
        )

        val decision = client.enforce("policy.health_probe") { "ok" }

        assertTrue(decision.allowed)
        assertEquals("ok", decision.detail)
    }
}

private class FakePolicyModule(
    private val allowlistedNetworkActions: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean {
        return allowlistedNetworkActions.contains(action)
    }

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = true
}
