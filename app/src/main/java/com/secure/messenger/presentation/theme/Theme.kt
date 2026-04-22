package com.secure.messenger.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Перечисление цветовых схем ──────────────────────────────────────────────

enum class AppColorScheme(val label: String, val isDark: Boolean = false) {
    CLASSIC("Классическая"),
    OCEAN("Океан"),
    FOREST("Лес"),
    SUNSET("Закат"),
    MATERIAL_DARK("Material Dark", isDark = true),
    MIDNIGHT("Полночь", isDark = true),
    EMERALD_DARK("Изумруд", isDark = true),
    ROYAL_DARK("Королевский", isDark = true),
    CORAL_DARK("Коралл", isDark = true),
    SLATE_DARK("Графит", isDark = true),
}

// ── Дополнительные цвета (пузырьки, обои) — зависят от выбранной схемы ──────

data class MessengerExtraColors(
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val chatWallpaper: Color,
    val chatPattern: Color,
)

val LocalMessengerColors = staticCompositionLocalOf {
    MessengerExtraColors(
        outgoingBubble = Color.Unspecified,
        incomingBubble = Color.Unspecified,
        chatWallpaper = Color.Unspecified,
        chatPattern = Color.Unspecified,
    )
}

// Для обратной совместимости — глобальные геттеры
val OutgoingBubble: Color @Composable get() = LocalMessengerColors.current.outgoingBubble
val IncomingBubble: Color @Composable get() = LocalMessengerColors.current.incomingBubble
val ChatWallpaperLight: Color @Composable get() = LocalMessengerColors.current.chatWallpaper
val ChatWallpaperDark: Color @Composable get() = LocalMessengerColors.current.chatWallpaper
val ChatPatternLight: Color @Composable get() = LocalMessengerColors.current.chatPattern
val ChatPatternDark: Color @Composable get() = LocalMessengerColors.current.chatPattern

// Оставляем статичные значения для тёмных пузырьков (нужны в ChatScreen)
val OutgoingBubbleDark: Color @Composable get() = LocalMessengerColors.current.outgoingBubble
val IncomingBubbleDark: Color @Composable get() = LocalMessengerColors.current.incomingBubble

// ══════════════════════════════════════════════════════════════════════════════
// 1. CLASSIC — текущая тёплая тема
// ══════════════════════════════════════════════════════════════════════════════

private val ClassicLight = lightColorScheme(
    primary = Color(0xFF3D8BD3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6F5),
    onPrimaryContainer = Color(0xFF1A3A5C),
    secondary = Color(0xFF5A7A96),
    background = Color(0xFFF5F5F0),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFAFAF7),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFEDEDE8),
    onSurfaceVariant = Color(0xFF606060),
    surfaceContainerLow = Color(0xFFF0F0EB),
    outline = Color(0xFFCACAC5),
    outlineVariant = Color(0xFFDDDDD8),
)

private val ClassicDark = darkColorScheme(
    primary = Color(0xFF5BA0E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A4060),
    secondary = Color(0xFF7AAFD4),
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    outline = Color(0xFF444444),
)

private val ClassicExtraLight = MessengerExtraColors(
    outgoingBubble = Color(0xFFE8F0DC),
    incomingBubble = Color(0xFFFAFAF7),
    chatWallpaper = Color(0xFFDAE8D2),
    chatPattern = Color(0xFFCDDCC5),
)

private val ClassicExtraDark = MessengerExtraColors(
    outgoingBubble = Color(0xFF1A4731),
    incomingBubble = Color(0xFF2C2C2E),
    chatWallpaper = Color(0xFF0E1621),
    chatPattern = Color(0xFF172331),
)

// ══════════════════════════════════════════════════════════════════════════════
// 2. OCEAN — глубокий синий
// ══════════════════════════════════════════════════════════════════════════════

private val OceanLight = lightColorScheme(
    primary = Color(0xFF2979B0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5F5),
    onPrimaryContainer = Color(0xFF0A3052),
    secondary = Color(0xFF4A90B8),
    background = Color(0xFFF0F4F8),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF7FAFC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E8EF),
    onSurfaceVariant = Color(0xFF4A5568),
    surfaceContainerLow = Color(0xFFEBF0F5),
    outline = Color(0xFFB0BEC5),
    outlineVariant = Color(0xFFD6DEE5),
)

