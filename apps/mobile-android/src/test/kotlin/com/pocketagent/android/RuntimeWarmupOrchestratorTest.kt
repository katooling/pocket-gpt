package com.pocketagent.android

import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.WarmupResult
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeWarmupOrchestratorTest {
    @Test
    fun `scheduling a new warmup cancels the previous job`() = runBlocking {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val orchestrator = RuntimeWarmupOrchestrator(
            scope = scope,
            dispatcher = Dispatchers.Default,
            warmupTimeoutMs = 5_000L,
            logger = { logs += it },
        )
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val secondRan = AtomicBoolean(false)
        val first = object : RuntimeWarmupSupport {
            override fun warmupActiveModel(): WarmupResult {
                started.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    interrupted.set(true)
                    throw RuntimeException("interrupted")
                }
                return WarmupResult.skipped("unexpected")
            }
        }
        val second = object : RuntimeWarmupSupport {
            override fun warmupActiveModel(): WarmupResult {
                secondRan.set(true)
                return WarmupResult(
                    attempted = true,
                    warmed = true,
                    residentHit = false,
                )
            }
        }

        orchestrator.scheduleWarmupIfSupported(first)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        orchestrator.scheduleWarmupIfSupported(second)

        withTimeout(2_000L) {
            while (!secondRan.get()) {
                delay(10L)
            }
        }

        withTimeout(2_000L) {
            while (!interrupted.get() && logs.none {
                it.contains("WARMUP|cancelled") || it.contains("WARMUP|failed|reason=interrupted")
            }) {
                delay(10L)
            }
        }

        assertTrue(interrupted.get() || logs.any {
            it.contains("WARMUP|cancelled") || it.contains("WARMUP|failed|reason=interrupted")
        })
        orchestrator.shutdown()
    }

    @Test
    fun `warmup timeout is logged with deterministic reason`() = runBlocking {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val orchestrator = RuntimeWarmupOrchestrator(
            scope = scope,
            dispatcher = Dispatchers.Default,
            warmupTimeoutMs = 25L,
            logger = { logs += it },
        )
        val blockingWarmup = object : RuntimeWarmupSupport {
            override fun warmupActiveModel(): WarmupResult {
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    // Expected when timeout cancellation interrupts the warmup worker thread.
                }
                return WarmupResult(
                    attempted = true,
                    warmed = true,
                    residentHit = false,
                )
            }
        }

        orchestrator.scheduleWarmupIfSupported(blockingWarmup)

        withTimeout(2_000L) {
            while (logs.none { it.contains("WARMUP|failed|reason=timeout|timeout_ms=25") }) {
                delay(10L)
            }
        }

        assertTrue(logs.any { it.contains("WARMUP|failed|reason=timeout|timeout_ms=25") })
        orchestrator.shutdown()
    }
}
