package tj.khujand.solana.trading.bot.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tj.khujand.solana.trading.bot.exchange.dex.Candle

class ExitRulesTest {

    @Test
    fun riskUsesInitialStop() {
        assertEquals(8.0, riskPerUnit(entry = 100.0, initialStop = 92.0, stopLossPercent = 15.0), 1e-9)
    }

    @Test
    fun riskFallsBackToConfigForLegacyTrades() {
        // Старые сделки: initial_stop = 0 → R из SL% конфига.
        assertEquals(15.0, riskPerUnit(entry = 100.0, initialStop = 0.0, stopLossPercent = 15.0), 1e-9)
        // Некорректный стоп выше входа — тоже запасной вариант.
        assertEquals(15.0, riskPerUnit(entry = 100.0, initialStop = 105.0, stopLossPercent = 15.0), 1e-9)
    }

    @Test
    fun gainInR() {
        assertEquals(1.5, gainInR(price = 112.0, entry = 100.0, r = 8.0), 1e-9)
        assertEquals(0.0, gainInR(price = 112.0, entry = 100.0, r = 0.0), 1e-9)
    }

    @Test
    fun ladderStepsInOrder() {
        assertNull(rLadderStep(1.4, tp1Done = false, tp2Done = false, tp1R = 1.5, tp2R = 3.0))
        assertEquals(1, rLadderStep(1.5, tp1Done = false, tp2Done = false, tp1R = 1.5, tp2R = 3.0))
        // Цена сразу выше TP2, но TP1 ещё не взят — сначала TP1.
        assertEquals(1, rLadderStep(4.0, tp1Done = false, tp2Done = false, tp1R = 1.5, tp2R = 3.0))
        assertNull(rLadderStep(2.0, tp1Done = true, tp2Done = false, tp1R = 1.5, tp2R = 3.0))
        assertEquals(2, rLadderStep(3.0, tp1Done = true, tp2Done = false, tp1R = 1.5, tp2R = 3.0))
        assertNull(rLadderStep(9.0, tp1Done = true, tp2Done = true, tp1R = 1.5, tp2R = 3.0))
    }

    @Test
    fun atrTrailScalesWithVolatility() {
        // ATR 4% × 3 = 12% от пика.
        assertEquals(88.0, atrTrailStop(peak = 100.0, atrFrac = 0.04, mult = 3.0, fallbackFrac = 0.10), 1e-9)
        // Нет ATR (старая сделка) — запасной трейлинг-% конфига.
        assertEquals(90.0, atrTrailStop(peak = 100.0, atrFrac = 0.0, mult = 3.0, fallbackFrac = 0.10), 1e-9)
    }

    @Test
    fun atrTrailDistanceIsClamped() {
        // Слишком узко (0.3%) → не меньше 2%; слишком широко (90%) → не больше 50%.
        assertEquals(98.0, atrTrailStop(peak = 100.0, atrFrac = 0.001, mult = 3.0, fallbackFrac = 0.1), 1e-9)
        assertEquals(50.0, atrTrailStop(peak = 100.0, atrFrac = 0.30, mult = 3.0, fallbackFrac = 0.1), 1e-9)
    }

    @Test
    fun atrFractionNeedsEnoughCandles() {
        val flat = (0 until 30).map { Candle(it * 60_000L, 1.0, 1.02, 0.98, 1.0, 10.0) }
        assertEquals(0.04, atrFraction(flat, period = 14, price = 1.0), 1e-9)
        assertEquals(0.0, atrFraction(flat.take(5), period = 14, price = 1.0), 1e-9)
        assertEquals(0.0, atrFraction(flat, period = 14, price = 0.0), 1e-9)
    }
}