private val OceanDark = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF81D4FA),
    background = Color(0xFF0A1929),
    surface = Color(0xFF102A43),
    surfaceVariant = Color(0xFF1A3A5C),
    outline = Color(0xFF2A4A6C),
)

private val OceanExtraLight = MessengerExtraColors(
    outgoingBubble = Color(0xFFD4E8F7),
    incomingBubble = Color(0xFFF7FAFC),
    chatWallpaper = Color(0xFFD0E1ED),
    chatPattern = Color(0xFFC2D5E3),
)

private val OceanExtraDark = MessengerExtraColors(
    outgoingBubble = Color(0xFF0D3B66),
    incomingBubble = Color(0xFF1A3A5C),
    chatWallpaper = Color(0xFF071422),
    chatPattern = Color(0xFF0E2238),
)

// ══════════════════════════════════════════════════════════════════════════════
// 3. FOREST — зелёно-землистые тона
// ══════════════════════════════════════════════════════════════════════════════

private val ForestLight = lightColorScheme(
    primary = Color(0xFF4A8B5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0ECD8),
    onPrimaryContainer = Color(0xFF1B3A23),
    secondary = Color(0xFF6B8F71),
    background = Color(0xFFF3F5F0),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFAFCF7),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE3E8DD),
    onSurfaceVariant = Color(0xFF4D5649),
    surfaceContainerLow = Color(0xFFEDF0E9),
    outline = Color(0xFFB5BFB0),
    outlineVariant = Color(0xFFD5DCD0),
)

private val ForestDark = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFFA5D6A7),
    background = Color(0xFF0D1A0F),
    surface = Color(0xFF152818),
    surfaceVariant = Color(0xFF233A26),
    outline = Color(0xFF3A5C3D),
)

private val ForestExtraLight = MessengerExtraColors(
    outgoingBubble = Color(0xFFD8EDDB),
    incomingBubble = Color(0xFFFAFCF7),
    chatWallpaper = Color(0xFFCBDECE),
    chatPattern = Color(0xFFBDD2C0),
)

private val ForestExtraDark = MessengerExtraColors(
    outgoingBubble = Color(0xFF1A3D1E),
    incomingBubble = Color(0xFF233A26),
    chatWallpaper = Color(0xFF091408),
    chatPattern = Color(0xFF122414),
)

// ══════════════════════════════════════════════════════════════════════════════
// 4. SUNSET — тёплый оранжево-розовый
// ══════════════════════════════════════════════════════════════════════════════

private val SunsetLight = lightColorScheme(
    primary = Color(0xFFD4764E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBDBC8),
    onPrimaryContainer = Color(0xFF5C2D13),
    secondary = Color(0xFFBA8A70),
    background = Color(0xFFFAF5F0),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF1E6DD),
    onSurfaceVariant = Color(0xFF6B5B52),
    surfaceContainerLow = Color(0xFFF5EDE6),
    outline = Color(0xFFCCBFB6),
    outlineVariant = Color(0xFFE3D8D0),
)

private val SunsetDark = darkColorScheme(
    primary = Color(0xFFFFAB76),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7A3B1A),
    secondary = Color(0xFFFFCC80),
    background = Color(0xFF1A110B),
    surface = Color(0xFF2A1D15),
    surfaceVariant = Color(0xFF3E2D22),
    outline = Color(0xFF5C4535),
)

private val SunsetExtraLight = MessengerExtraColors(
    outgoingBubble = Color(0xFFF5E1D0),
    incomingBubble = Color(0xFFFFFBF8),
    chatWallpaper = Color(0xFFEDD8C8),
    chatPattern = Color(0xFFE1CCBA),
)

private val SunsetExtraDark = MessengerExtraColors(
    outgoingBubble = Color(0xFF4A2A15),
    incomingBubble = Color(0xFF3E2D22),
    chatWallpaper = Color(0xFF140D07),
    chatPattern = Color(0xFF241A10),
)

// ══════════════════════════════════════════════════════════════════════════════
// 5. MATERIAL DARK — всегда тёмная, стиль Material You
// ══════════════════════════════════════════════════════════════════════════════

private val MaterialDarkColors = darkColorScheme(
    primary = Color(0xFFBB86FC),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color(0xFFE1E1E1),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE1E1E1),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceContainerLow = Color(0xFF1A1A1A),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF383838),
    error = Color(0xFFCF6679),
)

private val MaterialDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF2D1B4E),
    incomingBubble = Color(0xFF2D2D2D),
    chatWallpaper = Color(0xFF0D0D0D),
    chatPattern = Color(0xFF1A1A1A),
)

