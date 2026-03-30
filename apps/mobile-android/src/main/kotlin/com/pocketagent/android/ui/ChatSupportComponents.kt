package com.pocketagent.android.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import java.io.File

@Composable
internal fun EmptyChatState(
    messagesLoaded: Boolean,
    onSuggestedPrompt: (String) -> Unit,
    onOpenToolDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!messagesLoaded) {
            ShimmerMessageLoadingPlaceholder()
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                modifier = Modifier.padding(horizontal = PocketAgentDimensions.screenPadding * 2),
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(compactSpacing))
                Text(
                    text = stringResource(id = R.string.ui_chat_empty_state),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing))
                val prompts = listOf(
                    stringResource(id = R.string.ui_prompt_quick_answer),
                    stringResource(id = R.string.ui_prompt_image_help),
                    stringResource(id = R.string.ui_prompt_local_search),
                    stringResource(id = R.string.ui_prompt_reminder),
                )
                prompts.forEach { prompt ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                        ) + slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                        ),
                    ) {
                        SuggestedPromptCard(prompt = prompt, onClick = onSuggestedPrompt)
                    }
                }
                Spacer(modifier = Modifier.height(compactSpacing))
                TextButton(onClick = onOpenToolDialog) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(compactSpacing))
                    Text(
                        text = stringResource(id = R.string.ui_local_tools_title),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TimestampSeparator(epochMs: Long) {
    val relativeTime = remember(epochMs) {
        DateUtils.getRelativeTimeSpanString(
            epochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PocketAgentDimensions.sectionSpacing),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = relativeTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SuggestedPromptCard(
    prompt: String,
    onClick: (String) -> Unit,
) {
    Surface(
        onClick = { onClick(prompt) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = prompt,
            modifier = Modifier.padding(PocketAgentDimensions.cardPadding),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun AttachmentThumbnailRow(
    attachmentPaths: List<String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        attachmentPaths.take(3).forEach { path ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(path))
                    .crossfade(PocketAgentDimensions.animNormal)
                    .build(),
                contentDescription = stringResource(id = R.string.ui_image_message_label, path),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        }
    }
}

internal fun shouldRenderInThreadLoadingPlaceholder(message: MessageUiModel): Boolean {
    return message.role == MessageRole.ASSISTANT && message.isStreaming && message.content.isBlank()
}
