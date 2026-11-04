package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/** Синтетические графики для «выхода по развороту»: волны с явными свингами (фрактал k = 3). */
internal object ReversalFixtures {
    const val BAR_MS = 60_000L

    /**
     * Свечи по закрытиям: открытие — середина между прошлым и текущим закрытием (у соседних баров разные
     * экстремумы, поэтому вершины и днища волн — фракталы), тени по 0.001.
     */
    fun candles(closes: List<Double>, startMs: Long = 0L): List<Candle> = closes.mapIndexed { i, c ->
        val o = if (i == 0) c else (closes[i - 1] + c) / 2
        Candle(startMs + i * BAR_MS, o, maxOf(o, c) + 0.001, minOf(o, c) - 0.001, c, 100.0)
    }

    /** Аптренд: 8 волн «5 баров вверх по +0.06, 3 вниз по −0.03» от 1.0 (65 закрытий). */
    fun uptrend(): List<Double> {
        val out = mutableListOf(1.0)
        repeat(8) {
            repeat(5) { out += out.last() + 0.06 }
            repeat(3) { out += out.last() - 0.03 }
        }
        return out
    }

    /** Нисходящие волны «5 баров вниз по −0.06, 4 вверх по +0.03» — нижние максимумы и минимумы. */
    fun downWaves(from: Double, waves: Int = 7): List<Double> {
        val out = mutableListOf<Double>()
        var last = from
        repeat(waves) {
            repeat(5) { last -= 0.06; out += last }
            repeat(4) { last += 0.03; out += last }
        }
        return out
    }

    /** Аптренд, затем разворот вниз: 65 + 63 закрытия; разворот начинается с бара 65. */
    fun upThenDown(): List<Double> {
        val up = uptrend()
        return up + downWaves(up.last())
    }
}

class ReversalExitTest {

    private val bar = ReversalFixtures.BAR_MS

    @Test
    fun reversalAfterEntryIsDetected() {
        val candles = ReversalFixtures.candles(ReversalFixtures.upThenDown())
        // Вход на развороте (бар 65): нижние максимум и минимум сложились уже после него.
        assertEquals(EmaTrend.BEARISH, emaTrend(candles))
        assertTrue(marketReversedAgainstLong(candles, sinceMs = 65 * bar))
    }

    @Test
    fun steadyUptrendIsNotAReversal() {
        val candles = ReversalFixtures.candles(ReversalFixtures.uptrend())
        assertEquals(EmaTrend.BULLISH, emaTrend(candles))
        assertFalse(marketReversedAgainstLong(candles, sinceMs = 0L))
    }

    @Test
    fun structureThatExistedBeforeEntryDoesNotCloseTheTrade() {
        val candles = ReversalFixtures.candles(ReversalFixtures.upThenDown())
        // Вход после последнего бара: вся нисходящая структура была на графике ещё до входа.
        assertFalse(marketReversedAgainstLong(candles, sinceMs = candles.size * bar))
    }

    @Test
    fun tooFewCandlesNeverTrigger() {
        val candles = ReversalFixtures.candles(ReversalFixtures.upThenDown()).takeLast(40)
        assertEquals(EmaTrend.NEUTRAL, emaTrend(candles)) // EMA(50) не из чего считать
        assertFalse(marketReversedAgainstLong(candles, sinceMs = 0L))
    }

    @Test
    fun pivotsAlternateHighAndLow() {
        val pivots = fractalPivots(ReversalFixtures.candles(ReversalFixtures.upThenDown()), 3)
        assertTrue(pivots.size >= 8, "свингов ${pivots.size}")
        pivots.zipWithNext().forEach { (a, b) -> assertTrue(a.isHigh != b.isHigh, "подряд два ${if (a.isHigh) "максимума" else "минимума"}") }
    }

    @Test
    fun formingBarIsDropped() {
        val candles = ReversalFixtures.candles(listOf(1.0, 1.1, 1.2))
        // Последний бар открылся в 120 000 и закроется в 180 000 — в 170 000 он ещё формируется.
        assertEquals(2, closedBarsOnly(candles, bar, nowMs = 170_000L).size)
        assertEquals(3, closedBarsOnly(candles, bar, nowMs = 180_000L).size)
    }
}
