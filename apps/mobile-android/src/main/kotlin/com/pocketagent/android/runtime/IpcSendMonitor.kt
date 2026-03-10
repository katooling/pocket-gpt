package com.pocketagent.android.runtime

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal enum class IpcMessageKind {
    REPLY,
    STREAM_TOKEN,
    STREAM_RESULT,
}

internal class IpcSendMonitor(
    private val tag: String,
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, error)
        }
    },
) {
    private val failureCounters: ConcurrentHashMap<IpcMessageKind, AtomicInteger> = ConcurrentHashMap()

    fun send(kind: IpcMessageKind, block: () -> Unit): Boolean {
        return runCatching { block() }
            .onFailure { error ->
                val count = failureCounters
                    .computeIfAbsent(kind) { AtomicInteger(0) }
                    .incrementAndGet()
                logger(
                    "IPC_SEND_FAILURE|kind=${kind.name}|count=$count|error=${error.message ?: error::class.simpleName}",
                    error,
                )
            }
            .isSuccess
    }

    fun failureCount(kind: IpcMessageKind): Int {
        return failureCounters[kind]?.get() ?: 0
    }

    fun failureSnapshot(): Map<IpcMessageKind, Int> {
        return failureCounters.mapValues { entry -> entry.value.get() }
    }
}

