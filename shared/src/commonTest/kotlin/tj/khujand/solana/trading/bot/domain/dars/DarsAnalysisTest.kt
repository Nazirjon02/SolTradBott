package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Офлайн-проверка ядра методики «Dars» на синтетических свечах (без сети).
 * Проверяет всю цепочку анализа: сегментация ног → тренд → импульс/коррекция.
 */
class DarsAnalysisTest {

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

    /** Канонический паттерн: импульс↑, слабая коррекция↓, импульс↑, слабая коррекция↓ (текущая). */
    private fun canonicalEntryCandles(): List<Candle> {
        val closes = buildList {
            add(100.0)
            // импульс 1: 100 → 120
            addAll(listOf(102.0, 104.0, 106.0, 108.0, 110.0, 112.0, 114.0, 116.0, 118.0, 120.0))
            // коррекция 1: 120 → 113 (вялая)
            addAll(listOf(119.0, 118.0, 117.0, 116.0, 115.0, 114.5, 113.5, 113.0))
            // импульс 2: 113 → 133
            addAll(listOf(115.0, 117.0, 119.0, 121.0, 123.0, 125.0, 127.0, 129.0, 131.0, 133.0))
            // коррекция 2 (текущая): 133 → 125 (вялая, ~40% ретрейс)
            addAll(listOf(132.0, 131.0, 130.0, 129.0, 128.0, 127.0, 126.0, 125.0))
        }
        // Импульсные бары — высокий объём, коррекционные — низкий.
        return closes.mapIndexed { i, close ->
            val prev = if (i == 0) close else closes[i - 1]
            val up = close >= prev
            val high = maxOf(prev, close) * 1.002
            val low = minOf(prev, close) * 0.998
            val volume = if (up) 1000.0 else 200.0
            Candle(openTimeMs = i * 60_000L, open = prev, high = high, low = low, close = close, volume = volume)
        }
    }

    /**
     * Канонический паттерн + подтверждающий агрессивный бычий бар в конце (Урок 4):
     * коррекция завершилась, цена разворачивается вверх — только тогда входим.
     */
    private fun entryWithConfirmation(): List<Candle> {
        val base = canonicalEntryCandles()               // заканчивается падающим баром (125.0)
        val prev = base.last().close                     // 125.0
        val close = 127.5                                // сильный разворот вверх (+2%)
        return base + Candle(
            openTimeMs = base.size * 60_000L,
            open = prev,
            high = close * 1.002,
            low = prev * 0.998,
            close = close,
            volume = 1200.0,
        )
    }

    @Test
    fun segmentsIntoFourLegs() {
        val legs = LegSegmenter.segment(canonicalEntryCandles(), cfg.swingPivotPct)
        assertEquals(4, legs.size, "Ожидали 4 ноги, получили ${legs.size}")
        assertEquals(TrendDirection.UP, legs[0].direction)
        assertEquals(TrendDirection.DOWN, legs[1].direction)
        assertEquals(TrendDirection.UP, legs[2].direction)
        assertEquals(TrendDirection.DOWN, legs[3].direction)
    }

    @Test
    fun detectsUptrend() {
        val htf = (0..29).map { i ->
            val close = 80.0 + i * (50.0 / 29.0) // монотонный рост 80 → 130
            Candle(i * 3_600_000L, open = close - 0.5, high = close + 0.6, low = close - 0.7, close = close, volume = 500.0)
        }
        assertEquals(TrendDirection.UP, TrendAnalyzer.trend(htf, cfg.swingPivotPct))
    }

    @Test
    fun impulseCorrectionSetupPasses() {
        val candles = entryWithConfirmation()
        val legs = LegSegmenter.segment(candles, cfg.swingPivotPct)
        val signal = ImpulseCorrectionAnalyzer.analyze(candles, legs, cfg)
        assertTrue(signal.passed, "Сетап должен пройти. Причины: ${signal.reasons}")
        assertEquals(DarsSetup.IMPULSE_CORRECTION, signal.setup)
        assertEquals(TrendDirection.UP, signal.direction)
    }

    @Test
    fun withoutConfirmationBarDoesNotPass() {
        // «Не ловим нож» (Урок 4): пока коррекция ещё падает и нет подтверждающего
        // бычьего бара — входа быть не должно.
        val candles = canonicalEntryCandles()            // заканчивается падающим баром
        val legs = LegSegmenter.segment(candles, cfg.swingPivotPct)
        val signal = ImpulseCorrectionAnalyzer.analyze(candles, legs, cfg)
        assertFalse(signal.passed, "Без подтверждения разворота входа быть не должно")
    }

    @Test
    fun takeProfitTargetsNearestResistanceAbove() {
        // Урок 2: цель — у следующего уровня, а не механический TP%.
        val levels = listOf(
            Level(price = 90.0, kind = "reversal"),   // поддержка снизу — игнор
            Level(price = 110.0, kind = "reversal"),  // ближайшее сопротивление сверху → цель
            Level(price = 130.0, kind = "reversal"),
        )
        val frac = LevelDetector.takeProfitFrac(levels, price = 100.0)
        assertNotNull(frac, "Должна быть цель у сопротивления сверху")
        assertEquals(0.10, frac, 1e-9) // до 110 = +10%
    }

    @Test
    fun noResistanceAboveMeansMechanicalTp() {
        // Пробой к новым максимумам: сверху уровней нет → null (стратегия берёт механический TP%).
        val levels = listOf(Level(price = 80.0, kind = "reversal"), Level(price = 95.0, kind = "reversal"))
        assertNull(LevelDetector.takeProfitFrac(levels, price = 100.0))
    }

    @Test
    fun flatMarketDoesNotPass() {
        // Плоский рынок без структуры → мало ног → отказ.
        val flat = (0..40).map { i ->
            Candle(i * 60_000L, open = 100.0, high = 100.2, low = 99.8, close = 100.0, volume = 100.0)
        }
        val legs = LegSegmenter.segment(flat, cfg.swingPivotPct)
        val signal = ImpulseCorrectionAnalyzer.analyze(flat, legs, cfg)
        assertFalse(signal.passed, "Плоский рынок не должен давать вход")
    }
}
