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
import tj.khujand.solana.trading.bot.core.engine.PositionState
import tj.khujand.solana.trading.bot.core.engine.tradeLines
import tj.khujand.solana.trading.bot.core.strategy.CheckResult
import tj.khujand.solana.trading.bot.core.strategy.Evaluation
import tj.khujand.solana.trading.bot.core.strategy.ReversalFixtures
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.WatchItem
import tj.khujand.solana.trading.bot.core.strategy.WatchStatus

/**
 * График бота и доска наблюдения реально рисуются (Skia без окна, как [AnalysisRenderTest]).
 * PNG — в `build/bot-chart-render/`, чтобы посмотреть вёрстку глазами.
 */
class BotChartRenderTest {

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
        val dir = File("build/bot-chart-render").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(bytes)
        return bytes
    }

    @Test fun chartWithTradeAndChecklistRenders() {
        val candles = ReversalFixtures.candles(ReversalFixtures.uptrend())
        val entry = candles[40].close
        val position = PositionState(
            entryPrice = entry, initialStop = entry * 0.9, stopLoss = entry * 0.97, takeProfit = entry * 1.6, peakPrice = entry * 1.3,
            tp1Done = true, tp2Done = false, entryLiquidityUsd = 0.0, entryAtrFrac = 0.04, openedAtMs = candles[40].openTimeMs,
        )
        val cfg = StrategyConfig(id = "s", name = "Пробой + откат", rLadderEnabled = true, breakEvenAfterTp1 = true, atrTrailEnabled = true)
        val last = candles.last().close
        val trade = ChartTradeInfo(
            entry = entry, stopNow = position.stopLoss, initialStop = position.initialStop,
            pnlPercent = (last / entry - 1) * 100, rNow = (last - entry) / (entry - position.initialStop),
            lines = tradeLines(position, cfg),
        )
        val evaluation = Evaluation(
            passed = 10, total = 12, missing = "Окно входа: волна 3 началась 5 баров назад, окно 3",
            checks = listOf(
                CheckResult("Счёт волн", true, "1-2, идёт 3-я"),
                CheckResult("Волна 2 не за началом волны 1", true, "минимум 1.12 > 1.06"),
                CheckResult("Окно входа", false, "волна 3 началась 5 баров назад, окно 3"),
            ),
        )
        val png = render("bot-chart-phone", 780, 2600) {
            BotChartContent(
                symbol = "MEME", strategyName = cfg.name, workTf = "5m", tf = "5m", candles = candles,
                lastBarForming = false, evaluation = evaluation, trade = trade, status = "",
                onTimeframe = {}, onClose = {},
            )
        }
        assertTrue(png.size > 20_000)
    }

    @Test fun watchBoardRenders() {
        fun item(sym: String, status: WatchStatus, passed: Int, missing: String?, level: Double?) = WatchItem(
            mint = sym, symbol = sym, pairAddress = "p", strategyId = "s", strategyName = "Пробой на объёме",
            timeframe = "5m", price = 1.0, entryLevel = level, passed = passed, total = 8, missing = missing,
            status = status, updatedAt = 0L,
        )
        val items = listOf(
            item("BONK", WatchStatus.READY, 8, null, 0.98),
            item("WIF", WatchStatus.NEAR, 6, "объём пробоя ×1.8 < ×2.5 среднего", 1.012),
            item("POPCAT", WatchStatus.WAITING, 5, "закрытие не выше прежнего максимума", 1.08),
            item("MEW", WatchStatus.NONE, 2, "отката не было: −1.2% от максимума < 5%", null),
        )
        val png = render("watch-board-phone", 780, 900) { WatchBoardCard(items, running = true) }
        assertTrue(png.size > 5_000)
    }
}
