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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.skia.EncodedImageFormat
import tj.khujand.solana.trading.bot.core.engine.RunSummary

/**
 * «Новый старт» и календарь P&L реально рисуются (Skia без окна, как [AnalysisRenderTest]).
 * PNG — в `build/runs-render/`, чтобы посмотреть вёрстку глазами.
 */
class RunsRenderTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 11, 3)

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
        val dir = File("build/runs-render").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(bytes)
        return bytes
    }

    private fun dayMs(day: Int, hour: Int = 12) =
        LocalDate(2026, 10, day).atStartOfDayIn(zone).toEpochMilliseconds() + hour * 3_600_000L

    private fun trade(pnl: Double, closedAt: Long) = TradeHistoryItem(
        symbol = "MEME", strategy = "Пробой + откат", entryPrice = 1.0, exitPrice = 1.0 + pnl / 100, pnl = pnl,
        pnlPercent = pnl, sizeUsd = 100.0, isDemo = true, duration = "2ч 5м", isWin = pnl > 0,
        openReason = "test", closeReason = "Take Profit", closedAt = closedAt, openedAt = closedAt - 7_500_000L,
    )

    private val trades = listOf(
        trade(42.5, dayMs(2)), trade(-12.0, dayMs(3)), trade(-30.4, dayMs(7)), trade(8.0, dayMs(7, 15)),
        trade(120.0, dayMs(14)), trade(-55.0, dayMs(21)), trade(15.3, dayMs(28)), trade(3.0, dayMs(31)),
    )

    private val runs = listOf(
        RunSummary(1, dayMs(1), dayMs(20), 10_000.0, 10_150.0, "", 5, 3, 128.1, 2.5, listOf("Пробой + откат", "Dars")),
        RunSummary(2, dayMs(20), null, 10_150.0, null, "", 3, 2, -36.7, 1.5, listOf("Пробой + откат")),
    )

    @Test fun calendarMonthRenders() {
        val png = render("pnl-calendar-phone", 780, 1700) {
            PnlCalendarScreen(trades, runStarts = runs.map { it.startedAt }, today = LocalDate(2026, 10, 31), zone = zone,
                scopeLabel = "Забег №2 (DEMO)", onClose = {})
        }
        assertTrue(png.size > 10_000)
    }

    @Test fun calendarEntryAndRunsRender() {
        val png = render("stats-runs-phone", 780, 900) {
            PnlCalendarEntryCard(trades, LocalDate(2026, 10, 31), zone) {}
            RunScopeChips(currentRun = true, runNumber = 2) {}
            RunsCard(runs, "DEMO")
        }
        assertTrue(png.size > 5_000)
    }

    @Test fun newRunSectionRenders() {
        val png = render("settings-new-run-phone", 780, 700) {
            NewRunSection(demoMode = true) { "🏁 Новый старт №3 (DEMO)" }
        }
        assertTrue(png.size > 5_000)
    }
}
