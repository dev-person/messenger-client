package com.secure.messenger.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Telegram-like colour palette
private val TelegramBlue = Color(0xFF2AABEE)
private val TelegramBlueDark = Color(0xFF1D8DC8)
private val TelegramBackground = Color(0xFFEFEFEF)
private val TelegramSurface = Color(0xFFFFFFFF)
private val TelegramDarkBackground = Color(0xFF0F0F0F)
private val TelegramDarkSurface = Color(0xFF1C1C1E)
private val TelegramDarkSurfaceVariant = Color(0xFF2C2C2E)
val OutgoingBubble = Color(0xFFEFFAE3)
val OutgoingBubbleDark = Color(0xFF1A4731)
val IncomingBubble = Color(0xFFFFFFFF)
val IncomingBubbleDark = Color(0xFF2C2C2E)

private val LightColors = lightColorScheme(
    primary = TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0EEFF),
    secondary = TelegramBlueDark,
    background = TelegramBackground,
    surface = TelegramSurface,
    surfaceVariant = Color(0xFFF0F0F0),
    outline = Color(0xFFCCCCCC),
)

private val DarkColors = darkColorScheme(
    primary = TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A4060),
    secondary = TelegramBlueDark,
    background = TelegramDarkBackground,
    surface = TelegramDarkSurface,
    surfaceVariant = TelegramDarkSurfaceVariant,
    outline = Color(0xFF444444),
)

@Composable
fun SecureMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MessengerTypography,
        content = content,
    )
}