// ══════════════════════════════════════════════════════════════════════════════
// 6. MIDNIGHT — глубокая синяя ночь
// ══════════════════════════════════════════════════════════════════════════════

private val MidnightColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF93C5FD),
    background = Color(0xFF0A0E27),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111732),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E2544),
    onSurfaceVariant = Color(0xFFB4BCD0),
    surfaceContainerLow = Color(0xFF0E1431),
    outline = Color(0xFF3B4869),
    outlineVariant = Color(0xFF252E4F),
    error = Color(0xFFFF6B6B),
)

private val MidnightExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF1E3A8A),
    incomingBubble = Color(0xFF1E2544),
    chatWallpaper = Color(0xFF06091A),
    chatPattern = Color(0xFF0C1128),
)

// ══════════════════════════════════════════════════════════════════════════════
// 7. EMERALD_DARK — тёмно-зелёный, изумрудный
// ══════════════════════════════════════════════════════════════════════════════

private val EmeraldDarkColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF052E22),
    primaryContainer = Color(0xFF065F46),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = Color(0xFF6EE7B7),
    background = Color(0xFF071510),
    onBackground = Color(0xFFE0F2EC),
    surface = Color(0xFF0F221B),
    onSurface = Color(0xFFE0F2EC),
    surfaceVariant = Color(0xFF1A3328),
    onSurfaceVariant = Color(0xFFB0C8BE),
    surfaceContainerLow = Color(0xFF0C1D17),
    outline = Color(0xFF3B5B4D),
    outlineVariant = Color(0xFF213C30),
    error = Color(0xFFFF7675),
)

private val EmeraldDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF064E3B),
    incomingBubble = Color(0xFF1A3328),
    chatWallpaper = Color(0xFF040D09),
    chatPattern = Color(0xFF0A1812),
)

// ══════════════════════════════════════════════════════════════════════════════
// 8. ROYAL_DARK — королевский фиолетово-синий
// ══════════════════════════════════════════════════════════════════════════════

private val RoyalDarkColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1E1038),
    primaryContainer = Color(0xFF4C1D95),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFC4B5FD),
    background = Color(0xFF120B24),
    onBackground = Color(0xFFE9E4F7),
    surface = Color(0xFF1C1235),
    onSurface = Color(0xFFE9E4F7),
    surfaceVariant = Color(0xFF2A1E4A),
    onSurfaceVariant = Color(0xFFC2B7D5),
    surfaceContainerLow = Color(0xFF170E2D),
    outline = Color(0xFF4A3A6B),
    outlineVariant = Color(0xFF2E224A),
    error = Color(0xFFFF8585),
)

private val RoyalDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF4C1D95),
    incomingBubble = Color(0xFF2A1E4A),
    chatWallpaper = Color(0xFF09061A),
    chatPattern = Color(0xFF120C28),
)

// ══════════════════════════════════════════════════════════════════════════════
// 9. CORAL_DARK — коралловый на тёмно-бордовом
// ══════════════════════════════════════════════════════════════════════════════

private val CoralDarkColors = darkColorScheme(
    primary = Color(0xFFFB7185),
    onPrimary = Color(0xFF3D0A14),
    primaryContainer = Color(0xFF9F1239),
    onPrimaryContainer = Color(0xFFFFE4E6),
    secondary = Color(0xFFFDA4AF),
    background = Color(0xFF1C0A0E),
    onBackground = Color(0xFFFBE8EB),
    surface = Color(0xFF2A1015),
    onSurface = Color(0xFFFBE8EB),
    surfaceVariant = Color(0xFF3D1821),
    onSurfaceVariant = Color(0xFFD9B8BE),
    surfaceContainerLow = Color(0xFF220C10),
    outline = Color(0xFF6B3643),
    outlineVariant = Color(0xFF401B24),
    error = Color(0xFFFFB4A9),
)

private val CoralDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF7E1D32),
    incomingBubble = Color(0xFF3D1821),
    chatWallpaper = Color(0xFF130508),
    chatPattern = Color(0xFF1D090D),
)

// ══════════════════════════════════════════════════════════════════════════════
// 10. SLATE_DARK — холодный графит с серо-голубым акцентом
// ══════════════════════════════════════════════════════════════════════════════

