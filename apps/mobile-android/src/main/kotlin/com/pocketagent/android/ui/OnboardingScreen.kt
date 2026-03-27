package com.pocketagent.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.PocketTheme

private data class OnboardingPageData(
    val icon: ImageVector,
    val headline: String,
    val body: String,
)

// TODO: extract to strings.xml
// onboarding_welcome_headline = "Your AI, On Your Device"
// onboarding_welcome_body = "PocketGPT runs powerful language models entirely on your phone. No cloud, no subscriptions -- just you and your AI assistant."
// onboarding_privacy_headline = "100% Private, 100% Offline"
// onboarding_privacy_body = "Your conversations never leave your device. No data is sent to any server. Your privacy is guaranteed by design."
// onboarding_download_headline = "Download Your First Model"
// onboarding_download_body = "To get started, download a compact AI model optimized for your device. This is a one-time setup."
// onboarding_skip = "Skip"
// onboarding_next = "Next"
// onboarding_get_started = "Get Started"
// onboarding_downloading = "Downloading..."
// onboarding_download_model = "Download Recommended Model"

private val onboardingPages = listOf(
    OnboardingPageData(
        icon = Icons.Filled.PhoneAndroid,
        headline = "Your AI, On Your Device", // TODO: extract to strings.xml
        body = "PocketGPT runs powerful language models entirely on your phone. " +
            "No cloud, no subscriptions \u2014 just you and your AI assistant.", // TODO: extract to strings.xml
    ),
    OnboardingPageData(
        icon = Icons.Filled.Shield,
        headline = "100% Private, 100% Offline", // TODO: extract to strings.xml
        body = "Your conversations never leave your device. No data is sent to any server. " +
            "Your privacy is guaranteed by design.", // TODO: extract to strings.xml
    ),
    OnboardingPageData(
        icon = Icons.Filled.CloudDownload,
        headline = "Download Your First Model", // TODO: extract to strings.xml
        body = "To get started, download a compact AI model optimized for your device. " +
            "This is a one-time setup.", // TODO: extract to strings.xml
    ),
)

private const val PAGE_COUNT = 3

/**
 * Full-screen onboarding experience with a horizontal pager, page indicators,
 * and navigation controls. Designed as a drop-in replacement for the existing
 * AlertDialog-based onboarding in ChatApp.kt.
 *
 * All state is provided via parameters -- no ViewModel references.
 */
@Composable
fun OnboardingScreen(
    currentPage: Int,
    onNextPage: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    onStartDownload: () -> Unit = {},
) {
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, PAGE_COUNT - 1),
        pageCount = { PAGE_COUNT },
    )

    // Sync external currentPage to pager
    LaunchedEffect(currentPage) {
        val target = currentPage.coerceIn(0, PAGE_COUNT - 1)
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Skip button -- top right, hidden on last page
        if (pagerState.currentPage < PAGE_COUNT - 1) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = PocketTheme.spacing.lg,
                        end = PocketAgentDimensions.screenPadding,
                    ),
            ) {
                Text(
                    text = "Skip", // TODO: extract to strings.xml
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Main pager content
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f),
            ) { pageIndex ->
                OnboardingPage(
                    data = onboardingPages[pageIndex],
                    isDownloadPage = pageIndex == PAGE_COUNT - 1,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    onStartDownload = onStartDownload,
                )
            }

            // Page indicator dots
            PageIndicator(
                pageCount = PAGE_COUNT,
                currentPage = pagerState.currentPage,
                modifier = Modifier.padding(bottom = PocketTheme.spacing.lg),
            )

            // Bottom navigation button
            BottomNavigation(
                currentPage = pagerState.currentPage,
                isLastPage = pagerState.currentPage == PAGE_COUNT - 1,
                isDownloading = isDownloading,
                onNext = onNextPage,
                onFinish = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PocketTheme.spacing.xl,
                        vertical = PocketTheme.spacing.lg,
                    ),
            )

            Spacer(modifier = Modifier.height(PocketTheme.spacing.xl))
        }
    }
}

@Composable
private fun OnboardingPage(
    data: OnboardingPageData,
    isDownloadPage: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onStartDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PocketTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = data.icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(PocketTheme.spacing.xl))

        Text(
            text = data.headline,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(PocketTheme.spacing.md))

        Text(
            text = data.body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isDownloadPage) {
            Spacer(modifier = Modifier.height(PocketTheme.spacing.xl))

            if (isDownloading) {
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PocketTheme.spacing.xl),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PocketTheme.spacing.xl),
                    )
                }
            } else {
                Button(onClick = onStartDownload) {
                    Text(
                        text = "Download Recommended Model", // TODO: extract to strings.xml
                    )
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PocketTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val color by animateColorAsState(
                targetValue = if (index == currentPage) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = tween(durationMillis = PocketAgentDimensions.animNormal),
                label = "dotColor",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun BottomNavigation(
    currentPage: Int,
    isLastPage: Boolean,
    isDownloading: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = if (isLastPage) onFinish else onNext,
        modifier = modifier,
        enabled = !isDownloading || !isLastPage,
    ) {
        Text(
            text = when {
                isLastPage && isDownloading -> "Downloading\u2026" // TODO: extract to strings.xml
                isLastPage -> "Get Started" // TODO: extract to strings.xml
                else -> "Next" // TODO: extract to strings.xml
            },
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OnboardingScreenPreview() {
    PocketAgentTheme {
        OnboardingScreen(
            currentPage = 0,
            onNextPage = {},
            onSkip = {},
            onFinish = {},
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "Download Page")
@Composable
private fun OnboardingScreenDownloadPreview() {
    PocketAgentTheme {
        OnboardingScreen(
            currentPage = 2,
            onNextPage = {},
            onSkip = {},
            onFinish = {},
            isDownloading = true,
            downloadProgress = 0.45f,
        )
    }
}
