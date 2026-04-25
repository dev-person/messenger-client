package com.secure.messenger.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.hypot

// ── Перечисление цветовых схем ──────────────────────────────────────────────

enum class AppColorScheme(val label: String, val isDark: Boolean = false) {
    CLASSIC("Классическая"),
    OCEAN("Океан"),
    FOREST("Лес"),
    SUNSET("Закат"),
    MATERIAL_DARK("Material Dark", isDark = true),
    MIDNIGHT("Material Night", isDark = true),
    EMERALD_DARK("Material Emerald", isDark = true),
    CORAL_DARK("Material Coral", isDark = true),
    SLATE_DARK("Material Slate", isDark = true),
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

private val MidnightExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF365C8C),
    incomingBubble = Color(0xFF2D2D2D),
    chatWallpaper = Color(0xFF0D0D0D),
    chatPattern = Color(0xFF1A1A1A),
)

// ══════════════════════════════════════════════════════════════════════════════
// 7. EMERALD_DARK — тёмно-зелёный, изумрудный
// ══════════════════════════════════════════════════════════════════════════════

private val EmeraldDarkColors = darkColorScheme(
    primary = Color(0xFF34D399),
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

private val EmeraldDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF1E7A59),
    incomingBubble = Color(0xFF2D2D2D),
    chatWallpaper = Color(0xFF0D0D0D),
    chatPattern = Color(0xFF1A1A1A),
)

// ══════════════════════════════════════════════════════════════════════════════
// 9. CORAL_DARK — коралловый на тёмно-бордовом
// ══════════════════════════════════════════════════════════════════════════════

private val CoralDarkColors = darkColorScheme(
    primary = Color(0xFFFB7185),
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
    error = Color(0xFFCF6679)
)

private val CoralDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF773640),
    incomingBubble = Color(0xFF2D2D2D),
    chatWallpaper = Color(0xFF0D0D0D),
    chatPattern = Color(0xFF1A1A1A)
)

// ══════════════════════════════════════════════════════════════════════════════
// 10. SLATE_DARK — холодный графит с серо-голубым акцентом
// ══════════════════════════════════════════════════════════════════════════════

private val SlateDarkColors = darkColorScheme(
    primary = Color(0xFF94A3B8),
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
    error = Color(0xFFCF6679)
)

private val SlateDarkExtra = MessengerExtraColors(
    outgoingBubble = Color(0xFF616870),
    incomingBubble = Color(0xFF2D2D2D),
    chatWallpaper = Color(0xFF0D0D0D),
    chatPattern = Color(0xFF1A1A1A)
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
    AppColorScheme.CORAL_DARK -> Color(0xFFFB7185)
    AppColorScheme.SLATE_DARK -> Color(0xFF94A3B8)
}

// ══════════════════════════════════════════════════════════════════════════════
// Тема-обёртка
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Длительность кроссфейда всех цветов темы при её смене.
 * 400 мс — достаточно плавно, чтобы не ловить «слепоту» при переходе с тёмной
 * на светлую ночью, но не слишком затянуто.
 */
private const val THEME_TRANSITION_MS = 400

/**
 * Возвращает анимированный цвет: при смене [target] он плавно интерполируется
 * за [THEME_TRANSITION_MS] мс вместо мгновенного переключения. Используется
 * для каждого поля Material [ColorScheme] и дополнительных цветов мессенджера.
 */
@Composable
private fun animatedColor(target: Color, label: String): Color =
    animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = THEME_TRANSITION_MS),
        label = label,
    ).value

/**
 * Оборачивает переданную [target]-схему и возвращает новую [ColorScheme],
 * где каждое поле плавно анимировано. Так при смене цветовой схемы
 * в настройках всё приложение переливается, а не «щёлкает» одним кадром.
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme = ColorScheme(
    primary = animatedColor(target.primary, "primary"),
    onPrimary = animatedColor(target.onPrimary, "onPrimary"),
    primaryContainer = animatedColor(target.primaryContainer, "primaryContainer"),
    onPrimaryContainer = animatedColor(target.onPrimaryContainer, "onPrimaryContainer"),
    inversePrimary = animatedColor(target.inversePrimary, "inversePrimary"),
    secondary = animatedColor(target.secondary, "secondary"),
    onSecondary = animatedColor(target.onSecondary, "onSecondary"),
    secondaryContainer = animatedColor(target.secondaryContainer, "secondaryContainer"),
    onSecondaryContainer = animatedColor(target.onSecondaryContainer, "onSecondaryContainer"),
    tertiary = animatedColor(target.tertiary, "tertiary"),
    onTertiary = animatedColor(target.onTertiary, "onTertiary"),
    tertiaryContainer = animatedColor(target.tertiaryContainer, "tertiaryContainer"),
    onTertiaryContainer = animatedColor(target.onTertiaryContainer, "onTertiaryContainer"),
    background = animatedColor(target.background, "background"),
    onBackground = animatedColor(target.onBackground, "onBackground"),
    surface = animatedColor(target.surface, "surface"),
    onSurface = animatedColor(target.onSurface, "onSurface"),
    surfaceVariant = animatedColor(target.surfaceVariant, "surfaceVariant"),
    onSurfaceVariant = animatedColor(target.onSurfaceVariant, "onSurfaceVariant"),
    surfaceTint = animatedColor(target.surfaceTint, "surfaceTint"),
    inverseSurface = animatedColor(target.inverseSurface, "inverseSurface"),
    inverseOnSurface = animatedColor(target.inverseOnSurface, "inverseOnSurface"),
    error = animatedColor(target.error, "error"),
    onError = animatedColor(target.onError, "onError"),
    errorContainer = animatedColor(target.errorContainer, "errorContainer"),
    onErrorContainer = animatedColor(target.onErrorContainer, "onErrorContainer"),
    outline = animatedColor(target.outline, "outline"),
    outlineVariant = animatedColor(target.outlineVariant, "outlineVariant"),
    scrim = animatedColor(target.scrim, "scrim"),
    surfaceBright = animatedColor(target.surfaceBright, "surfaceBright"),
    surfaceDim = animatedColor(target.surfaceDim, "surfaceDim"),
    surfaceContainer = animatedColor(target.surfaceContainer, "surfaceContainer"),
    surfaceContainerHigh = animatedColor(target.surfaceContainerHigh, "surfaceContainerHigh"),
    surfaceContainerHighest = animatedColor(target.surfaceContainerHighest, "surfaceContainerHighest"),
    surfaceContainerLow = animatedColor(target.surfaceContainerLow, "surfaceContainerLow"),
    surfaceContainerLowest = animatedColor(target.surfaceContainerLowest, "surfaceContainerLowest"),
)

/** Анимированная версия [MessengerExtraColors]. */
@Composable
private fun animatedExtraColors(target: MessengerExtraColors): MessengerExtraColors =
    MessengerExtraColors(
        outgoingBubble = animatedColor(target.outgoingBubble, "outgoingBubble"),
        incomingBubble = animatedColor(target.incomingBubble, "incomingBubble"),
        chatWallpaper = animatedColor(target.chatWallpaper, "chatWallpaper"),
        chatPattern = animatedColor(target.chatPattern, "chatPattern"),
    )

