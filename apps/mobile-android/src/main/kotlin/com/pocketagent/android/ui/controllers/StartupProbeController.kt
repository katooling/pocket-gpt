package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

open class StartupProbeController {
    open suspend fun runStartupChecks(
        runtimeGateway: RuntimeGateway,
        ioDispatcher: CoroutineDispatcher,
        timeoutMs: Long,
    ): List<String> {
        return try {
            withTimeout(timeoutMs) {
                runInterruptible(ioDispatcher) { runtimeGateway.runStartupChecks() }
            }
        } catch (_: TimeoutCancellationException) {
            val timeoutSeconds = (timeoutMs / 1000L).coerceAtLeast(1L)
            listOf("Startup checks timed out after ${timeoutSeconds}s.")
        }
    }
}
