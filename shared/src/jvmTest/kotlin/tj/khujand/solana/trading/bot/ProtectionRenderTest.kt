package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import tj.khujand.solana.trading.bot.data.OpenPosition

/**
 * Настройки с разделом «Защита сделок» и карточка позиции с неудачной продажей реально рисуются
 * (Skia без окна, как [AnalysisRenderTest]). PNG — в `build/protection-render/`, чтобы посмотреть глазами.
 */
class ProtectionRenderTest {

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            MaterialTheme(colorScheme = darkColorScheme(background = BgDark, surface = SurfaceCard)) {
                Column(Modifier.fillMaxSize().background(BgDark)) { content() }
            }
        }
        val bytes = try {
            scene.render()
            scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
        val dir = File("build/protection-render").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(bytes)
        return bytes
    }

    @Test fun settingsWithProtectionSectionRender() {
        val png = render("settings-shadow-phone", 780, 6400) {
            SettingsScreen(settings = DrxSettings(), demoMode = true, shadowMode = true, initialCategory = SettingsCategory.PROTECTION, onSave = {})
        }
        assertTrue(png.size > 10_000)
    }

    @Test fun positionWithBlockedExitRenders() {
        val pos = OpenPosition(
            tradeId = "t1", mint = "So1aNaMemeMint1111111111111111111111111111", symbol = "MEME",
            strategyName = "Пробой + откат", entryPrice = 0.001, currentPrice = 0.0008, qtyRemaining = 98_000.0,
            sizeUsd = 100.0, valueUsd = 78.4, pnlUsd = -21.6, pnlPercent = -21.6, stopLoss = 0.00085,
            takeProfit = 0.0013, isDemo = true, openedAt = 1_760_000_000_000L,
            exitFailures = 3, exitNote = "нет маршрута продажи (COULD_NOT_FIND_ANY_ROUTE)", execMode = "SHADOW",
        )
        val png = render("position-exit-blocked-phone", 780, 900) { PositionCard(pos, onClose = {}) }
        assertTrue(png.size > 5_000)
    }
}
