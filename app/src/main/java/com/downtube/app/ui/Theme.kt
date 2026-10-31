package com.downtube.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Brand {
    val Blue = Color(0xFF2E8BFF)
    val Cyan = Color(0xFF3FD0FF)
    val Red = Color(0xFFE5323B)
    val Navy = Color(0xFF08142A)
    val Surface = Color(0xFF0F1E38)
    val SurfaceHigh = Color(0xFF172A4A)
}

private val scheme = darkColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF123A6E),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Brand.Cyan,
    tertiary = Brand.Red,
    background = Brand.Navy,
    onBackground = Color(0xFFE6EDF7),
    surface = Brand.Navy,
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Brand.SurfaceHigh,
    onSurfaceVariant = Color(0xFFA9B8CF),
    surfaceContainer = Brand.Surface,
    surfaceContainerLow = Brand.Surface,
    surfaceContainerHigh = Brand.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF1E355C),
    outline = Color(0xFF34507A),
    error = Color(0xFFFF6B6B),
)

@Composable
fun DownTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
