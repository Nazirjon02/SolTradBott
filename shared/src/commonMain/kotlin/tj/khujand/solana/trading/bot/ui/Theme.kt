package tj.khujand.solana.trading.bot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Solana Brand Palette ─────────────────────────────────────────────────────
val SolPurple         = Color(0xFF9945FF)   // фирменный фиолетовый Solana
val SolPurpleDim      = Color(0xFF7A2FE0)
val SolPurpleBg       = Color(0xFF1E1236)   // подложка под фиолетовый акцент

val SolGreen          = Color(0xFF14F195)   // фирменный зелёный Solana
val SolGreenDim       = Color(0xFF0EC27A)
val SolGreenBg        = Color(0xFF0A2119)   // подложка под зелёный акцент

// ─── Dark Background Scale (фиолетово-чёрный, вайб Phantom) ───────────────────
val DarkBg            = Color(0xFF0E0B14)   // фон страницы
val DarkSurface       = Color(0xFF171322)   // карточка
val DarkSurfaceVar    = Color(0xFF1E1930)   // приподнятая карточка
val DarkBorder        = Color(0xFF2C2440)   // рамки/разделители

// ─── Text ─────────────────────────────────────────────────────────────────────
val TextPrimary       = Color(0xFFF2EFF7)
val TextSecondary     = Color(0xFF9D95B3)
val TextMuted         = Color(0xFF574F6B)

// ─── Semantic Colors ──────────────────────────────────────────────────────────
val SuccessGreen      = SolGreen
val SuccessGreenBg    = SolGreenBg

val DangerRed         = Color(0xFFFF4976)
val DangerRedBg       = Color(0xFF2A0F18)

val WarnAmber         = Color(0xFFFFB224)
val WarnAmberBg       = Color(0xFF241A05)

// ─── Gradients (акценты, кнопки, индикаторы) ──────────────────────────────────
val SolanaGradient = Brush.linearGradient(listOf(SolPurple, SolGreen))

/** Полупрозрачный вариант для фонов карточек/баннеров. */
val SolanaGradientSoft = Brush.linearGradient(
    listOf(SolPurple.copy(alpha = 0.22f), SolGreen.copy(alpha = 0.10f))
)

/** Вертикальное свечение для подложек графиков (purple → прозрачный). */
val SolanaGlowVertical = Brush.verticalGradient(
    listOf(SolPurple.copy(alpha = 0.25f), Color.Transparent)
)

// ─── Dark Color Scheme ────────────────────────────────────────────────────────
private val AppColorScheme = darkColorScheme(
    primary              = SolPurple,
    onPrimary            = Color.White,
    primaryContainer     = SolPurpleBg,
    onPrimaryContainer   = Color(0xFFCFA9FF),

    secondary            = SolGreen,
    onSecondary          = Color(0xFF00281A),
    secondaryContainer   = SolGreenBg,
    onSecondaryContainer = SolGreen,

    tertiary             = Color(0xFF58A6FF),
    onTertiary           = Color(0xFF001B3E),
    tertiaryContainer    = Color(0xFF0E1A2E),
    onTertiaryContainer  = Color(0xFF9CC8FF),

    error                = DangerRed,
    errorContainer       = DangerRedBg,
    onError              = Color.White,
    onErrorContainer     = Color(0xFFFFB3C2),

    background           = DarkBg,
    onBackground         = TextPrimary,

    surface              = DarkSurface,
    onSurface            = TextPrimary,
    surfaceVariant       = DarkSurfaceVar,
    onSurfaceVariant     = TextSecondary,

    outline              = DarkBorder,
    outlineVariant       = Color(0xFF221B33),

    inverseSurface       = TextPrimary,
    inverseOnSurface     = DarkBg,
    inversePrimary       = SolPurpleDim,
)

// ─── Typography ───────────────────────────────────────────────────────────────
private val AppTypography = Typography(
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 22.sp, letterSpacing = (-0.3).sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 13.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 14.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 12.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.5.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 11.sp, letterSpacing = 0.3.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 10.sp, letterSpacing = 0.3.sp),
)

// ─── Theme Entry Point ────────────────────────────────────────────────────────
@Composable
fun SolTradBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography  = AppTypography,
        content     = content,
    )
}
