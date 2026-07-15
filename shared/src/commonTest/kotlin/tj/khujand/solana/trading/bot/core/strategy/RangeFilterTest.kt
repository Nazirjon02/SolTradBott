package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeFilterTest {

    private fun candle(high: Double, low: Double) =
        Candle(openTimeMs = 0L, open = low, high = high, low = low, close = high, volume = 1.0)

    /** Окно: low=0, high=10 → диапазон [0, 10]. */
    private fun window(): List<Candle> = listOf(
        candle(high = 10.0, low = 0.0),
        candle(high = 8.0, low = 2.0),
    )

    @Test fun positionAtLowIsZero() =
        assertEquals(0.0, rangePosition(window(), price = 0.0, lookback = 100))

    @Test fun positionAtHighIsOne() =
        assertEquals(1.0, rangePosition(window(), price = 10.0, lookback = 100))

    @Test fun positionMidIsHalf() =
        assertEquals(0.5, rangePosition(window(), price = 5.0, lookback = 100))

    @Test fun emptyCandlesGiveNull() =
        assertNull(rangePosition(emptyList(), price = 5.0, lookback = 100))

    @Test fun degenerateRangeGivesNull() {
        val flat = listOf(candle(high = 5.0, low = 5.0))
        assertNull(rangePosition(flat, price = 5.0, lookback = 100))
    }

    @Test fun lookbackTrimsToRecentWindow() {
        // Старая широкая свеча вне окна не учитывается: lookback=1 → окно [5, 10], цена 7.5 → 0.5.
        val candles = listOf(
            candle(high = 100.0, low = 0.0),
            candle(high = 10.0, low = 5.0),
        )
        assertEquals(0.5, rangePosition(candles, price = 7.5, lookback = 1))
    }

    private fun cfg(enabled: Boolean, maxPct: Double = 0.8) =
        StrategyConfig(id = "t", rangeFilterEnabled = enabled, rangeMaxEntryPct = maxPct, rangeLookbackBars = 100)

    @Test fun disabledFilterAllowsAnyEntry() =
        assertTrue(cfg(enabled = false).rangeAllowsEntry(window(), entryPrice = 10.0))

    @Test fun blocksEntryAboveThreshold() =
        // позиция 0.9 > порог 0.8 → блок
        assertFalse(cfg(enabled = true, maxPct = 0.8).rangeAllowsEntry(window(), entryPrice = 9.0))

    @Test fun allowsEntryBelowThreshold() =
        // позиция 0.3 ≤ 0.8 → разрешено
        assertTrue(cfg(enabled = true, maxPct = 0.8).rangeAllowsEntry(window(), entryPrice = 3.0))

    @Test fun noDataDoesNotBlockBlindly() =
        // фильтр включён, но свечей нет → не блокируем вслепую
        assertTrue(cfg(enabled = true).rangeAllowsEntry(emptyList(), entryPrice = 999.0))
}