private val SlateDarkColors = darkColorScheme(
    primary = Color(0xFF94A3B8),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF334155),
    onPrimaryContainer = Color(0xFFE2E8F0),
    secondary = Color(0xFFCBD5E1),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceContainerLow = Color(0xFF18223A),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF2A3B54),
    error = Color(0xFFFF8B7A),
)

private val SlateDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF334155),
    incomingBubble = Color(0xFF1E293B),
    chatWallpaper = Color(0xFF0A1120),
    chatPattern = Color(0xFF121C30),
)

// ══════════════════════════════════════════════════════════════════════════════
// Выбор палитры по схеме
// ══════════════════════════════════════════════════════════════════════════════

fun resolveColorScheme(scheme: AppColorScheme, isDark: Boolean): ColorScheme = when (scheme) {
    AppColorScheme.CLASSIC -> if (isDark) ClassicDark else ClassicLight
    AppColorScheme.OCEAN -> if (isDark) OceanDark else OceanLight
    AppColorScheme.FOREST -> if (isDark) ForestDark else ForestLight
    AppColorScheme.SUNSET -> if (isDark) SunsetDark else SunsetLight
    AppColorScheme.MATERIAL_DARK -> MaterialDarkColors
    AppColorScheme.MIDNIGHT -> MidnightColors
    AppColorScheme.EMERALD_DARK -> EmeraldDarkColors
    AppColorScheme.ROYAL_DARK -> RoyalDarkColors
    AppColorScheme.CORAL_DARK -> CoralDarkColors
    AppColorScheme.SLATE_DARK -> SlateDarkColors
}

fun resolveExtraColors(scheme: AppColorScheme, isDark: Boolean): MessengerExtraColors = when (scheme) {
    AppColorScheme.CLASSIC -> if (isDark) ClassicExtraDark else ClassicExtraLight
    AppColorScheme.OCEAN -> if (isDark) OceanExtraDark else OceanExtraLight
    AppColorScheme.FOREST -> if (isDark) ForestExtraDark else ForestExtraLight
    AppColorScheme.SUNSET -> if (isDark) SunsetExtraDark else SunsetExtraLight
    AppColorScheme.MATERIAL_DARK -> MaterialDarkExtra
    AppColorScheme.MIDNIGHT -> MidnightExtra
    AppColorScheme.EMERALD_DARK -> EmeraldDarkExtra
    AppColorScheme.ROYAL_DARK -> RoyalDarkExtra
    AppColorScheme.CORAL_DARK -> CoralDarkExtra
    AppColorScheme.SLATE_DARK -> SlateDarkExtra
}

/** Цвет-превью для селектора в настройках */
fun AppColorScheme.previewColor(): Color = when (this) {
    AppColorScheme.CLASSIC -> Color(0xFF3D8BD3)
    AppColorScheme.OCEAN -> Color(0xFF2979B0)
    AppColorScheme.FOREST -> Color(0xFF4A8B5C)
    AppColorScheme.SUNSET -> Color(0xFFD4764E)
    AppColorScheme.MATERIAL_DARK -> Color(0xFFBB86FC)
    AppColorScheme.MIDNIGHT -> Color(0xFF60A5FA)
    AppColorScheme.EMERALD_DARK -> Color(0xFF34D399)
    AppColorScheme.ROYAL_DARK -> Color(0xFFA78BFA)
    AppColorScheme.CORAL_DARK -> Color(0xFFFB7185)
    AppColorScheme.SLATE_DARK -> Color(0xFF94A3B8)
}

// ══════════════════════════════════════════════════════════════════════════════
// Тема-обёртка
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SecureMessengerTheme(
    colorScheme: AppColorScheme = AppColorScheme.CLASSIC,
    content: @Composable () -> Unit,
) {
    // Выбранная в приложении схема полностью управляет темой — ИГНОРИРУЕМ
    // системное isSystemInDarkTheme(). Иначе CLASSIC/OCEAN/FOREST/SUNSET при
    // тёмной системной теме переключались в dark-вариант, а MATERIAL_DARK
    // при светлой системе давал непоследовательные шапки/статус-бары.
    //
    // Все схемы с isDark=true (Material Dark и новые тёмные) — тёмные,
    // остальные — светлые.
    val isDark = colorScheme.isDark
    val colors = resolveColorScheme(colorScheme, isDark)
    val extra = resolveExtraColors(colorScheme, isDark)

    CompositionLocalProvider(LocalMessengerColors provides extra) {
        MaterialTheme(
            colorScheme = colors,
            typography = MessengerTypography,
            content = content,
        )
    }
}
