package com.pocketagent.android

import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.WarmupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

internal class RuntimeWarmupOrchestrator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val warmupTimeoutMs: Long = DEFAULT_WARMUP_TIMEOUT_MS,
    private val logger: (String) -> Unit,
) {
    private val lock = Any()
    private var activeWarmupJob: Job? = null

    fun scheduleWarmupIfSupported(warmupSupport: RuntimeWarmupSupport?) {
        if (warmupSupport == null) {
            return
        }
        synchronized(lock) {
            activeWarmupJob?.cancel()
            activeWarmupJob = scope.launch(dispatcher) {
                try {
                    val result = withTimeout(warmupTimeoutMs) {
                        runInterruptible(dispatcher) { warmupSupport.warmupActiveModel() }
                    }
                    logger(formatSuccess(result))
                } catch (_: TimeoutCancellationException) {
                    logger("WARMUP|failed|reason=timeout|timeout_ms=$warmupTimeoutMs")
                } catch (_: CancellationException) {
                    logger("WARMUP|cancelled")
                } catch (error: RuntimeException) {
                    logger("WARMUP|failed|reason=${error.message ?: error::class.simpleName}")
                }
            }
        }
    }

    fun cancelActiveWarmup() {
        synchronized(lock) {
            activeWarmupJob?.cancel()
            activeWarmupJob = null
        }
    }

    fun shutdown() {
        cancelActiveWarmup()
        scope.cancel()
    }

    private fun formatSuccess(result: WarmupResult): String {
        return "WARMUP|attempted=${result.attempted}|warmed=${result.warmed}|resident_hit=${result.residentHit}|" +
            "load_ms=${result.loadDurationMs ?: -1}|warmup_ms=${result.warmupDurationMs ?: -1}|" +
            "speculative_path=${result.speculativePath}|error=${result.errorCode ?: "none"}"
    }

    private companion object {
        private const val DEFAULT_WARMUP_TIMEOUT_MS = 120_000L
    }
}

