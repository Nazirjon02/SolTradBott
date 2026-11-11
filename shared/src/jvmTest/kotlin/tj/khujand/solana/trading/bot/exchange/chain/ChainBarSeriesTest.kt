package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.Candle

class ChainBarSeriesTest {

    private fun csv(name: String): List<List<String>> =
        ChainBarSeriesTest::class.java.getResource("/chain/$name")!!.readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(',') }

    private fun close(expected: Double, actual: Double, what: String) =
        assertEquals(expected, actual, expected * 1e-9, what)

    /**
     * Правило GeckoTerminal на его же данных: сделки пула PAYDAY из его /trades, собранные в 1m-бары, должны
     * дать ровно его 1m OHLCV — open = прошлый close, high/low с учётом open, объём — сумма.
     */
    @Test
    fun `бары из сделок GeckoTerminal совпадают с его свечами`() {
        val series = ChainBarSeries(60_000L)
        val trades = csv("gt_payday_trades.csv")
        trades.forEachIndexed { i, r ->
            series.add(PricedTrade(r[0].toLong() * 1000, i.toLong(), r[1].toDouble(), r[2].toDouble()))
        }
        val firstTradeMs = trades.first()[0].toLong() * 1000
        val coverage = Coverage().apply { open(firstTradeMs) }
        val ours = series.bars(nowMs = Long.MAX_VALUE / 2, coverage = coverage).associateBy { it.candle.openTimeMs }
        val gecko = csv("gt_payday_1m.csv").map {
            Candle(it[0].toLong() * 1000, it[1].toDouble(), it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toDouble())
        }
        var compared = 0
        for (g in gecko) {
            val bar = ours[g.openTimeMs] ?: continue
            // Первый бар в списке сделок неполный (у /trades только 300 последних сделок).
            if (bar.source != BarSource.CHAIN || !bar.openReliable) continue
            val m = bar.candle
            close(g.open, m.open, "open ${g.openTimeMs}")
            close(g.high, m.high, "high ${g.openTimeMs}")
            close(g.low, m.low, "low ${g.openTimeMs}")
            close(g.close, m.close, "close ${g.openTimeMs}")
            close(g.volume, m.volume, "volume ${g.openTimeMs}")
            compared++
        }
        assertTrue(compared >= 15, "сравнено баров: $compared")
    }

    @Test
    fun `open — прошлый close, high и low его включают, пустая минута бара не даёт`() {
        val s = ChainBarSeries(60_000L)
        val cov = Coverage().apply { open(0) }
        s.add(PricedTrade(10_000, 1, 10.0, 1.0))
        s.add(PricedTrade(50_000, 2, 12.0, 2.0))
        // минута 60–120 с — без сделок
        s.add(PricedTrade(130_000, 3, 11.0, 3.0))
        s.add(PricedTrade(170_000, 4, 11.5, 4.0))
        val bars = s.bars(nowMs = 300_000, coverage = cov)
        assertEquals(listOf(0L, 120_000L), bars.map { it.candle.openTimeMs })
        val b = bars[1].candle
        assertEquals(12.0, b.open)
        assertEquals(12.0, b.high) // open выше сделок бара
        assertEquals(11.0, b.low)
        assertEquals(11.5, b.close)
        assertEquals(7.0, b.volume)
        assertEquals(10.0, bars[0].candle.open) // первый бар — open по первой сделке
        assertFalse(bars[0].openReliable)
        assertTrue(bars[1].openReliable)
    }

    @Test
    fun `сделки не по порядку — close по номеру сделки, а не по приходу`() {
        val s = ChainBarSeries(60_000L)
        val cov = Coverage().apply { open(0) }
        s.add(PricedTrade(30_000, 20, 2.0, 1.0))
        s.add(PricedTrade(20_000, 10, 1.0, 1.0)) // пришла позже, но была раньше
        val b = s.bars(nowMs = 120_000, coverage = cov).single().candle
        assertEquals(1.0, b.open)
        assertEquals(2.0, b.close)
    }

    @Test
    fun `неполный бар заменяет бар GeckoTerminal, полный — свой`() {
        val s = ChainBarSeries(60_000L)
        val cov = Coverage().apply { open(90_000) } // подписка с середины второй минуты
        s.add(PricedTrade(100_000, 1, 5.0, 1.0)) // неполный бар 60–120 с
        s.add(PricedTrade(150_000, 2, 6.0, 1.0)) // полный бар 120–180 с
        s.putGecko(listOf(
            Candle(0, 4.0, 4.5, 3.9, 4.2, 10.0), // история до подписки
            Candle(60_000, 4.2, 5.2, 4.1, 5.1, 20.0), // полный бар GT вместо нашего неполного
            Candle(120_000, 5.1, 6.1, 5.0, 6.0, 1.5), // свой полный бар не заменяется
        ))
        val bars = s.bars(nowMs = 300_000, coverage = cov)
        assertEquals(listOf(BarSource.GECKO, BarSource.GECKO, BarSource.CHAIN), bars.map { it.source })
        assertEquals(5.1, bars[2].candle.open) // open — close бара GeckoTerminal перед ним
        assertEquals(1.0, bars[2].candle.volume)
        assertTrue(bars[2].openReliable)
    }

    @Test
    fun `без бара GeckoTerminal неполный бар остаётся неполным, обрыв рвёт надёжность open`() {
        val s = ChainBarSeries(60_000L)
        val cov = Coverage().apply { open(0) }
        s.add(PricedTrade(10_000, 1, 1.0, 1.0))
        cov.close(70_000) // обрыв во второй минуте
        cov.open(200_000)
        s.add(PricedTrade(80_000, 2, 2.0, 1.0)) // до обрыва дошла
        s.add(PricedTrade(250_000, 3, 3.0, 1.0)) // после восстановления
        val bars = s.bars(nowMs = 400_000, coverage = cov)
        assertEquals(listOf(BarSource.CHAIN, BarSource.PARTIAL, BarSource.CHAIN), bars.map { it.source })
        assertFalse(bars[2].openReliable, "между барами подписки не было — open мог быть другим")
    }

    @Test
    fun `незакрытый бар — только по запросу`() {
        val s = ChainBarSeries(60_000L)
        val cov = Coverage().apply { open(0) }
        s.add(PricedTrade(10_000, 1, 1.0, 1.0))
        s.add(PricedTrade(70_000, 2, 2.0, 1.0))
        assertEquals(1, s.bars(nowMs = 90_000, coverage = cov).size)
        assertEquals(2, s.bars(nowMs = 90_000, coverage = cov, includeOpen = true).size)
        // закрытым бар считается через CLOSE_LAG_MS после конца
        assertEquals(1, s.bars(nowMs = 120_000 + ChainBarSeries.CLOSE_LAG_MS - 1, coverage = cov).size)
        assertEquals(2, s.bars(nowMs = 120_000 + ChainBarSeries.CLOSE_LAG_MS, coverage = cov).size)
    }

    @Test
    fun `старые бары вычищаются`() {
        val s = ChainBarSeries(60_000L, maxBars = 3)
        val cov = Coverage().apply { open(0) }
        for (i in 0 until 6) s.add(PricedTrade(i * 60_000L + 1, i.toLong(), 1.0 + i, 1.0))
        assertEquals(listOf(180_000L, 240_000L, 300_000L), s.bars(nowMs = 1_000_000, coverage = cov).map { it.candle.openTimeMs })
    }
}
