package tj.khujand.solana.trading.bot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Dark Brand Palette ───────────────────────────────────────────────────────
val CyanAccent        = Color(0xFF00E5FF)
val CyanAccentDim     = Color(0xFF0097A7)
val CyanAccentBg      = Color(0xFF001F26)

val DarkBg            = Color(0xFF0D1117)   // page background per spec
val DarkSurface       = Color(0xFF161B22)   // card background
val DarkSurfaceVar    = Color(0xFF1C2128)   // elevated card
val DarkBorder        = Color(0xFF30363D)

val TextOnDark        = Color(0xFFE6EDF3)   // primary text
val TextOnDarkMuted   = Color(0xFF8B949E)   // secondary text
val TextOnDarkFaint   = Color(0xFF484F58)   // disabled / placeholder

// ─── Semantic Colors ──────────────────────────────────────────────────────────
val SuccessGreen      = Color(0xFF3FB950)
val SuccessGreenBg    = Color(0xFF0D1F12)

val DangerRed         = Color(0xFFF85149)
val DangerRedBg       = Color(0xFF1F0D0D)

val WarnAmber         = Color(0xFFD29922)
val WarnAmberBg       = Color(0xFF1C1600)

// ─── Legacy aliases kept for compatibility ────────────────────────────────────
val BrandIndigo       = CyanAccent
val BrandIndigoLight  = CyanAccentBg
val BrandIndigoDark   = CyanAccentDim
val BrandTeal         = CyanAccent
val BrandTealLight    = CyanAccentBg
val BrandTealDark     = CyanAccentDim
val BrandPurple       = Color(0xFF9747FF)
val BrandPurpleLight  = Color(0xFF1A0D2E)
val BrandPurpleDark   = Color(0xFF6B21A8)
val NeutralBg         = DarkBg
val NeutralCard       = DarkSurface
val NeutralBorder     = DarkBorder
val TextPrimary       = TextOnDark
val TextSecondary     = TextOnDarkMuted
val TextMuted         = TextOnDarkFaint
val SuccessGreenDark  = SuccessGreen
val DangerRedDark     = DangerRed

// ─── Dark Color Scheme ────────────────────────────────────────────────────────
private val AppColorScheme = darkColorScheme(
    primary              = CyanAccent,
    onPrimary            = Color(0xFF002023),
    primaryContainer     = CyanAccentBg,
    onPrimaryContainer   = CyanAccent,

    secondary            = Color(0xFF58A6FF),
    onSecondary          = Color(0xFF001B3E),
    secondaryContainer   = Color(0xFF001B3E),
    onSecondaryContainer = Color(0xFF58A6FF),

    tertiary             = BrandPurple,
    onTertiary           = Color(0xFF1A0D2E),
    tertiaryContainer    = BrandPurpleLight,
    onTertiaryContainer  = Color(0xFFCFB8FF),

    error                = DangerRed,
    errorContainer       = DangerRedBg,
    onError              = Color.White,
    onErrorContainer     = Color(0xFFFFB4AB),

    background           = DarkBg,
    onBackground         = TextOnDark,

    surface              = DarkSurface,
    onSurface            = TextOnDark,
    surfaceVariant       = DarkSurfaceVar,
    onSurfaceVariant     = TextOnDarkMuted,

    outline              = DarkBorder,
    outlineVariant       = Color(0xFF21262D),

    inverseSurface       = TextOnDark,
    inverseOnSurface     = DarkBg,
    inversePrimary       = CyanAccentDim,
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
