package com.pocketagent.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
internal fun MarkdownMessageContent(
    content: String,
    clipboardManager: ClipboardManager? = null,
) {
    val sanitizedContent = remember(content) { sanitizeMarkdownForRendering(content) }
    val codeFenceParts = remember(sanitizedContent) { sanitizedContent.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        codeFenceParts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                val lines = part.lines()
                val language = lines.firstOrNull()?.trim().orEmpty()
                val codeContent = if (language.isNotBlank() && !language.contains(" ")) {
                    lines.drop(1).joinToString("\n").trim()
                } else {
                    part.trim()
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            if (language.isNotBlank() && !language.contains(" ")) {
                                Text(
                                    text = language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }
                            if (clipboardManager != null) {
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(codeContent)) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy code",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = codeContent,
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .padding(bottom = 10.dp)
                                .horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            } else {
                part.lines().forEach { rawLine ->
                    val line = rawLine.trimEnd()
                    if (line.isBlank()) return@forEach
                    when {
                        line.startsWith("### ") -> MarkdownInlineText(
                            text = renderInlineMarkdown(line.drop(4)),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        line.startsWith("## ") -> MarkdownInlineText(
                            text = renderInlineMarkdown(line.drop(3)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        line.startsWith("# ") -> MarkdownInlineText(
                            text = renderInlineMarkdown(line.drop(2)),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        line.startsWith("> ") -> {
                            Row {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(3.dp).height(20.dp),
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                MarkdownInlineText(
                                    text = renderInlineMarkdown(line.drop(2)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        line.startsWith("- ") || line.startsWith("* ") -> MarkdownInlineText(
                            text = renderInlineMarkdown("• ${line.drop(2)}"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        line.matches(Regex("^\\d+\\.\\s.*")) -> MarkdownInlineText(
                            text = renderInlineMarkdown(line),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        line.startsWith("---") || line.startsWith("***") -> HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        else -> MarkdownInlineText(
                            text = renderInlineMarkdown(line),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private val INLINE_MARKDOWN_REGEX = Regex(
    """(\*\*(.+?)\*\*)|(\*(.+?)\*)|(`(.+?)`)|(\~\~(.+?)\~\~)|(\[(.+?)\]\((.+?)\))""",
)

internal fun renderInlineMarkdown(text: String): AnnotatedString {
    val matches = INLINE_MARKDOWN_REGEX.findAll(text).toList()
    if (matches.isEmpty()) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var cursor = 0
        matches.forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            when {
                match.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[2])
                }
                match.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[4])
                }
                match.groupValues[6].isNotEmpty() -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x20808080)),
                ) {
                    append(match.groupValues[6])
                }
                match.groupValues[8].isNotEmpty() -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                ) {
                    append(match.groupValues[8])
                }
                match.groupValues[10].isNotEmpty() -> {
                    withLink(
                        LinkAnnotation.Url(
                            url = match.groupValues[11],
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = Color(0xFF1A73E8),
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) {
                        append(match.groupValues[10])
                    }
                }
                else -> append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

@Composable
internal fun MarkdownInlineText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        style = style.merge(TextStyle(color = color, fontWeight = fontWeight)),
    )
}

internal fun sanitizeMarkdownForRendering(content: String): String {
    val codeFenceCount = "```".toRegex().findAll(content).count()
    return if (codeFenceCount % 2 == 0) content else "$content\n```"
}
