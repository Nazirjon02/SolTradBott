package tj.khujand.solana.trading.bot.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig

/** Линии графика бота повторяют правила выхода стратегии: что выключено — того на графике нет. */
class TradeOverlayTest {

    /** Вход 1.0, стоп при входе 0.9 (R = 0.1), стоп уже подтянут к 0.95. */
    private val position = PositionState(
        entryPrice = 1.0, initialStop = 0.9, stopLoss = 0.95, takeProfit = 1.5, peakPrice = 1.2,
        tp1Done = true, tp2Done = false, entryLiquidityUsd = 0.0, entryAtrFrac = 0.05, openedAtMs = 0L,
    )

    private fun kinds(lines: List<TradeLine>) = lines.map { it.kind }

    @Test
    fun rLadderAndTrailsBecomeLines() {
        val cfg = StrategyConfig(
            id = "s", rLadderEnabled = true, tp1R = 1.5, tp2R = 3.0, tp1ClosePercent = 40.0, breakEvenAfterTp1 = true,
            atrTrailEnabled = true, atrTrailActivationR = 1.0, breakEvenEnabled = true, breakEvenTriggerPercent = 10.0,
        )
        val lines = tradeLines(position, cfg)
        assertEquals(
            listOf(
                TradeLineKind.ENTRY, TradeLineKind.STOP_NOW, TradeLineKind.STOP_INITIAL, TradeLineKind.ONE_R,
                TradeLineKind.BREAK_EVEN, TradeLineKind.TRAIL_START, TradeLineKind.PARTIAL, TradeLineKind.PARTIAL, TradeLineKind.TAKE,
            ),
            kinds(lines),
        )
        val tp1 = lines.first { it.kind == TradeLineKind.PARTIAL }
        assertEquals(1.15, tp1.price, 1e-9)
        assertTrue(tp1.reached, "TP1 уже взят")
        assertTrue(tp1.label.contains("БУ"), tp1.label)
        assertEquals(1.1, lines.first { it.kind == TradeLineKind.ONE_R }.price, 1e-9)
        assertEquals(1.1, lines.first { it.kind == TradeLineKind.TRAIL_START }.price, 1e-9) // ATR-трейлинг с +1R
    }

    @Test
    fun disabledRulesDrawNothingAndRunnerHasNoTarget() {
        val cfg = StrategyConfig(id = "s", runnerEnabled = true)
        val lines = tradeLines(position.copy(stopLoss = 0.9), cfg)
        assertEquals(listOf(TradeLineKind.ENTRY, TradeLineKind.STOP_NOW, TradeLineKind.ONE_R), kinds(lines))
        assertFalse(lines.any { it.kind == TradeLineKind.TAKE }, "раннер — основной тейк не закрывает позицию")
    }
}
