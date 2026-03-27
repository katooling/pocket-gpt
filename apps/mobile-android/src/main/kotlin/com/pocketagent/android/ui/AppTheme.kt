package com.pocketagent.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.pocketagent.android.ui.theme.LocalSpacing
import com.pocketagent.android.ui.theme.PocketDarkColorScheme
import com.pocketagent.android.ui.theme.PocketLightColorScheme
import com.pocketagent.android.ui.theme.PocketShapes
import com.pocketagent.android.ui.theme.PocketTypography
import com.pocketagent.android.ui.theme.Spacing

@Composable
fun PocketAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> PocketDarkColorScheme
        else -> PocketLightColorScheme
    }

    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PocketTypography,
            shapes = PocketShapes,
            content = content,
        )
    }
}
