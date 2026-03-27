package com.pocketagent.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
fun rememberHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.tickLight() } }
}

@Composable
fun rememberLongPressHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.tickConfirm() } }
}

fun HapticFeedback.tickLight() = performHapticFeedback(HapticFeedbackType.TextHandleMove)
fun HapticFeedback.tickConfirm() = performHapticFeedback(HapticFeedbackType.LongPress)
