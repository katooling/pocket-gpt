package com.pocketagent.android.runtime

internal class LlamaRuntimeExecutionGate {
    private val lock = Any()
    private var generationActive: Boolean = false
    private var probeActive: Boolean = false
    private var nonStreamingCount: Int = 0

    fun isBusyForConfig(): Boolean = synchronized(lock) { generationActive || probeActive }

    fun tryBeginNonStreaming(): Boolean = synchronized(lock) {
        if (generationActive || probeActive) {
            return@synchronized false
        }
        nonStreamingCount += 1
        true
    }

    fun endNonStreaming() {
        synchronized(lock) {
            if (nonStreamingCount > 0) {
                nonStreamingCount -= 1
            }
        }
    }

    fun tryBeginGeneration(): Boolean = synchronized(lock) {
        if (generationActive || probeActive || nonStreamingCount > 0) {
            return@synchronized false
        }
        generationActive = true
        true
    }

    fun endGeneration() {
        synchronized(lock) {
            generationActive = false
        }
    }

    fun tryBeginProbe(): Boolean = synchronized(lock) {
        if (generationActive || probeActive || nonStreamingCount > 0) {
            return@synchronized false
        }
        probeActive = true
        true
    }

    fun endProbe() {
        synchronized(lock) {
            probeActive = false
        }
    }
}
