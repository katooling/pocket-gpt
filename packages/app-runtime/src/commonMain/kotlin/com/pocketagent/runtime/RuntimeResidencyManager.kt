package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.RuntimeResidencyState
import java.util.Timer
import java.util.TimerTask

internal data class ResidentRuntimeSlot(
    val modelId: String,
    val slotId: String,
    val keepAliveMs: Long,
    val expiresAtEpochMs: Long,
    val lastTouchedAtEpochMs: Long,
)

internal class RuntimeResidencyManager(
    private val inferenceModule: InferenceModule,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var timer: Timer? = null
    private var residentSlot: ResidentRuntimeSlot? = null
    private var activeRequestCount: Int = 0
    private var lastUnloadReason: String = "none"

    fun ensureLoaded(modelId: String, slotId: String, keepAliveMs: Long): Boolean {
        val safeKeepAliveMs = keepAliveMs.coerceAtLeast(1L)
        val loaded = inferenceModule.loadModel(modelId)
        synchronized(lock) {
            if (loaded) {
                residentSlot = ResidentRuntimeSlot(
                    modelId = modelId,
                    slotId = slotId,
                    keepAliveMs = safeKeepAliveMs,
                    expiresAtEpochMs = nowMs() + safeKeepAliveMs,
                    lastTouchedAtEpochMs = nowMs(),
                )
                updateNativeResidencyLocked()
                if (activeRequestCount == 0) {
                    scheduleExpiryLocked(safeKeepAliveMs)
                } else {
                    cancelTimerLocked()
                }
            }
        }
        return loaded
    }

    fun touch(slotId: String, keepAliveMs: Long): Boolean {
        synchronized(lock) {
            val current = residentSlot ?: return false
            if (current.slotId != slotId) {
                return false
            }
            refreshSlotLocked(current, keepAliveMs.coerceAtLeast(1L))
            return true
        }
    }

    fun touchKeepAlive(keepAliveMs: Long? = null): Boolean {
        synchronized(lock) {
            val current = residentSlot ?: return false
            refreshSlotLocked(current, keepAliveMs?.coerceAtLeast(1L) ?: current.keepAliveMs)
            return true
        }
    }

    fun shortenKeepAlive(maxKeepAliveMs: Long): Boolean {
        synchronized(lock) {
            val current = residentSlot ?: return false
            val nextKeepAliveMs = minOf(current.keepAliveMs, maxKeepAliveMs.coerceAtLeast(1L))
            refreshSlotLocked(current, nextKeepAliveMs)
            return true
        }
    }

    fun listResident(): List<ResidentRuntimeSlot> {
        synchronized(lock) {
            return residentSlot?.let(::listOf) ?: emptyList()
        }
    }

    fun unload(reason: String): Boolean {
        cancelAndClear(reason)
        inferenceModule.unloadModel()
        return true
    }

    fun onTrimMemory(level: Int): Boolean {
        return when {
            level >= TRIM_MEMORY_RUNNING_CRITICAL -> unload(reason = "trim_memory_$level")
            level >= TRIM_MEMORY_RUNNING_LOW -> shortenKeepAlive(15_000L)
            level >= TRIM_MEMORY_RUNNING_MODERATE -> shortenKeepAlive(60_000L)
            level >= TRIM_MEMORY_UI_HIDDEN -> shortenKeepAlive(120_000L)
            else -> false
        }
    }

    fun onAppBackground(): Boolean = unload(reason = "app_background")

    fun onGenerationStarted() {
        synchronized(lock) {
            activeRequestCount += 1
            cancelTimerLocked()
        }
    }

    fun onGenerationFinished(slotId: String?, keepAliveMs: Long?) {
        synchronized(lock) {
            activeRequestCount = (activeRequestCount - 1).coerceAtLeast(0)
            if (slotId != null && keepAliveMs != null) {
                val current = residentSlot
                if (current != null && current.slotId == slotId) {
                    refreshSlotLocked(current, keepAliveMs.coerceAtLeast(1L))
                }
            }
        }
    }

    fun queueDepth(): Int {
        synchronized(lock) {
            return activeRequestCount
        }
    }

    fun diagnosticsLine(residencyState: RuntimeResidencyState?): String {
        val slot = synchronized(lock) { residentSlot }
        val residentModel = slot?.modelId ?: residencyState?.key?.modelId.orEmpty()
        val slotId = slot?.slotId.orEmpty()
        val expiresAt = slot?.expiresAtEpochMs?.toString().orEmpty()
        val reloadReason = residencyState?.reloadReason?.name?.lowercase().orEmpty()
        val loadMs = residencyState?.lastLoadDurationMs?.toString().orEmpty()
        val warmupMs = residencyState?.lastWarmupDurationMs?.toString().orEmpty()
        val queueDepth = queueDepth()
        return buildString {
            append("RUNTIME_RESIDENCY")
            append("|resident_model=")
            append(if (residentModel.isNotBlank()) residentModel else "none")
            append("|slot_id=")
            append(if (slotId.isNotBlank()) slotId else "none")
            append("|expires_at=")
            append(if (expiresAt.isNotBlank()) expiresAt else "none")
            append("|last_load_ms=")
            append(if (loadMs.isNotBlank()) loadMs else "none")
            append("|last_warmup_ms=")
            append(if (warmupMs.isNotBlank()) warmupMs else "none")
            append("|reload_reason=")
            append(if (reloadReason.isNotBlank()) reloadReason else "none")
            append("|queue_depth=")
            append(queueDepth)
            append("|last_unload_reason=")
            append(lastUnloadReason)
        }
    }

    private fun refreshSlotLocked(current: ResidentRuntimeSlot, keepAliveMs: Long) {
        residentSlot = current.copy(
            keepAliveMs = keepAliveMs,
            expiresAtEpochMs = nowMs() + keepAliveMs,
            lastTouchedAtEpochMs = nowMs(),
        )
        updateNativeResidencyLocked()
        if (activeRequestCount == 0) {
            scheduleExpiryLocked(keepAliveMs)
        }
    }

    private fun cancelAndClear(reason: String) {
        synchronized(lock) {
            lastUnloadReason = sanitizeReason(reason)
            cancelTimerLocked()
            residentSlot = null
            updateNativeResidencyLocked()
        }
    }

    private fun scheduleExpiryLocked(delayMs: Long) {
        cancelTimerLocked()
        val current = residentSlot ?: return
        val safeDelay = delayMs.coerceAtLeast(1L)
        timer = Timer("runtime-residency-expiry", true).also { ttlTimer ->
            ttlTimer.schedule(
                object : TimerTask() {
                    override fun run() {
                        synchronized(lock) {
                            if (activeRequestCount > 0) {
                                scheduleExpiryLocked(safeDelay)
                                return
                            }
                            if (residentSlot?.slotId != current.slotId) {
                                return
                            }
                            residentSlot = null
                            lastUnloadReason = "idle_ttl"
                            updateNativeResidencyLocked()
                        }
                        inferenceModule.unloadModel()
                    }
                },
                safeDelay,
            )
        }
    }

    private fun updateNativeResidencyLocked() {
        (inferenceModule as? LlamaCppInferenceModule)?.updateResidencySlot(
            slotId = residentSlot?.slotId,
            expiresAtEpochMs = residentSlot?.expiresAtEpochMs,
        )
    }

    private fun cancelTimerLocked() {
        timer?.cancel()
        timer = null
    }

    private companion object {
        // Mirror Android ComponentCallbacks2 trim levels so residency policy can run in common code.
        private const val TRIM_MEMORY_UI_HIDDEN = 20
        private const val TRIM_MEMORY_RUNNING_MODERATE = 5
        private const val TRIM_MEMORY_RUNNING_LOW = 10
        private const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    }

    private fun sanitizeReason(reason: String): String {
        return reason.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").ifBlank { "manual" }
    }
}
