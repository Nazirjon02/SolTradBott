package tj.khujand.solana.trading.bot.domain.backtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.core.engine.PositionState
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/** Сопровождение позиции в бэктесте: исполнение по уровням, гэпы, лестница, издержки. */
class BacktesterSimulateTest {

    private val tf = 300_000L
    private val coin = BacktestCoin("pool", "mint", "SYM", "SYM / SOL", "raydium", 0L, 1.0, 1e6, 1e5)

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) =
        Candle(i * tf, o, h, l, c, 100.0)

    /** Вход 1.0, стоп 0.9 → R = 0.1. */
    private fun position(takeProfit: Double = 1.3) = PositionState(
        entryPrice = 1.0, initialStop = 0.9, stopLoss = 0.9, takeProfit = takeProfit, peakPrice = 1.0,
        tp1Done = false, tp2Done = false, entryLiquidityUsd = 0.0, entryAtrFrac = 0.05, openedAtMs = 0L,
    )

    private fun trade(sim: Backtester.SimResult, cost: Double = 0.0) =
        Backtester.toTrade(coin, position(), sim, cost, "test")

    @Test
    fun stopInsideBarFillsAtStopLevel() {
        val bars = listOf(bar(0, 1.0, 1.02, 0.85, 0.88)) // медвежья: open → high → low (0.85 < стопа)
        val sim = Backtester.simulate(position(), StrategyConfig(id = "s"), bars, 0, tf)
        assertEquals(0.9, sim.fills.single().price, 1e-9)
        assertEquals("Stop Loss", sim.fills.single().reason)
        assertEquals(-1.0, trade(sim).rMultiple, 1e-9)
    }

    @Test
    fun gapThroughStopFillsAtOpen() {
        val bars = listOf(bar(0, 1.0, 1.01, 0.99, 1.0), bar(1, 0.80, 0.82, 0.78, 0.81))
        val sim = Backtester.simulate(position(), StrategyConfig(id = "s"), bars, 0, tf)
        assertEquals(0.80, sim.fills.single().price, 1e-9) // хуже стопа — как на реальном гэпе
        assertEquals(-2.0, trade(sim).rMultiple, 1e-9)
    }

    @Test
    fun takeProfitFillsAtLevel() {
        val bars = listOf(bar(0, 1.0, 1.4, 0.99, 1.35)) // бычья: open → low → high (выше TP 1.3)
        val sim = Backtester.simulate(position(), StrategyConfig(id = "s"), bars, 0, tf)
        assertEquals(1.3, sim.fills.single().price, 1e-9)
        assertEquals(3.0, trade(sim).rMultiple, 1e-9)
    }

    @Test
    fun costsReduceResult() {
        val bars = listOf(bar(0, 1.0, 1.02, 0.85, 0.88))
        val sim = Backtester.simulate(position(), StrategyConfig(id = "s"), bars, 0, tf)
        // (0.9 × 0.99 − 1.0 × 1.01) / 0.1 = −1.19R
        assertEquals(-1.19, trade(sim, cost = 0.01).rMultiple, 1e-9)
    }

    @Test
    fun ladderTp1AndTp2InOneBarThenRunnerTrails() {
        val cfg = StrategyConfig(
            id = "r", tp1ClosePercent = 40.0, tp2ClosePercent = 30.0, breakEvenOffsetPercent = 2.0,
            rLadderEnabled = true, tp1R = 1.5, tp2R = 3.0, breakEvenAfterTp1 = true,
            runnerEnabled = true, atrTrailEnabled = true, atrTrailMult = 3.0, atrTrailActivationR = 1.0,
        )
        val bars = listOf(
            bar(0, 1.0, 1.35, 0.99, 1.33),  // TP1 (1.15) и TP2 (1.30) в одной свече
            bar(1, 1.33, 1.60, 1.32, 1.58), // пик 1.60 → трейлинг 1.60 × (1 − 0.15) = 1.36
            bar(2, 1.58, 1.59, 1.30, 1.31), // откат ниже трейлинга → раннер закрыт по 1.36
        )
        val sim = Backtester.simulate(position(takeProfit = 2.0), cfg, bars, 0, tf)
        assertEquals(listOf("TP1 +1.5R", "TP2 +3R", "Trailing Stop"), sim.fills.map { it.reason })
        assertEquals(listOf(1.15, 1.30, 1.36), sim.fills.map { it.price }.map { kotlin.math.round(it * 100) / 100 })
        assertEquals(1.0, sim.fills.sumOf { it.portion }, 1e-9)
        // 0.4 × 1.5R + 0.3 × 3R + 0.3 × 3.6R = 2.58R
        assertEquals(2.58, trade(sim).rMultiple, 1e-6)
        assertEquals(2, sim.exitIndex)
    }

    @Test
    fun openPositionClosedAtEndOfData() {
        val bars = listOf(bar(0, 1.0, 1.05, 0.98, 1.04), bar(1, 1.04, 1.08, 1.02, 1.06))
        val sim = Backtester.simulate(position(), StrategyConfig(id = "s"), bars, 0, tf)
        assertEquals("Конец данных", sim.fills.single().reason)
        assertEquals(1.06, sim.fills.single().price, 1e-9)
        assertTrue(trade(sim).rMultiple > 0)
    }

    @Test
    fun statsFromTrades() {
        fun t(r: Double, exit: Long) = BacktestTrade("S", "p", 0, exit, 1.0, 0.9, emptyList(), r, r * 10, "x", "y")
        val s = BacktestStats.from(listOf(t(2.0, 1), t(-1.0, 2), t(-1.0, 3), t(3.0, 4)))
        assertEquals(4, s.trades)
        assertEquals(50.0, s.winRate, 1e-9)
        assertEquals(0.75, s.expectancyR, 1e-9)
        assertEquals(2.5, s.profitFactor, 1e-9)
        assertEquals(3.0, s.totalR, 1e-9)
        // 1.02 × 0.99 × 0.99 × 1.03 − 1 = +2.969%; просадка после пика 1.02: 1 − 0.99² = 1.99%
        assertEquals(2.9693, s.equityPct, 1e-3)
        assertEquals(1.99, s.maxDrawdownPct, 1e-2)
    }
}
