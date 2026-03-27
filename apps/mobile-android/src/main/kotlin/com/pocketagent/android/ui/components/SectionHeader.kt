package com.pocketagent.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pocketagent.android.ui.theme.PocketAgentDimensions

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.padding(bottom = PocketAgentDimensions.sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.compactSpacing),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
