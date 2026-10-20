package tj.khujand.solana.trading.bot.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.domain.backtest.Backtester
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/** Выходы «по иксам»: ступенчатый трейлинг и выход «нет нового максимума» (общие правила decideExit). */
class TieredExitTest {

    private val hour = 3_600_000L
    private val t0 = 1_700_000_000_000L

    private val cfg = StrategyConfig(
        id = "mp",
        stopLossPercent = 25.0,
        runnerEnabled = true,
        tieredTrailEnabled = true,
        staleExitEnabled = true,
        staleExitMinutes = 1440,
    )

    private fun position(peak: Double, peakAt: Long = t0, stop: Double = 0.8) = PositionState(
        entryPrice = 1.0, initialStop = 0.8, stopLoss = stop, takeProfit = 100.0, peakPrice = peak,
        tp1Done = false, tp2Done = false, entryLiquidityUsd = 0.0, entryAtrFrac = 0.0,
        openedAtMs = t0, peakAtMs = peakAt,
    )

    @Test fun trailPercentTightensWithMultiple() {
        assertEquals(40.0, tieredTrailPercent(1.5, 40.0, 30.0, 20.0, 15.0))
        assertEquals(30.0, tieredTrailPercent(2.0, 40.0, 30.0, 20.0, 15.0))
        assertEquals(20.0, tieredTrailPercent(7.0, 40.0, 30.0, 20.0, 15.0))
        assertEquals(15.0, tieredTrailPercent(12.0, 40.0, 30.0, 20.0, 15.0))
    }

    @Test fun staleStepsAtSixthAndHalfOfTerm() {
        assertEquals(0, staleSteps(3 * hour, 1440))
        assertEquals(1, staleSteps(4 * hour, 1440))
        assertEquals(2, staleSteps(12 * hour, 1440))
        assertEquals(0, staleSteps(30 * hour, 0)) // срок не задан
        assertEquals(20.0, tightenedTrailPercent(30.0, 1))
        assertEquals(5.0, tightenedTrailPercent(12.0, 2), "не уже 5%")
        assertEquals(3.0, tightenedTrailPercent(3.0, 2), "и не шире исходного")
    }

    @Test fun tieredTrailStopsOutBelowPeak() {
        // Пик 3x → отступ 30% → стоп 2.1; цена 2.05 — выход трейлингом по уровню.
        val d = decideExit(position(peak = 3.0), cfg, price = 2.05, liquidity = null, nowMs = t0 + hour)
        assertEquals(2.1, d.stop, 1e-9)
        val action = assertIs<ExitAction.CloseAll>(d.action)
        assertEquals("Trailing Stop", action.reason)
        assertEquals(2.1, action.level!!, 1e-9)
    }

    @Test fun staleHighTightensTrail() {
        // Пик 1.5x: свежий — отступ 40% (стоп 0.9), 4 ч без нового пика — 30% (стоп 1.05).
        val fresh = decideExit(position(peak = 1.5), cfg, price = 1.2, liquidity = null, nowMs = t0 + hour)
        assertEquals(0.9, fresh.stop, 1e-9)
        assertEquals(ExitAction.Hold, fresh.action)
        val stale = decideExit(position(peak = 1.5), cfg, price = 1.2, liquidity = null, nowMs = t0 + 4 * hour)
        assertEquals(1.05, stale.stop, 1e-9)
        assertEquals(ExitAction.Hold, stale.action)
    }

    @Test fun exitsAfterFullTermWithoutNewHigh() {
        val d = decideExit(position(peak = 1.5), cfg, price = 1.4, liquidity = null, nowMs = t0 + 24 * hour)
        val action = assertIs<ExitAction.CloseAll>(d.action)
        assertTrue(action.reason.startsWith("Нет нового максимума"), action.reason)
        assertEquals(null, action.level)
    }

    @Test fun newHighResetsClock() {
        val d = decideExit(position(peak = 1.5), cfg, price = 1.6, liquidity = null, nowMs = t0 + 24 * hour)
        assertEquals(ExitAction.Hold, d.action)
        assertEquals(1.6, d.peak, 1e-9)
        assertEquals(t0 + 24 * hour, d.peakAtMs)
    }

    @Test fun legacyTradeCountsFromEntry() {
        // peak_at = 0 у старых сделок: отсчёт от открытия.
        val d = decideExit(position(peak = 1.0, peakAt = 0L), cfg, price = 0.95, liquidity = null, nowMs = t0 + 25 * hour)
        assertTrue(assertIs<ExitAction.CloseAll>(d.action).reason.startsWith("Нет нового максимума"))
        assertEquals(t0, d.peakAtMs)
    }

    @Test fun offByDefault() {
        val plain = StrategyConfig(id = "plain", runnerEnabled = true)
        val d = decideExit(position(peak = 3.0), plain, price = 2.05, liquidity = null, nowMs = t0 + 48 * hour)
        assertEquals(ExitAction.Hold, d.action)
        assertEquals(0.8, d.stop, 1e-9)
    }

    @Test fun backtestTracksTimeOfPeak() {
        // Рост до 1.5 за первый час, затем сутки боком у 1.3: бэктест закрывает по «нет нового максимума».
        val bar = hour
        val candles = listOf(Candle(t0, 1.0, 1.5, 1.0, 1.45, 100.0)) +
            (1..30).map { Candle(t0 + it * bar, 1.3, 1.32, 1.28, 1.3, 100.0) }
        val start = position(peak = 1.0, peakAt = t0).copy(stopLoss = 0.8)
        val sim = Backtester.simulate(start, cfg, candles, fromIndex = 0, tfMs = bar)
        assertTrue(sim.fills.single().reason.startsWith("Нет нового максимума"), sim.fills.single().reason)
        assertTrue(sim.exitIndex in 23..25, "выход через ~24 ч после пика: бар ${sim.exitIndex}")
    }
}
