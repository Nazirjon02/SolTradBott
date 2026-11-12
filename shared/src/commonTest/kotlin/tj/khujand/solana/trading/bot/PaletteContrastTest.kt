package tj.khujand.solana.trading.bot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Текст палитры держит контраст WCAG AA (4.5:1) там, где он на самом деле рисуется: на фоне, на карточке, на
 * панели «Сохранить», на плашке и на сообщении своего цвета. Серый текст на карточке был 3.7:1 — тест не даст
 * вернуть такое при следующей правке цветов.
 */
class PaletteContrastTest {

    private fun contrast(text: Color, background: Color): Double {
        val a = text.compositeOver(background).luminance().toDouble()
        val b = background.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun assertReadable(name: String, text: Color, background: Color) {
        val ratio = contrast(text, background)
        assertTrue(ratio >= 4.5, "$name: контраст ${(ratio * 100).toInt() / 100.0} < 4.5")
    }

    private val accents = mapOf("зелёный" to Green, "красный" to Red, "синий" to Blue, "фиолетовый" to Purple, "янтарный" to Amber)

    @Test
    fun secondaryTextIsReadableOnEverySurface() {
        assertReadable("серый текст на карточке", TextSecondary, SurfaceCard)
        assertReadable("серый текст на фоне", TextSecondary, BgDark)
        assertReadable("серый текст на панели «Сохранить»", TextSecondary, SurfaceRaised)
        assertReadable("основной текст на карточке", TextPrimary, SurfaceCard)
    }

    @Test
    fun accentTextIsReadableOnCardAndBackground() {
        accents.forEach { (name, color) ->
            assertReadable("$name на карточке", color, SurfaceCard)
            assertReadable("$name на фоне", color, BgDark)
            assertReadable("$name на панели «Сохранить» и выбранном сегменте", color, SurfaceRaised)
        }
    }

    @Test
    fun pillAndBannerTextIsReadableOnOwnTint() {
        (accents + ("серый" to TextSecondary)).forEach { (name, color) ->
            val pill = color.copy(alpha = PillTintAlpha).compositeOver(SurfaceCard)
            val banner = color.copy(alpha = BannerTintAlpha).compositeOver(SurfaceCard)
            assertReadable("$name на своей плашке", color, pill)
            assertReadable("$name на своём сообщении", color, banner)
        }
    }

    @Test
    fun statusChipsAndFilledButtonsAreReadable() {
        // «РАБОТАЕТ / ПАУЗА / ОСТАНОВЛЕН» в шапке — подложка 15% поверх фона экрана.
        listOf("зелёный" to Green, "янтарный" to Amber, "красный" to Red).forEach { (name, color) ->
            assertReadable("$name в чипе статуса", color, color.copy(alpha = 0.15f).compositeOver(BgDark))
        }
        // Залитые кнопки («Старт», «Сохранить», подтверждения) — чёрный текст на цвете.
        accents.forEach { (name, color) -> assertReadable("чёрный текст на кнопке: $name", Color.Black, color) }
    }
}
