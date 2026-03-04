package com.pocketagent.runtime

import com.pocketagent.core.PolicyModule

data class NetworkPolicyDecision(
    val allowed: Boolean,
    val detail: String,
)

interface PlatformNetworkPolicyProbe {
    fun isInternetPermissionDeclared(): Boolean
    fun isCleartextTrafficPermitted(): Boolean
    fun isNetworkSecurityConfigPresent(): Boolean
}

class StaticPlatformNetworkPolicyProbe(
    private val internetPermissionDeclared: Boolean = false,
    private val cleartextTrafficPermitted: Boolean = false,
    private val networkSecurityConfigPresent: Boolean = true,
) : PlatformNetworkPolicyProbe {
    override fun isInternetPermissionDeclared(): Boolean = internetPermissionDeclared

    override fun isCleartextTrafficPermitted(): Boolean = cleartextTrafficPermitted

    override fun isNetworkSecurityConfigPresent(): Boolean = networkSecurityConfigPresent
}

class PolicyAwareNetworkClient(
    private val policyModule: PolicyModule,
    private val platformProbe: PlatformNetworkPolicyProbe = StaticPlatformNetworkPolicyProbe(),
) {
    fun startupChecks(): List<String> {
        val checks = mutableListOf<String>()
        if (platformProbe.isCleartextTrafficPermitted()) {
            checks.add("Network security config invalid: cleartext traffic must be disabled.")
        }
        if (!platformProbe.isNetworkSecurityConfigPresent()) {
            checks.add("Network security config missing: policy wiring requires a platform config.")
        }
        if (platformProbe.isInternetPermissionDeclared() &&
            !policyModule.isNetworkAllowedForAction("runtime.offline_probe")
        ) {
            checks.add("Network policy wiring invalid: INTERNET permission declared while offline-only policy denies runtime.offline_probe.")
        }
        return checks
    }

    fun enforce(
        action: String,
        request: (() -> String)? = null,
    ): NetworkPolicyDecision {
        if (!policyModule.isNetworkAllowedForAction(action)) {
            return NetworkPolicyDecision(
                allowed = false,
                detail = "NETWORK_POLICY_DENIED:$action",
            )
        }
        if (request == null) {
            return NetworkPolicyDecision(
                allowed = true,
                detail = "NETWORK_POLICY_ALLOWED:$action",
            )
        }
        return runCatching { request() }
            .fold(
                onSuccess = { output ->
                    NetworkPolicyDecision(
                        allowed = true,
                        detail = output,
                    )
                },
                onFailure = { error ->
                    NetworkPolicyDecision(
                        allowed = false,
                        detail = "NETWORK_POLICY_ERROR:$action:${error.message ?: "unknown"}",
                    )
                },
            )
    }
}
