package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.PersistedChatStateCodec
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PersistenceQueueMetrics(
    val writeCount: Int = 0,
    val lastPersistDurationMs: Long = 0L,
    val medianPersistDurationMs: Long = 0L,
    val lastPayloadBytes: Int = 0,
    val medianPayloadBytes: Int = 0,
)

class ChatPersistenceQueue(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val toPersistedState: (ChatUiState) -> PersistedChatState,
    private val savePersistedState: (PersistedChatState) -> Unit,
    private val debounceMs: Long = 120L,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val onMetrics: (PersistenceQueueMetrics) -> Unit = {},
) {
    private val lock = Any()
    private var workerJob: Job? = null
    private var pendingState: ChatUiState? = null
    private var lastSaved: PersistedChatState? = null
    private var writes: Int = 0
    private val recentDurationsMs = ArrayDeque<Long>()
    private val recentPayloadBytes = ArrayDeque<Int>()
    private var metrics: PersistenceQueueMetrics = PersistenceQueueMetrics()

    fun enqueue(state: ChatUiState) {
        synchronized(lock) {
            pendingState = state
            if (workerJob?.isActive == true) {
                return
            }
            workerJob = scope.launch(ioDispatcher) { runWorker() }
        }
    }

    fun metricsSnapshot(): PersistenceQueueMetrics = synchronized(lock) { metrics }

    fun close() {
        synchronized(lock) {
            workerJob?.cancel()
            workerJob = null
            pendingState = null
        }
    }

    private suspend fun runWorker() {
        while (scope.isActive) {
            delay(debounceMs)
            val state = synchronized(lock) {
                val snapshot = pendingState
                pendingState = null
                snapshot
            } ?: break

            val persisted = toPersistedState(state)
            if (persisted == lastSaved) {
                if (!hasPendingState()) {
                    break
                }
                continue
            }

            val payloadBytes = PersistedChatStateCodec.encode(persisted).toByteArray(StandardCharsets.UTF_8).size
            val started = clockMs()
            savePersistedState(persisted)
            val durationMs = (clockMs() - started).coerceAtLeast(0L)
            lastSaved = persisted
            recordMetrics(durationMs = durationMs, payloadBytes = payloadBytes)

            if (!hasPendingState()) {
                break
            }
        }
        synchronized(lock) {
            workerJob = null
        }
    }

    private fun hasPendingState(): Boolean = synchronized(lock) { pendingState != null }

    private fun recordMetrics(durationMs: Long, payloadBytes: Int) {
        val snapshot = synchronized(lock) {
            writes += 1
            recentDurationsMs.addLast(durationMs)
            recentPayloadBytes.addLast(payloadBytes)
            while (recentDurationsMs.size > METRICS_WINDOW_SIZE) {
                recentDurationsMs.removeFirst()
            }
            while (recentPayloadBytes.size > METRICS_WINDOW_SIZE) {
                recentPayloadBytes.removeFirst()
            }
            val next = PersistenceQueueMetrics(
                writeCount = writes,
                lastPersistDurationMs = durationMs,
                medianPersistDurationMs = medianLong(recentDurationsMs),
                lastPayloadBytes = payloadBytes,
                medianPayloadBytes = medianInt(recentPayloadBytes),
            )
            metrics = next
            next
        }
        onMetrics(snapshot)
    }

    private fun medianLong(values: Collection<Long>): Long {
        if (values.isEmpty()) {
            return 0L
        }
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianInt(values: Collection<Int>): Int {
        if (values.isEmpty()) {
            return 0
        }
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        private const val METRICS_WINDOW_SIZE = 64
    }
}

