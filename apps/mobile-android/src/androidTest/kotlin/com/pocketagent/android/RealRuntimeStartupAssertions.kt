package com.pocketagent.android

import org.junit.Assert.assertTrue

private const val OPTIONAL_RUNTIME_WARNING_PREFIX = "Optional runtime model unavailable:"

internal fun assertStartupChecksReadyWithOptionalWarnings(
    startupChecks: List<String>,
    healthyModelIds: Set<String>,
    failurePrefix: String,
) {
    val blockingChecks = startupChecks.filterNot { it.startsWith(OPTIONAL_RUNTIME_WARNING_PREFIX) }
    assertTrue(
        "$failurePrefix: ${startupChecks.joinToString()}",
        blockingChecks.isEmpty(),
    )

    val unexpectedHealthyModelWarnings = startupChecks.filter { warning ->
        warning.startsWith(OPTIONAL_RUNTIME_WARNING_PREFIX) &&
            healthyModelIds.any { modelId -> warning.contains(modelId) }
    }
    assertTrue(
        "Seeded runtime models were reported unavailable: ${unexpectedHealthyModelWarnings.joinToString()}",
        unexpectedHealthyModelWarnings.isEmpty(),
    )
}
