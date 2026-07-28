package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Офлайн-проверка информационного анализатора [MarketAnalysis] на синтетических свечах.
 * Он делит те же примитивы с торговым [DarsEntryEngine], поэтому его вердикт «второе мнение»
 * должен совпадать с логикой входа: восходящий тренд + чистая структура → не IGNORE,
 * боковик/нисходящий/мало данных → IGNORE.
 */
class MarketAnalysisTest {

    private val cfg = DarsConfig(
        higherTf = "hour", higherTfAggregate = 1,
        entryTf = "minute", entryTfAggregate = 5,
        candleLimit = 200,
        swingPivotPct = 1.5,
        requireHtfTrend = true,
        dominanceRatio = 1.5,
        minCorrectionLenPct = 70.0,
        rejectAtResistance = true,
        resistanceProximityPct = 2.0,
        minLegs = 4,
        failClosed = false,
        useImpulseCorrection = true,
        useTrendLevels = true,
        useFalseBreakout = true,
        useTriangle = true,
    )

    private val market = MarketContext(
        priceUsd = 127.5, changeM5Percent = 1.0, changeH1Percent = 8.0,
        liquidityUsd = 100_000.0, marketCap = 500_000.0, volumeH1Usd = 50_000.0,
        buysH1 = 120, sellsH1 = 80, tokenAgeMinutes = 600,
    )

    /** Импульс↑, слабая коррекция↓, импульс↑, слабая коррекция↓ + подтверждающий бычий бар. */
    private fun entryWithConfirmation(): List<Candle> {
        val closes = buildList {
            add(100.0)
            addAll(listOf(102.0, 104.0, 106.0, 108.0, 110.0, 112.0, 114.0, 116.0, 118.0, 120.0)) // импульс → 120
            addAll(listOf(119.0, 118.0, 117.0, 116.0, 115.0, 114.5, 113.5, 113.0))                 // коррекция → 113
            addAll(listOf(115.0, 117.0, 119.0, 121.0, 123.0, 125.0, 127.0, 129.0, 131.0, 133.0)) // импульс → 133
            addAll(listOf(132.0, 131.0, 130.0, 129.0, 128.0, 127.0, 126.0, 125.0))                 // коррекция → 125
            add(127.5)                                                                              // подтверждение ↑
        }
        return closes.mapIndexed { i, close ->
            val prev = if (i == 0) close else closes[i - 1]
            val up = close >= prev
            Candle(
                openTimeMs = i * 300_000L,
                open = prev,
                high = maxOf(prev, close) * 1.002,
                low = minOf(prev, close) * 0.998,
                close = close,
                volume = if (up) 1000.0 else 200.0,
            )
        }
    }

    /**
     * Монотонный рост 80 → 170 на старшем ТФ. Верх (сопротивление) заметно выше цены рабочего ТФ
     * (~127), иначе сработал бы гейт «не покупаем у сопротивления» (Урок 4) и вход был бы IGNORE.
     */
    private fun uptrendHtf(): List<Candle> = (0..29).map { i ->
        val close = 80.0 + i * (90.0 / 29.0)
        Candle(i * 3_600_000L, open = close - 0.5, high = close + 0.6, low = close - 0.7, close = close, volume = 500.0)
    }

    @Test
    fun uptrendCanonicalIsTradeable() {
        val a = MarketAnalysis.analyze("BULL", entryWithConfirmation(), uptrendHtf(), market, cfg, now = 40 * 300_000L)
        assertEquals(TrendDirection.UP, a.trend, "Ожидали восходящий тренд")
        assertNotNull(a.impulse, "Должна быть характеристика импульса")
        assertNotNull(a.correction, "Должна быть характеристика коррекции")
        assertNotNull(a.entry, "Должна быть посчитана зона входа")
        assertTrue(a.score > 0, "Балл должен быть положительным, получили ${a.score}")
        assertTrue(
            a.readiness != Readiness.IGNORE,
            "Чистая структура по тренду не должна игнорироваться. Блокеры: ${a.blockers}",
        )
    }

    @Test
    fun downtrendIsIgnored() {
        // Нисходящий старший ТФ → лонг по методике не рассматриваем.
        val downHtf = (0..29).map { i ->
            val close = 130.0 - i * (50.0 / 29.0)
            Candle(i * 3_600_000L, open = close + 0.5, high = close + 0.7, low = close - 0.6, close = close, volume = 500.0)
        }
        val a = MarketAnalysis.analyze("BEAR", entryWithConfirmation(), downHtf, market, cfg)
        assertEquals(Readiness.IGNORE, a.readiness)
        assertTrue(a.blockers.any { it.contains("тренд", ignoreCase = true) }, "Ожидали блокер про тренд: ${a.blockers}")
        assertEquals(0, a.score)
    }

    @Test
    fun flatMarketIsIgnored() {
        val flat = (0..40).map { i ->
            Candle(i * 300_000L, open = 100.0, high = 100.2, low = 99.8, close = 100.0, volume = 100.0)
        }
        val a = MarketAnalysis.analyze("FLAT", flat, flat, market, cfg)
        assertEquals(Readiness.IGNORE, a.readiness)
    }

    @Test
    fun insufficientHistoryIsIgnored() {
        val few = (0..5).map { i ->
            Candle(i * 300_000L, open = 100.0, high = 101.0, low = 99.0, close = 100.5, volume = 100.0)
        }
        val a = MarketAnalysis.analyze("TINY", few, emptyList(), market, cfg)
        assertEquals(Readiness.IGNORE, a.readiness)
        assertTrue(a.blockers.any { it.contains("истори", ignoreCase = true) }, "Ожидали блокер про историю: ${a.blockers}")
    }
}
