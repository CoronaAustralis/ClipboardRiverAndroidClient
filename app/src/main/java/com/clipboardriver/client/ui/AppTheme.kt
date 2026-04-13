package com.clipboardriver.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5D5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBCECE9),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF4B635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8E2),
    onSecondaryContainer = Color(0xFF06201C),
    tertiary = Color(0xFF775A2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDAE),
    onTertiaryContainer = Color(0xFF291800),
    background = Color(0xFFF6FBFA),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDBE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FD0CC),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF004F4D),
    onPrimaryContainer = Color(0xFFBCECE9),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1D3430),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCDE8E2),
    tertiary = Color(0xFFE7C18B),
    onTertiary = Color(0xFF442B05),
    tertiaryContainer = Color(0xFF5D4119),
    onTertiaryContainer = Color(0xFFFFDDAE),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF151C1B),
    onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun ClipboardRiverAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
