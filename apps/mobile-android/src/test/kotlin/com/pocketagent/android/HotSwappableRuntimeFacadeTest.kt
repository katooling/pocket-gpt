package com.pocketagent.android

import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.StreamChatRequestV2
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HotSwappableRuntimeFacadeTest {
    @Test
    fun `replace waits for in-flight call and subsequent calls use new delegate`() {
        val callStarted = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)
        val first = BlockingFacade(
            sessionId = "session-old",
            callStarted = callStarted,
            releaseCall = releaseCall,
        )
        val second = StubFacade(sessionId = "session-new")
        val hotSwap = HotSwappableRuntimeFacade(first)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val inFlight = executor.submit<SessionId> { hotSwap.createSession() }
            assertTrue(callStarted.await(2, TimeUnit.SECONDS))

            val replaceFuture = executor.submit { hotSwap.replace(second) }
            assertFailsWith<TimeoutException> {
                replaceFuture.get(100, TimeUnit.MILLISECONDS)
            }
            assertFalse(replaceFuture.isDone, "replace should wait until in-flight read call completes")

            releaseCall.countDown()
            assertEquals("session-old", inFlight.get(2, TimeUnit.SECONDS).value)
            replaceFuture.get(2, TimeUnit.SECONDS)

            assertEquals("session-new", hotSwap.createSession().value)
        } finally {
            executor.shutdownNow()
        }
    }
}

private open class StubFacade(
    private val sessionId: String = "session-1",
) : MvpRuntimeFacade {
    private var routingMode: RoutingMode = RoutingMode.AUTO

    override fun createSession(): SessionId = SessionId(sessionId)

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> = emptyFlow()

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): String = "tool:$toolName"

    override fun analyzeImage(imagePath: String, prompt: String): String = "image:$imagePath"

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true
}

private class BlockingFacade(
    sessionId: String,
    private val callStarted: CountDownLatch,
    private val releaseCall: CountDownLatch,
) : StubFacade(sessionId) {
    override fun createSession(): SessionId {
        callStarted.countDown()
        releaseCall.await(2, TimeUnit.SECONDS)
        return super.createSession()
    }
}
