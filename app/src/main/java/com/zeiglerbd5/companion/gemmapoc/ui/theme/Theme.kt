package com.zeiglerbd5.companion.gemmapoc.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

/**
 * Wraps content in a Material 3 theme whose `ColorScheme` is derived from
 * the user's selected [AppTheme]. `AppTheme.System` falls through to the
 * existing dynamic-or-static behaviour the Android Studio template ships
 * with; every other case maps the iOS Theme properties onto Material 3
 * color tokens so the existing Card / Button / Scaffold surfaces pick up
 * the theme without per-component overrides.
 */
@Composable
fun GemmaPOCTheme(
    appTheme: AppTheme = AppTheme.System,
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appTheme) {
        AppTheme.System -> systemColorScheme(darkTheme, dynamicColor)
        else -> appTheme.toColorScheme(systemDark = darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

@Composable
private fun systemColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
) = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
}

/**
 * Map AppTheme's iOS-mirroring per-surface colors onto Material 3's
 * ColorScheme tokens. The mapping is intentionally lossy — Material 3
 * has more tokens than iOS exposes (primaryContainer, tertiary, etc.)
 * — but the visible surfaces (background, surface, primary, onSurface)
 * are wired up so themes are immediately legible.
 */
private fun AppTheme.toColorScheme(systemDark: Boolean): androidx.compose.material3.ColorScheme {
    val dark = isDark ?: systemDark
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // Prefer inputText over bubbleText for the onSurface family: Material 3
    // has one onSurface that pairs with every surface*-container variant,
    // and our Cards (the dominant text-bearing surface today) sit on
    // inputBackground, not bubble colors. For Sky this means deep-navy
    // text on white cards and on the sky-blue background — both legible.
    val onSurface = inputText ?: bubbleText ?: base.onSurface
    return base.copy(
        primary = accent ?: base.primary,
        onPrimary = if (dark) Color.Black else Color.White,
        background = background ?: base.background,
        onBackground = onSurface,
        surface = background ?: base.surface,
        onSurface = onSurface,
        surfaceVariant = modelBubble(dark),
        onSurfaceVariant = onSurface,
        // Card defaults to surfaceContainerLow; cover the whole family so
        // every container surface picks up the theme instead of falling
        // through to lightColorScheme()'s pale pink defaults.
        surfaceContainerLowest = inputBackground(dark),
        surfaceContainerLow = inputBackground(dark),
        surfaceContainer = inputBackground(dark),
        surfaceContainerHigh = inputBackground(dark),
        surfaceContainerHighest = inputBackground(dark),
        outline = inputBorder(dark),
    )
}
