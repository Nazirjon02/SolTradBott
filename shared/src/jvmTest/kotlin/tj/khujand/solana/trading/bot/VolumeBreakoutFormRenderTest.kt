package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Форма стратегии «Пробой на объёме» реально рисуется (Skia без окна, как [AnalysisRenderTest]).
 * PNG — в `build/strategy-render/`, чтобы посмотреть раздел параметров глазами.
 */
class VolumeBreakoutFormRenderTest {

    @Test fun volumeBreakoutFormRenders() {
        val scene = ImageComposeScene(width = 780, height = 11_000, density = Density(2f)) {
            MaterialTheme(colorScheme = darkColorScheme(background = BgDark, surface = SurfaceCard)) {
                Column(Modifier.fillMaxSize().background(BgDark)) {
                    StrategyFormScreen(initial = volumeBreakoutDefaults(), onSave = {}, onCancel = {})
                }
            }
        }
        val png = try {
            scene.render()
            scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
        File("build/strategy-render").apply { mkdirs() }.resolve("volume-breakout-form-phone.png").writeBytes(png)
        assertTrue(png.size > 10_000)
    }
}
