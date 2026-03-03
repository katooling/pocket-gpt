package com.pocketagent.core

interface PolicyModule {
    fun isNetworkAllowedForAction(action: String): Boolean
    fun getRetentionWindowDays(): Int
    fun enforceDataBoundary(eventType: String): Boolean
}

interface ObservabilityModule {
    fun recordLatencyMetric(name: String, valueMs: Double)
    fun recordThermalSnapshot(level: Int)
    fun exportLocalDiagnostics(): String
}
