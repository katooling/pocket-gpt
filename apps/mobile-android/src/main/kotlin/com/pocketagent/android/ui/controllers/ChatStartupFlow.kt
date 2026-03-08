package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeGateway
import com.pocketagent.android.ui.addTelemetryEventIfMissing
import com.pocketagent.android.ui.clearError
import com.pocketagent.android.ui.withUiError
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlinx.coroutines.CoroutineDispatcher

data class StartupBootstrapResult(
    val state: ChatUiState,
    val shouldRunStartupProbe: Boolean,
    val shouldPersist: Boolean,
)

data class StartupProbeOutcome(
    val startupChecks: List<String>,
    val runtimeBackend: String?,
    val gpuSupported: Boolean,
    val readinessDecision: StartupReadinessDecision,
)

class ChatStartupFlow(
    private val runtimeGateway: RuntimeGateway,
    private val startupProbeController: StartupProbeController,
    private val startupReadinessCoordinator: StartupReadinessCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeStartupProbeTimeoutMs: Long,
    private val nativeRuntimeLibraryPackaged: Boolean,
) {
    fun bootstrap(loadedState: PersistenceBootstrapState): StartupBootstrapResult {
        val persisted = loadedState.persisted
        val loadError = loadedState.loadError
        val runtimeBackend = runtimeGateway.runtimeBackend()

        val restoredRoutingMode = RoutingMode.valueOf(persisted.routingMode)
        val restoredPerformanceProfile = RuntimePerformanceProfile.valueOf(persisted.performanceProfile)
        val restoredFirstSessionStage = FirstSessionStage.valueOf(persisted.firstSessionStage)
        val restoredAdvancedUnlocked = true
        val initialFirstSessionStage = when {
            !persisted.onboardingCompleted -> FirstSessionStage.ONBOARDING
            restoredFirstSessionStage == FirstSessionStage.ONBOARDING -> FirstSessionStage.GET_READY
            else -> restoredFirstSessionStage
        }
        val gpuSupported = runtimeGateway.supportsGpuOffload()
        val restoredGpuEnabled = persisted.gpuAccelerationEnabled && gpuSupported
        runtimeGateway.setRoutingMode(restoredRoutingMode)

        val bootstrapRuntimeState = if (loadError == null) {
            RuntimeUiState(
                routingMode = restoredRoutingMode,
                performanceProfile = restoredPerformanceProfile,
                gpuAccelerationEnabled = restoredGpuEnabled,
                gpuAccelerationSupported = gpuSupported,
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.RUNNING,
                modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                modelStatusDetail = "Warming model and running startup checks...",
            ).clearError()
        } else {
            RuntimeUiState(
                routingMode = restoredRoutingMode,
                performanceProfile = restoredPerformanceProfile,
                gpuAccelerationEnabled = restoredGpuEnabled,
                gpuAccelerationSupported = gpuSupported,
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.BLOCKED,
                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                modelStatusDetail = loadError.userMessage,
                startupChecks = listOf(loadError.technicalDetail ?: loadError.userMessage),
            ).withUiError(loadError)
        }

        val restoredSessions = persisted.sessions.map { session ->
            val turns = session.messages
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                .map { message ->
                    Turn(
                        role = if (message.role == MessageRole.USER) "user" else "assistant",
                        content = message.content,
                        timestampEpochMs = message.timestampEpochMs,
                    )
                }
            runtimeGateway.restoreSession(sessionId = SessionId(session.id), turns = turns)
            session
        }

        if (restoredSessions.isEmpty()) {
            val newSessionId = runtimeGateway.createSession().value
            val now = System.currentTimeMillis()
            return StartupBootstrapResult(
                state = ChatUiState(
                    sessions = listOf(
                        ChatSessionUiModel(
                            id = newSessionId,
                            title = "New chat",
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now,
                            messages = emptyList(),
                        ),
                    ),
                    activeSessionId = newSessionId,
                    runtime = bootstrapRuntimeState,
                    showOnboarding = !persisted.onboardingCompleted,
                    firstSessionStage = initialFirstSessionStage,
                    advancedUnlocked = restoredAdvancedUnlocked,
                    firstAnswerCompleted = persisted.firstAnswerCompleted,
                    followUpCompleted = persisted.followUpCompleted,
                    firstSessionTelemetryEvents = persisted.firstSessionTelemetryEvents,
                ),
                shouldRunStartupProbe = loadedState.shouldRunStartupProbe,
                shouldPersist = true,
            )
        }

        val activeSessionId = persisted.activeSessionId
            ?.takeIf { id -> restoredSessions.any { it.id == id } }
            ?: restoredSessions.last().id
        return StartupBootstrapResult(
            state = ChatUiState(
                sessions = restoredSessions,
                activeSessionId = activeSessionId,
                runtime = bootstrapRuntimeState,
                showOnboarding = !persisted.onboardingCompleted,
                firstSessionStage = initialFirstSessionStage,
                advancedUnlocked = restoredAdvancedUnlocked,
                firstAnswerCompleted = persisted.firstAnswerCompleted,
                followUpCompleted = persisted.followUpCompleted,
                firstSessionTelemetryEvents = persisted.firstSessionTelemetryEvents,
            ),
            shouldRunStartupProbe = loadedState.shouldRunStartupProbe,
            shouldPersist = false,
        )
    }

    fun markProbeRunning(state: ChatUiState): ChatUiState {
        return state.copy(
            runtime = state.runtime.copy(
                startupProbeState = StartupProbeState.RUNNING,
                modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                modelStatusDetail = "Warming model and running startup checks...",
            ).clearError(),
        )
    }

    suspend fun evaluateStartup(statusDetailOverride: String?): StartupProbeOutcome {
        val startupChecks = if (!nativeRuntimeLibraryPackaged) {
            listOf(MISSING_NATIVE_RUNTIME_BUILD_CHECK)
        } else {
            startupProbeController.runStartupChecks(
                runtimeGateway = runtimeGateway,
                ioDispatcher = ioDispatcher,
                timeoutMs = runtimeStartupProbeTimeoutMs,
            )
        }
        val runtimeBackend = runtimeGateway.runtimeBackend()
        return StartupProbeOutcome(
            startupChecks = startupChecks,
            runtimeBackend = runtimeBackend,
            gpuSupported = runtimeGateway.supportsGpuOffload(),
            readinessDecision = startupReadinessCoordinator.decide(
                startupChecks = startupChecks,
                runtimeBackend = runtimeBackend,
                statusDetailOverride = statusDetailOverride,
            ),
        )
    }

    fun applyProbeOutcome(
        state: ChatUiState,
        outcome: StartupProbeOutcome,
    ): ChatUiState {
        val nextRuntime = state.runtime.copy(
            runtimeBackend = outcome.runtimeBackend,
            gpuAccelerationSupported = outcome.gpuSupported,
            gpuAccelerationEnabled = state.runtime.gpuAccelerationEnabled && outcome.gpuSupported,
            startupProbeState = outcome.readinessDecision.startupProbeState,
            modelRuntimeStatus = outcome.readinessDecision.modelRuntimeStatus,
            modelStatusDetail = outcome.readinessDecision.modelStatusDetail,
            startupChecks = outcome.startupChecks,
            startupWarnings = outcome.readinessDecision.startupWarnings,
        ).withUiError(outcome.readinessDecision.startupError)
        val sendAllowed = outcome.readinessDecision.startupProbeState == StartupProbeState.READY
        val blocked = outcome.readinessDecision.startupProbeState == StartupProbeState.BLOCKED ||
            outcome.readinessDecision.startupProbeState == StartupProbeState.BLOCKED_TIMEOUT
        val nextStage = when {
            state.showOnboarding -> FirstSessionStage.ONBOARDING
            state.firstAnswerCompleted -> state.firstSessionStage
            sendAllowed -> FirstSessionStage.READY_TO_CHAT
            blocked -> FirstSessionStage.GET_READY
            else -> state.firstSessionStage
        }
        val completedGetReadyNow = state.firstSessionStage == FirstSessionStage.GET_READY &&
            nextStage == FirstSessionStage.READY_TO_CHAT
        val telemetry = if (completedGetReadyNow) {
            addTelemetryEventIfMissing(
                events = state.firstSessionTelemetryEvents,
                eventName = TELEMETRY_EVENT_GET_READY_COMPLETED,
            )
        } else {
            state.firstSessionTelemetryEvents
        }
        return state.copy(
            runtime = nextRuntime,
            firstSessionStage = nextStage,
            firstSessionTelemetryEvents = telemetry,
        )
    }

    fun startupBlockError(runtime: RuntimeUiState): com.pocketagent.android.ui.state.UiError {
        val checks = runtime.startupChecks.ifEmpty {
            listOf(runtime.modelStatusDetail ?: "Runtime startup checks are still running.")
        }
        return com.pocketagent.android.ui.state.UiErrorMapper.startupFailure(checks)
            ?: com.pocketagent.android.ui.state.UiErrorMapper.runtimeFailure(
                runtime.modelStatusDetail ?: "Runtime is not ready yet.",
            )
    }

    fun readyStatusDetail(runtimeBackend: String?): String {
        return if (runtimeBackend.isNullOrBlank()) {
            "Runtime model ready"
        } else {
            "Runtime model ready ($runtimeBackend)"
        }
    }

    private companion object {
        private const val TELEMETRY_EVENT_GET_READY_COMPLETED = "get_ready_completed"
        private const val MISSING_NATIVE_RUNTIME_BUILD_CHECK =
            "Build is missing native runtime library (libpocket_llama.so). " +
                "Install an app build that packages native runtime."
    }
}
