package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.PersistedToolCall
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.core.Turn
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.InteractionToolCall
import com.pocketagent.runtime.InteractionToolCallStatus

class TimelineProjector {
    fun toTranscript(session: ChatSessionUiModel): List<InteractionMessage> {
        return session.messages.map { message -> message.toInteractionMessage() }
    }

    fun toTurns(session: ChatSessionUiModel): List<Turn> {
        return session.messages.map { message ->
            Turn(
                role = message.role.toTurnRole(),
                content = message.content,
                timestampEpochMs = message.timestampEpochMs,
            )
        }
    }

    fun latestAssistantRequestId(session: ChatSessionUiModel): String? {
        return session.messages
            .asReversed()
            .firstOrNull { message -> message.role == MessageRole.ASSISTANT && !message.requestId.isNullOrBlank() }
            ?.requestId
    }
}

private fun MessageUiModel.toInteractionMessage(): InteractionMessage {
    val interaction = interaction
    val textParts = if (interaction?.parts?.isNotEmpty() == true) {
        interaction.parts.map { part -> part.toContentPart() }
    } else {
        listOf(InteractionContentPart.Text(content))
    }
    val parts = if (kind == MessageKind.IMAGE) {
        val attachmentPaths = imagePaths.ifEmpty { listOfNotNull(imagePath) }
        if (attachmentPaths.isNotEmpty()) {
            val imageParts = attachmentPaths.map { InteractionContentPart.Image(path = it) }
            imageParts + textParts
        } else {
            textParts
        }
    } else {
        textParts
    }
    val toolCalls = if (interaction?.toolCalls?.isNotEmpty() == true) {
        interaction.toolCalls.map { call -> call.toInteractionToolCall() }
    } else {
        emptyList()
    }
    return InteractionMessage(
        id = id,
        role = role.toInteractionRole(),
        parts = parts,
        toolCalls = toolCalls,
        toolCallId = interaction?.toolCallId,
        metadata = interaction?.metadata ?: emptyMap(),
    )
}

private fun PersistedInteractionPart.toContentPart(): InteractionContentPart {
    return InteractionContentPart.Text(text = text.orEmpty())
}

private fun PersistedToolCall.toInteractionToolCall(): InteractionToolCall {
    return InteractionToolCall(
        id = id,
        name = name,
        argumentsJson = argumentsJson,
        status = status.toInteractionStatus(),
    )
}

private fun PersistedToolCallStatus.toInteractionStatus(): InteractionToolCallStatus {
    return when (this) {
        PersistedToolCallStatus.PENDING -> InteractionToolCallStatus.PENDING
        PersistedToolCallStatus.RUNNING -> InteractionToolCallStatus.RUNNING
        PersistedToolCallStatus.COMPLETED -> InteractionToolCallStatus.COMPLETED
        PersistedToolCallStatus.FAILED -> InteractionToolCallStatus.FAILED
    }
}

private fun MessageRole.toInteractionRole(): InteractionRole {
    return when (this) {
        MessageRole.USER -> InteractionRole.USER
        MessageRole.ASSISTANT -> InteractionRole.ASSISTANT
        MessageRole.TOOL -> InteractionRole.TOOL
        MessageRole.SYSTEM -> InteractionRole.SYSTEM
    }
}

private fun MessageRole.toTurnRole(): String {
    return when (this) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
        MessageRole.SYSTEM -> "system"
    }
}
