package com.pocketagent.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageBubbleComposeContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun userBubbleLongPressOpensEditMenu() {
        composeRule.setContent {
            val clipboard = LocalClipboardManager.current
            MaterialTheme {
                MessageBubble(
                    message = message(role = MessageRole.USER, content = "hello user"),
                    runtimeStatusDetail = null,
                    onEditMessage = {},
                    onRegenerateMessage = {},
                    onCopiedToClipboard = {},
                    clipboardManager = clipboard,
                    modifier = Modifier.testTag("user_bubble"),
                )
            }
        }

        composeRule.onNodeWithText("hello user")
            .performTouchInput { longClick(center) }

        composeRule.onNodeWithText("Edit").assertIsDisplayed()
    }

    @Test
    fun assistantBubbleLongPressDoesNotExposeEditMenu() {
        composeRule.setContent {
            val clipboard = LocalClipboardManager.current
            MaterialTheme {
                MessageBubble(
                    message = message(role = MessageRole.ASSISTANT, content = "hello assistant"),
                    runtimeStatusDetail = null,
                    onEditMessage = {},
                    onRegenerateMessage = {},
                    onCopiedToClipboard = {},
                    clipboardManager = clipboard,
                    modifier = Modifier.testTag("assistant_bubble"),
                )
            }
        }

        composeRule.onNodeWithText("hello assistant")
            .performTouchInput { longClick(center) }

        assertTrue(composeRule.onAllNodesWithText("Edit").fetchSemanticsNodes().isEmpty())
    }

    private fun message(
        role: MessageRole,
        content: String,
    ): MessageUiModel {
        return MessageUiModel(
            id = "$role-$content",
            role = role,
            content = content,
            timestampEpochMs = 1L,
            kind = MessageKind.TEXT,
        )
    }
}
