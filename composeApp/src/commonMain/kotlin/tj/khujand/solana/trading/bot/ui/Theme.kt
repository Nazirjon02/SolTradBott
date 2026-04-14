package tj.khujand.solana.trading.bot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Brand Palette ────────────────────────────────────────────────────────────
val BrandIndigo        = Color(0xFF5B61F5)
val BrandIndigoLight   = Color(0xFFEEF0FF)
val BrandIndigoDark    = Color(0xFF312E81)

val BrandTeal          = Color(0xFF00BFA5)
val BrandTealLight     = Color(0xFFE0F7F4)
val BrandTealDark      = Color(0xFF00695C)

val BrandPurple        = Color(0xFF9747FF)
val BrandPurpleLight   = Color(0xFFF3E8FF)
val BrandPurpleDark    = Color(0xFF6B21A8)

// ─── Semantic Colors ──────────────────────────────────────────────────────────
val SuccessGreen       = Color(0xFF22C55E)
val SuccessGreenBg     = Color(0xFFF0FDF4)
val SuccessGreenDark   = Color(0xFF15803D)

val DangerRed          = Color(0xFFEF4444)
val DangerRedBg        = Color(0xFFFEF2F2)
val DangerRedDark      = Color(0xFFB91C1C)

val WarnAmber          = Color(0xFFF59E0B)
val WarnAmberBg        = Color(0xFFFFFBEB)

// ─── Neutral Surfaces ─────────────────────────────────────────────────────────
val NeutralBg          = Color(0xFFF7F8FF)   // page background – very subtle blue tint
val NeutralCard        = Color(0xFFFFFFFF)
val NeutralBorder      = Color(0xFFE4E7F5)

val TextPrimary        = Color(0xFF1A1D4E)
val TextSecondary      = Color(0xFF5C6080)
val TextMuted          = Color(0xFF9CA3AF)

// ─── Color Scheme ─────────────────────────────────────────────────────────────
private val AppColorScheme = lightColorScheme(
    primary              = BrandIndigo,
    onPrimary            = Color.White,
    primaryContainer     = BrandIndigoLight,
    onPrimaryContainer   = BrandIndigoDark,

    secondary            = BrandTeal,
    onSecondary          = Color.White,
    secondaryContainer   = BrandTealLight,
    onSecondaryContainer = BrandTealDark,

    tertiary             = BrandPurple,
    onTertiary           = Color.White,
    tertiaryContainer    = BrandPurpleLight,
    onTertiaryContainer  = BrandPurpleDark,

    error                = DangerRed,
    errorContainer       = DangerRedBg,
    onError              = Color.White,
    onErrorContainer     = DangerRedDark,

    background           = NeutralBg,
    onBackground         = TextPrimary,

    surface              = NeutralCard,
    onSurface            = TextPrimary,
    surfaceVariant       = BrandIndigoLight,
    onSurfaceVariant     = TextSecondary,

    outline              = NeutralBorder,
    outlineVariant       = Color(0xFFF0F2FF),

    inverseSurface       = TextPrimary,
    inverseOnSurface     = Color.White,
    inversePrimary       = Color(0xFFBBBEFF),
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
