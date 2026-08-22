package com.meetingapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PrimaryBlue = Color(0xFF1A73E8)
private val OnPrimary = Color.White
private val Surface = Color(0xFFF8F9FA)
private val SurfaceDark = Color(0xFF1C1C1E)

private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimary,
    surface = Surface,
    background = Surface
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FA3FF),
    onPrimary = Color(0xFF003063),
    surface = SurfaceDark,
    background = Color(0xFF000000)
)

@Composable
fun MeetingAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
