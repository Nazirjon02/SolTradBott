package tj.khujand.solana.trading.bot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.Candle

class IndicatorsTest {

    @Test
    fun emaFollowsTrend() {
        val rising = (1..50).map { it.toDouble() }
        val ema = Indicators.ema(rising, 9)
        assertTrue(ema.isNotEmpty())
        // EMA растущего ряда монотонно растёт
        assertTrue(ema.zipWithNext().all { (a, b) -> b > a })
        // и отстаёт от цены (последняя EMA ниже последней цены)
        assertTrue(ema.last() < rising.last())
    }

    @Test
    fun emaTooFewPointsReturnsEmpty() {
        assertEquals(emptyList(), Indicators.ema(listOf(1.0, 2.0), 9))
    }

    @Test
    fun rsiExtremes() {
        // Непрерывный рост → RSI у 100
        val rising = (1..40).map { it.toDouble() }
        val rsiUp = Indicators.rsi(rising, 14)
        assertTrue(rsiUp.last() > 90.0, "RSI роста должен быть высоким, а был ${rsiUp.last()}")

        // Непрерывное падение → RSI у 0
        val falling = (40 downTo 1).map { it.toDouble() }
        val rsiDown = Indicators.rsi(falling, 14)
        assertTrue(rsiDown.last() < 10.0, "RSI падения должен быть низким, а был ${rsiDown.last()}")
    }

    @Test
    fun atrPositiveOnVolatileSeries() {
        val candles = (1..30).map { i ->
            val base = 100.0 + i
            Candle(openTimeMs = i.toLong(), open = base, high = base + 2, low = base - 2, close = base + 1, volume = 10.0)
        }
        val atr = Indicators.atr(candles, 14)
        assertTrue(atr.isNotEmpty())
        assertTrue(atr.last() > 0)
    }

    @Test
    fun bollingerBandsOrdering() {
        val prices = (1..40).map { 100.0 + (it % 5) }
        val bb = Indicators.bollingerBands(prices, 20, 2.0)
        assertTrue(bb.upper.isNotEmpty())
        bb.upper.indices.forEach { i ->
            assertTrue(bb.upper[i] >= bb.middle[i])
            assertTrue(bb.middle[i] >= bb.lower[i])
        }
    }
}
