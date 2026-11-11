package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import tj.khujand.solana.trading.bot.exchange.chain.ChainCandleStatus
import tj.khujand.solana.trading.bot.exchange.chain.SkipReason
import tj.khujand.solana.trading.bot.exchange.chain.UsageSnapshot

/** Раздел «Свечи из блокчейна» рисуется (Skia без окна); PNG — в `build/chain-render/`, посмотреть глазами. */
class ChainCandlesRenderTest {

    private fun render(name: String, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = 780, height = height, density = Density(2f)) {
            MaterialTheme(colorScheme = darkColorScheme(background = BgDark, surface = SurfaceCard)) {
                Column(Modifier.fillMaxSize().background(BgDark).padding(16.dp)) { content() }
            }
        }
        val bytes = try {
            scene.render()
            scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
        File("build/chain-render").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
        return bytes
    }

    @Test fun runningSectionRenders() {
        val status = ChainCandleStatus(
            running = true,
            connected = true,
            host = "mainnet.helius-rpc.com",
            helius = true,
            poolsLive = 31,
            poolsWatched = 59,
            skipped = mapOf(SkipReason.QUOTE to 7, SkipReason.EXPENSIVE to 3, SkipReason.OVER_BUDGET to 9, SkipReason.UNSUPPORTED_DEX to 5),
            tradesToday = 4_812,
            usage = UsageSnapshot(creditsToday = 9_431.0, creditsPerDayNow = 22_140.0),
            dailyLimit = 25_000,
            reconnects = 1,
            lastReconcile = "сверка 5m: 24 пулов, 560 баров; close ±1% — 97.5%, объём ±1% — 91.2%; пропущено баров 3, лишних 2",
        )
        val png = render("chain-running", 1500) {
            ChainCandlesSectionContent(status, enabled = true, limit = "25000", onToggle = {}, onLimit = {})
        }
        assertTrue(png.size > 10_000)
    }

    @Test fun disabledSectionRenders() {
        val png = render("chain-off", 900) {
            ChainCandlesSectionContent(ChainCandleStatus(), enabled = false, limit = "25000", onToggle = {}, onLimit = {})
        }
        assertTrue(png.size > 5_000)
    }
}
