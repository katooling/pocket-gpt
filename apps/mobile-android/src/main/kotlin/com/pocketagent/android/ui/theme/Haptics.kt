package com.pocketagent.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
fun rememberHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

@Composable
fun rememberLongPressHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
}

fun HapticFeedback.tickLight() = performHapticFeedback(HapticFeedbackType.TextHandleMove)
fun HapticFeedback.tickConfirm() = performHapticFeedback(HapticFeedbackType.LongPress)
