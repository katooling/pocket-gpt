package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.ui.state.ChatUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChatStartupProbeOrchestrator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeGateway: ChatRuntimeService,
    private val startupFlow: ChatStartupFlow,
    private val startupReadinessCoordinator: StartupReadinessCoordinator,
    private val updateState: ((ChatUiState) -> ChatUiState) -> Unit,
    private val onPersist: () -> Unit,
    private val onProbeApplied: () -> Unit,
    private val log: (phase: String, probeToken: Long, detail: String?, error: Throwable?) -> Unit,
) {
    private var startupProbeJob: Job? = null
    private var latestStartupProbeToken: Long = 0L
    private var inFlightStartupProbeDetail: String? = null

    fun launch(statusDetailOverride: String? = null) {
        val normalizedDetail = statusDetailOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (startupProbeJob?.isActive == true && inFlightStartupProbeDetail == normalizedDetail) {
            log("coalesced", latestStartupProbeToken, normalizedDetail, null)
            return
        }
        val probeToken = nextStartupProbeToken(normalizedDetail)
        startupProbeJob?.cancel()
        startupProbeJob = scope.launch(ioDispatcher) {
            log("started", probeToken, normalizedDetail, null)
            try {
                updateState { state -> startupFlow.markProbeRunning(state) }
                val outcome = try {
                    startupFlow.evaluateStartup(statusDetailOverride = normalizedDetail)
                } catch (error: RuntimeException) {
                    if (error is CancellationException) {
                        throw error
                    }
                    log("failed", probeToken, normalizedDetail, error)
                    buildFallbackProbeOutcome(error)
                }
                if (!isLatestStartupProbe(probeToken)) {
                    log("stale_discarded", probeToken, normalizedDetail, null)
                    return@launch
                }
                updateState { state -> startupFlow.applyProbeOutcome(state, outcome) }
                onProbeApplied()
                onPersist()
                log("completed", probeToken, normalizedDetail, null)
            } catch (cancelled: CancellationException) {
                if (isLatestStartupProbe(probeToken)) {
                    updateState { state ->
                        val runtime = state.runtime
                        if (runtime.startupProbeState == com.pocketagent.android.ui.state.StartupProbeState.RUNNING) {
                            state.copy(
                                runtime = runtime.copy(
                                    startupProbeState = com.pocketagent.android.ui.state.StartupProbeState.IDLE,
                                    modelRuntimeStatus = if (runtime.modelRuntimeStatus == com.pocketagent.android.ui.state.ModelRuntimeStatus.LOADING) {
                                        com.pocketagent.android.ui.state.ModelRuntimeStatus.NOT_READY
                                    } else {
                                        runtime.modelRuntimeStatus
                                    },
                                ),
                            )
                        } else {
                            state
                        }
                    }
                }
                log("cancelled", probeToken, normalizedDetail, cancelled)
                throw cancelled
            } finally {
                if (isLatestStartupProbe(probeToken)) {
                    inFlightStartupProbeDetail = null
                }
            }
        }
    }

    fun cancel() {
        startupProbeJob?.cancel()
        startupProbeJob = null
    }

    private fun nextStartupProbeToken(statusDetailOverride: String?): Long {
        val next = latestStartupProbeToken + 1L
        latestStartupProbeToken = next
        inFlightStartupProbeDetail = statusDetailOverride
        return next
    }

    private fun isLatestStartupProbe(probeToken: Long): Boolean = latestStartupProbeToken == probeToken

    private fun buildFallbackProbeOutcome(error: Throwable): StartupProbeOutcome {
        val fallbackCheck = "Startup checks failed unexpectedly: ${error.message ?: error::class.simpleName.orEmpty()}"
        val runtimeBackend = runtimeGateway.runtimeBackend()
        val gpuProbe = runCatching { runtimeGateway.gpuOffloadStatus() }.getOrElse {
            com.pocketagent.android.runtime.GpuProbeResult(
                status = com.pocketagent.android.runtime.GpuProbeStatus.FAILED,
                failureReason = com.pocketagent.android.runtime.GpuProbeFailureReason.UNKNOWN,
                detail = "fallback_probe_status_failed:${it.message ?: it::class.simpleName}",
            )
        }
        return StartupProbeOutcome(
            startupChecks = listOf(fallbackCheck),
            runtimeBackend = runtimeBackend,
            gpuProbeResult = gpuProbe,
            readinessDecision = startupReadinessCoordinator.decide(
                startupChecks = listOf(fallbackCheck),
                runtimeBackend = runtimeBackend,
                statusDetailOverride = null,
            ),
        )
    }
}