/**
 * Оставлен как no-op для обратной совместимости. Логика reveal теперь
 * интегрирована прямо в [SecureMessengerTheme] через graphicsLayer —
 * отдельный оверлей не нужен, но если на него ссылается старый код
 * (MainActivity) — он просто ничего не рендерит.
 */
@Composable
fun ThemeRevealOverlay() = Unit

/**
 * Длительность pixel-perfect reveal-анимации. 850 мс — достаточно,
 * чтобы пользователь успел «проводить глазами» круг от центра до края
 * экрана, но не настолько долго, чтобы ощущалось как лаг.
 */
private const val REVEAL_DURATION_MS = 850

@Composable
fun SecureMessengerTheme(
    colorScheme: AppColorScheme = AppColorScheme.CLASSIC,
    content: @Composable () -> Unit,
) {
    // Выбранная в приложении схема полностью управляет темой — ИГНОРИРУЕМ
    // системное isSystemInDarkTheme(). Иначе CLASSIC/OCEAN/FOREST/SUNSET при
    // тёмной системной теме переключались в dark-вариант, а MATERIAL_DARK
    // при светлой системе давал непоследовательные шапки/статус-бары.

    val revealSpec by ThemeTransition.activeReveal.collectAsState()
    var revealProgress by remember { mutableStateOf<Float?>(null) }

    // Жизненный цикл reveal-анимации. Снимок старого UI в revealSpec.snapshot
    // уже захвачен в момент клика (см. ThemeTransition.startReveal) — нам
    // остаётся только запустить прогресс от 0 к 1 и по завершении сбросить.
    LaunchedEffect(revealSpec?.nonce) {
        if (revealSpec == null) return@LaunchedEffect
        revealProgress = 0f
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = REVEAL_DURATION_MS, easing = FastOutSlowInEasing),
        ) { value, _ -> revealProgress = value }
        revealProgress = null
        ThemeTransition.endReveal()
    }

    val isDark = colorScheme.isDark
    val targetColors = resolveColorScheme(colorScheme, isDark)
    val targetExtra = resolveExtraColors(colorScheme, isDark)

    // Во время reveal отключаем crossfade цветов — поверх снимка старого UI
    // уже нарисован новый, дополнительная анимация цветов создаёт визуальный
    // шум. В остальное время плавная переливка цветов остаётся (например,
    // при force_logout или при переходе между темами одной «светлоты»).
    val colors = if (revealProgress == null) animatedColorScheme(targetColors) else targetColors
    val extra = if (revealProgress == null) animatedExtraColors(targetExtra) else targetExtra

    CompositionLocalProvider(LocalMessengerColors provides extra) {
        MaterialTheme(
            colorScheme = colors,
            typography = MessengerTypography,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Основное содержимое приложения — МаterialTheme уже
                // применил новую цветовую схему, поэтому content() рендерит
                // новый UI с первого кадра reveal.
                content()

                // Overlay: рисуем СНИМОК старого UI поверх нового, но только
                // ВНЕ растущего круга в центре. Круг имеет радиус 0 на старте →
                // пользователь видит старый UI полностью. По мере роста круга
                // старый UI «отступает», открывая уже отрисованный под ним
                // новый UI. Клип через EvenOdd-path (rect + oval).
                val progress = revealProgress
                val snapshot = revealSpec?.snapshot
                if (progress != null && snapshot != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = hypot(size.width / 2f, size.height / 2f) + 24f
                        val radius = maxRadius * progress

                        if (radius < maxRadius) {
                            val clip = Path().apply {
                                addRect(Rect(0f, 0f, size.width, size.height))
                                addOval(
                                    Rect(
                                        center.x - radius,
                                        center.y - radius,
                                        center.x + radius,
                                        center.y + radius,
                                    )
                                )
                                fillType = PathFillType.EvenOdd
                            }
                            clipPath(clip) {
                                drawImage(image = snapshot)
                            }
                        }
                    }
                }
            }
        }
    }
}
