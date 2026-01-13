package dev.insforge.samples.twitter.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1DA1F2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003258),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF14171A),
    onSecondary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF15202B),
    onSurface = Color.White,
    error = Color(0xFFE0245E),
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1DA1F2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF003258),
    secondary = Color(0xFF14171A),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF14171A),
    surface = Color(0xFFF7F9F9),
    onSurface = Color(0xFF14171A),
    error = Color(0xFFE0245E),
    onError = Color.White
)

@Composable
fun TwitterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}