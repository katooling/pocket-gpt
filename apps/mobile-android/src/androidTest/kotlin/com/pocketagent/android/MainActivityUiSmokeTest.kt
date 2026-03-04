package com.pocketagent.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.android.ui.runtime.ChatStreamEvent
import com.pocketagent.android.ui.runtime.MvpRuntimeFacade
import com.pocketagent.android.ui.runtime.StreamUserMessageRequest
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiSmokeTest {
    @Suppress("unused")
    private val installFakeRuntimeFactory: () -> com.pocketagent.android.ui.runtime.MvpRuntimeFacade =
        { FakeRuntimeFacade() }.also { AppRuntimeDependencies.runtimeFacadeFactory = it }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext.getSharedPreferences("pocketagent_chat_state", 0).edit().clear().apply()
    }

    @After
    fun tearDown() {
        AppRuntimeDependencies.runtimeFacadeFactory = { com.pocketagent.android.ui.runtime.DefaultMvpRuntimeFacade() }
    }

    @Test
    fun launchShowsComposerAndOfflineIndicator() {
        composeRule.onNodeWithTag("offline_indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_input").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertIsDisplayed()
    }

    @Test
    fun sendMessageShowsUserAndAssistantBubbles() {
        composeRule.onNodeWithTag("composer_input").performTextInput("hello ui")
        composeRule.onNodeWithTag("send_button").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("hello ui").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("runtime response for hello ui").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("hello ui").assertIsDisplayed()
        composeRule.onNodeWithText("runtime response for hello ui").assertIsDisplayed()
    }
}

private class FakeRuntimeFacade : MvpRuntimeFacade {
    private var sessionCounter = 0
    private var mode: RoutingMode = RoutingMode.AUTO

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("fake-session-$sessionCounter")
    }

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Token("runtime ", "runtime"))
        emit(ChatStreamEvent.Token("response ", "runtime response"))
        emit(
            ChatStreamEvent.Completed(
                response = com.pocketagent.android.ChatResponse(
                    sessionId = request.sessionId,
                    modelId = when (mode) {
                        RoutingMode.AUTO -> "auto"
                        RoutingMode.QWEN_0_8B -> "qwen-0.8b"
                        RoutingMode.QWEN_2B -> "qwen-2b"
                    },
                    text = "runtime response for ${request.userText}",
                    firstTokenLatencyMs = 42,
                    totalLatencyMs = 85,
                ),
            ),
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String {
        return "tool:$toolName"
    }

    override fun analyzeImage(imagePath: String, prompt: String): String {
        return "image:$imagePath"
    }

    override fun exportDiagnostics(): String {
        return "diag=ok"
    }

    override fun setRoutingMode(mode: RoutingMode) {
        this.mode = mode
    }

    override fun getRoutingMode(): RoutingMode = mode

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        // no-op
    }

    override fun deleteSession(sessionId: SessionId): Boolean = true
}
