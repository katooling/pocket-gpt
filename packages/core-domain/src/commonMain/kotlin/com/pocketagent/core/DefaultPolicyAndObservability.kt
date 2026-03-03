package com.pocketagent.core

class DefaultPolicyModule(
    private val offlineOnly: Boolean = true,
    private val retentionWindowDays: Int = 30,
    private val allowlistedNetworkActions: Set<String> = emptySet(),
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean {
        if (offlineOnly) {
            return false
        }
        return allowlistedNetworkActions.contains(action)
    }

    override fun getRetentionWindowDays(): Int = retentionWindowDays

    override fun enforceDataBoundary(eventType: String): Boolean {
        // Only allow known event families for privacy-safe telemetry.
        return eventType.startsWith("inference.") ||
            eventType.startsWith("routing.") ||
            eventType.startsWith("tool.") ||
            eventType.startsWith("memory.")
    }
}

class InMemoryObservabilityModule : ObservabilityModule {
    private val metrics: MutableMap<String, MutableList<Double>> = mutableMapOf()
    private val thermalSamples: MutableList<Int> = mutableListOf()

    override fun recordLatencyMetric(name: String, valueMs: Double) {
        metrics.getOrPut(name) { mutableListOf() }.add(valueMs)
    }

    override fun recordThermalSnapshot(level: Int) {
        thermalSamples.add(level)
    }

    override fun exportLocalDiagnostics(): String {
        val metricsSection = metrics.entries.joinToString(separator = ";") { entry ->
            val average = if (entry.value.isEmpty()) 0.0 else entry.value.average()
            "${entry.key}=count:${entry.value.size},avg_ms:${"%.2f".format(average)}"
        }
        val thermalSection = "thermal_samples=${thermalSamples.joinToString(",")}"
        return "$metricsSection|$thermalSection"
    }
}
