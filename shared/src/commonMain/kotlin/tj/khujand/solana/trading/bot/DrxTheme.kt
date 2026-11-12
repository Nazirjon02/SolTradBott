package tj.khujand.solana.trading.bot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ─── Палитра ──────────────────────────────────────────────────────────────────
//
// Основа — палитра MRX. Цвета текста подняты до контраста WCAG AA (4.5:1) на карточке [SurfaceCard]:
// серый был 3.7:1 и на телефоне при ярком свете читался плохо; красный, синий и фиолетовый — чтобы
// подписи на собственной подложке (плашки «УБЫТОК», DEMO, «ЖДЁМ») тоже держали 4.5:1. Проверяет PaletteContrastTest.

internal val BgDark        = Color(0xFF0A0E1A)
internal val SurfaceCard   = Color(0xFF1A2235)
/** Поверх карточки: выбранный сегмент переключателя, панель «Сохранить», выключенные кнопки. */
internal val SurfaceRaised = Color(0xFF232D44)
internal val Green         = Color(0xFF00D97E)
internal val GreenDim      = Green.copy(alpha = 0.15f)
internal val Red           = Color(0xFFFF6672)
internal val RedDim        = Red.copy(alpha = 0.15f)
internal val Blue          = Color(0xFF5AA5FF)
internal val Purple        = Color(0xFFA98FFF)
internal val Amber         = Color(0xFFF59E0B)
internal val TextPrimary   = Color(0xFFEAF0FB)
/** 5.7:1 на [SurfaceCard], 6.9:1 на [BgDark]. */
internal val TextSecondary = Color(0xFF8E9BB8)
internal val BorderColor   = Color(0xFF1E2D45)

/** Подложка плашек ([Pill]) и сообщений ([Banner]): доля цвета текста поверх карточки. Подобраны так, чтобы текст держал 4.5:1. */
internal const val PillTintAlpha = 0.13f
internal const val BannerTintAlpha = 0.12f

/** С какой ширины окна интерфейс раскладывается в две колонки и навигация уходит в боковую панель. */
internal val WideLayoutMinWidth = 840.dp

/** Предел ширины одной колонки текста: дальше строки становятся нечитаемо длинными. */
internal val ContentMaxWidth = 760.dp

/** Тема приложения: тёмная схема Material 3 целиком из палитры, чтобы диалоги, меню и поля не брали чужих цветов. */
@Composable
fun DrxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            onPrimary = Color.Black,
            primaryContainer = GreenDim,
            onPrimaryContainer = Green,
            secondary = Blue,
            onSecondary = Color.Black,
            tertiary = Purple,
            onTertiary = Color.Black,
            error = Red,
            onError = Color.Black,
            background = BgDark,
            onBackground = TextPrimary,
            surface = SurfaceCard,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceRaised,
            onSurfaceVariant = TextSecondary,
            surfaceContainerLowest = BgDark,
            surfaceContainerLow = SurfaceCard,
            surfaceContainer = SurfaceCard,
            surfaceContainerHigh = SurfaceRaised,
            surfaceContainerHighest = SurfaceRaised,
            inverseSurface = TextPrimary,
            inverseOnSurface = BgDark,
            outline = BorderColor,
            outlineVariant = BorderColor,
            scrim = Color.Black,
        ),
        content = content,
    )
}
