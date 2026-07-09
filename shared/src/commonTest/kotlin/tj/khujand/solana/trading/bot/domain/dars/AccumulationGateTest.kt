package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Урок 4 п.2: после резкого движения ждём накопления. */
class AccumulationGateTest {

    private fun calm(i: Int, price: Double = 100.0) =
        Candle(i * 60_000L, open = price, high = price * 1.004, low = price * 0.996, close = price, volume = 300.0)

    /** Резкий бар: диапазон ~31% (нож). */
    private fun sharp(i: Int) =
        Candle(i * 60_000L, open = 100.0, high = 130.0, low = 99.0, close = 128.0, volume = 3000.0)

    @Test
    fun calmMarketAllowsEntry() {
        val candles = (0..20).map { calm(it) }
        assertFalse(AccumulationGate.blocksEntry(candles), "Спокойный рынок не должен блокировать вход")
    }

    @Test
    fun recentSharpMoveBlocksEntry() {
        // 15 спокойных, резкий бар, затем всего 2 спокойных + бар-триггер → накопления мало.
        val candles = buildList {
            repeat(15) { add(calm(it)) }
            add(sharp(15))
            add(calm(16)); add(calm(17))
            add(calm(18)) // бар-триггер (последний)
        }
        assertTrue(AccumulationGate.blocksEntry(candles), "Сразу после ножа входа быть не должно")
    }

    @Test
    fun oldSharpMoveWithAccumulationAllowsEntry() {
        // Резкий бар давно, после него длинная консолидация → вход разрешён.
        val candles = buildList {
            add(calm(0)); add(calm(1))
            add(sharp(2))
            repeat(20) { add(calm(it + 3)) }
        }
        assertFalse(AccumulationGate.blocksEntry(candles), "После долгого накопления вход разрешён")
    }
}
