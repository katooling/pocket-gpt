package com.pocketagent.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenComposeContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun downloadProgressAnnouncesDeterminateProgress() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    isDownloading = true,
                    downloadProgress = 0.45f,
                    onStartDownload = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Model download progress 45%")
            .assertIsDisplayed()
    }

    @Test
    fun downloadProgressAnnouncesIndeterminateProgress() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    currentPage = 2,
                    onPageChanged = {},
                    onNextPage = {},
                    onSkip = {},
                    onFinish = {},
                    isDownloading = true,
                    downloadProgress = null,
                    onStartDownload = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Model download progress")
            .assertIsDisplayed()
    }
}
