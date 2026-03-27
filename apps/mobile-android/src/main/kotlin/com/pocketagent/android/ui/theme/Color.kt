package com.pocketagent.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette
private val Teal10 = Color(0xFF001F26)
private val Teal20 = Color(0xFF003640)
private val Teal30 = Color(0xFF004E5C)
private val Teal40 = Color(0xFF00687A)
private val Teal80 = Color(0xFF4FD8EB)
private val Teal90 = Color(0xFFA2EEFF)

private val DeepBlue10 = Color(0xFF001B3D)
private val DeepBlue20 = Color(0xFF002F65)
private val DeepBlue30 = Color(0xFF00448F)
private val DeepBlue40 = Color(0xFF1A5CB0)
private val DeepBlue80 = Color(0xFFAAC7FF)
private val DeepBlue90 = Color(0xFFD6E3FF)

private val Coral10 = Color(0xFF3B0808)
private val Coral20 = Color(0xFF5C1414)
private val Coral30 = Color(0xFF7E2020)
private val Coral40 = Color(0xFF9C3030)
private val Coral80 = Color(0xFFFFB4AB)
private val Coral90 = Color(0xFFFFDAD6)

private val Amber10 = Color(0xFF261A00)
private val Amber20 = Color(0xFF402D00)
private val Amber30 = Color(0xFF5C4200)
private val Amber40 = Color(0xFF7A5800)
private val Amber80 = Color(0xFFF5BF48)
private val Amber90 = Color(0xFFFFDEA1)

private val Neutral10 = Color(0xFF191C1E)
private val Neutral20 = Color(0xFF2E3133)
private val Neutral90 = Color(0xFFE1E3E5)
private val Neutral95 = Color(0xFFF0F1F3)
private val Neutral99 = Color(0xFFFBFCFE)

private val NeutralVariant30 = Color(0xFF414749)
private val NeutralVariant50 = Color(0xFF70787B)
private val NeutralVariant60 = Color(0xFF8A9295)
private val NeutralVariant80 = Color(0xFFC0C8CB)
private val NeutralVariant90 = Color(0xFFDCE4E7)

val PocketLightColorScheme =
    lightColorScheme(
        primary = Teal40,
        onPrimary = Color.White,
        primaryContainer = Teal90,
        onPrimaryContainer = Teal10,
        secondary = DeepBlue40,
        onSecondary = Color.White,
        secondaryContainer = DeepBlue90,
        onSecondaryContainer = DeepBlue10,
        tertiary = Amber40,
        onTertiary = Color.White,
        tertiaryContainer = Amber90,
        onTertiaryContainer = Amber10,
        error = Coral40,
        onError = Color.White,
        errorContainer = Coral90,
        onErrorContainer = Coral10,
        background = Neutral99,
        onBackground = Neutral10,
        surface = Neutral99,
        onSurface = Neutral10,
        surfaceVariant = NeutralVariant90,
        onSurfaceVariant = NeutralVariant30,
        outline = NeutralVariant50,
        outlineVariant = NeutralVariant80,
    )

val PocketDarkColorScheme =
    darkColorScheme(
        primary = Teal80,
        onPrimary = Teal20,
        primaryContainer = Teal30,
        onPrimaryContainer = Teal90,
        secondary = DeepBlue80,
        onSecondary = DeepBlue20,
        secondaryContainer = DeepBlue30,
        onSecondaryContainer = DeepBlue90,
        tertiary = Amber80,
        onTertiary = Amber20,
        tertiaryContainer = Amber30,
        onTertiaryContainer = Amber90,
        error = Coral80,
        onError = Coral20,
        errorContainer = Coral30,
        onErrorContainer = Coral90,
        background = Neutral10,
        onBackground = Neutral90,
        surface = Neutral10,
        onSurface = Neutral90,
        surfaceVariant = NeutralVariant30,
        onSurfaceVariant = NeutralVariant80,
        outline = NeutralVariant60,
        outlineVariant = NeutralVariant30,
    )
