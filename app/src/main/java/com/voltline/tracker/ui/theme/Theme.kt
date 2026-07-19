package com.voltline.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VoltlineColors = darkColorScheme(
    primary = VoltCyan,
    onPrimary = VoltBackground,
    secondary = VoltLime,
    onSecondary = VoltBackground,
    tertiary = VoltAmber,
    background = VoltBackground,
    onBackground = VoltTextHi,
    surface = VoltSurface,
    onSurface = VoltTextHi,
    surfaceVariant = VoltSurfaceHi,
    onSurfaceVariant = VoltTextDim,
    error = VoltRed,
)

/** Voltline is a dark-only cockpit; the scheme ignores the system light theme. */
@Composable
fun VoltlineTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = VoltlineColors,
        typography = VoltlineTypography,
        content = content,
    )
}
