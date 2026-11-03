package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Свечи 1m в форме графика из треда (TUZKI): накопление у 1.00 → импульс к максимуму 1.32 на объёме →
 * первый откат до 1.18 → медленный подъём обратно к максимуму на тихом объёме → бар пробоя: закрытие
 * 1.36 выше 1.32 на объёме ×3.7 среднего.
 */
internal object VolumeBreakoutFixtures {

    const val TF_MS = 60_000L

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double, v: Double) =
        Candle(openTimeMs = i * TF_MS, open = o, high = h, low = l, close = c, volume = v)

    fun accumulation(): List<Candle> = (0 until 5).map { i ->
        if (i % 2 == 0) bar(i, 1.00, 1.02, 0.98, 1.01, 100.0) else bar(i, 1.01, 1.02, 0.99, 1.00, 100.0)
    }

    fun impulse(): List<Candle> = listOf(
        bar(5, 1.01, 1.11, 1.00, 1.10, 400.0),
        bar(6, 1.10, 1.23, 1.09, 1.22, 400.0),
        bar(7, 1.22, 1.29, 1.20, 1.28, 400.0),
        bar(8, 1.28, 1.32, 1.26, 1.30, 400.0), // прежний максимум 1.32
    )

    fun pullback(lowestLow: Double = 1.18): List<Candle> = listOf(
        bar(9, 1.30, 1.30, 1.22, 1.23, 150.0),
        bar(10, 1.23, 1.24, lowestLow, 1.20, 150.0),
        bar(11, 1.20, 1.23, 1.19, 1.22, 150.0),
    )

    /** Цена подползает к максимуму на тихом объёме; [lastCloses] — закрытия двух баров перед пробоем. */
    fun retest(lastCloses: Pair<Double, Double> = 1.30 to 1.31): List<Candle> = listOf(
        bar(12, 1.22, 1.25, 1.21, 1.24, 60.0),
        bar(13, 1.24, 1.27, 1.23, 1.26, 60.0),
        bar(14, 1.26, 1.28, 1.25, 1.27, 60.0),
        bar(15, 1.27, 1.30, 1.27, 1.29, 60.0),
        bar(16, 1.29, 1.31, minOf(1.28, lastCloses.first), lastCloses.first, 60.0),
        bar(17, 1.30, 1.315, minOf(1.29, lastCloses.second), lastCloses.second, 60.0),
    )

    fun breakout(close: Double = 1.36, high: Double = 1.37, volume: Double = 600.0): Candle =
        bar(18, 1.31, high, 1.305, close, volume)

    fun setup(): List<Candle> = accumulation() + impulse() + pullback() + retest() + breakout()
}

class VolumeBreakoutDetectorTest {

    private val cfg = StrategyConfig(id = "vb", type = StrategyType.VOLUME_BREAKOUT.name, stopLossPercent = 15.0)

    private fun detect(candles: List<Candle>, config: StrategyConfig = cfg) = VolumeBreakoutDetector.detect(candles, config)

    @Test fun breakoutAfterFirstPullbackPasses() {
        val r = detect(VolumeBreakoutFixtures.setup())
        assertIs<VolumeBreakoutResult.Setup>(r, (r as? VolumeBreakoutResult.Reject)?.reason)
        assertEquals(1.32, r.previousHigh, 1e-9)
        assertEquals(1.18, r.pullbackLow, 1e-9)
        assertEquals(0, r.barsAgo)
        assertTrue(r.volumeRatio >= 2.5, "объём ×${r.volumeRatio}")
        // Стоп под базой из трёх баров перед пробоем (минимум 1.27), а не под первым откатом (1.18).
        assertTrue(r.stopFrac > 0.0 && 1.36 * (1 - r.stopFrac) < 1.27 && 1.36 * (1 - r.stopFrac) > 1.20, "стоп ${r.stopFrac}")
        assertTrue(r.confidence >= 0.6, "уверенность ${r.confidence}")
    }

    @Test fun breakoutWithoutVolumeSpikeRejected() {
        val r = detect(VolumeBreakoutFixtures.accumulation() + VolumeBreakoutFixtures.impulse() +
            VolumeBreakoutFixtures.pullback() + VolumeBreakoutFixtures.retest() + VolumeBreakoutFixtures.breakout(volume = 200.0))
        assertTrue("объём пробоя" in assertIs<VolumeBreakoutResult.Reject>(r).reason, (r as? VolumeBreakoutResult.Reject)?.reason)
    }

    @Test fun wickAboveHighWithoutCloseRejected() {
        val r = detect(VolumeBreakoutFixtures.accumulation() + VolumeBreakoutFixtures.impulse() +
            VolumeBreakoutFixtures.pullback() + VolumeBreakoutFixtures.retest() +
            VolumeBreakoutFixtures.breakout(close = 1.315, high = 1.37))
        assertTrue("закрытие не выше" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }

    @Test fun flagWithoutPullbackRejected() {
        // После максимума цена стоит у самого уровня, первого отката нет.
        val flat = (9..17).map { i -> Candle(i * VolumeBreakoutFixtures.TF_MS, 1.30, 1.315, 1.29, 1.31, 60.0) }
        val r = detect(VolumeBreakoutFixtures.accumulation() + VolumeBreakoutFixtures.impulse() + flat + VolumeBreakoutFixtures.breakout())
        assertTrue("отката не было" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }

    @Test fun pullbackBelowImpulseStartRejected() {
        val r = detect(VolumeBreakoutFixtures.accumulation() + VolumeBreakoutFixtures.impulse() +
            VolumeBreakoutFixtures.pullback(lowestLow = 0.95) + VolumeBreakoutFixtures.retest() + VolumeBreakoutFixtures.breakout())
        assertTrue("глубже" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }

    @Test fun shotFromPullbackLowWithoutRetestRejected() {
        val r = detect(VolumeBreakoutFixtures.accumulation() + VolumeBreakoutFixtures.impulse() +
            VolumeBreakoutFixtures.pullback() + VolumeBreakoutFixtures.retest(lastCloses = 1.19 to 1.20) +
            VolumeBreakoutFixtures.breakout())
        assertTrue("не вернулась к максимуму" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }

    @Test fun runawayBreakoutIsNotChased() {
        val r = detect(VolumeBreakoutFixtures.accumulation() + VolumeBreakoutFixtures.impulse() +
            VolumeBreakoutFixtures.pullback() + VolumeBreakoutFixtures.retest() +
            VolumeBreakoutFixtures.breakout(close = 1.60, high = 1.62))
        assertTrue("не догоняем" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }

    @Test fun looseBaseRejectedByStopLossCap() {
        val r = detect(VolumeBreakoutFixtures.setup(), cfg.copy(stopLossPercent = 3.0))
        assertTrue("база не тугая" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }

    @Test fun weakImpulseRejected() {
        val r = detect(VolumeBreakoutFixtures.setup(), cfg.copy(vbMinImpulsePct = 50.0)) // импульс ~+35%
        assertTrue("импульс" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }

    @Test fun breakoutOneBarAgoStillValidWhileHeld() {
        val held = Candle(19 * VolumeBreakoutFixtures.TF_MS, 1.36, 1.39, 1.35, 1.38, 300.0)
        val r = detect(VolumeBreakoutFixtures.setup() + held)
        assertIs<VolumeBreakoutResult.Setup>(r, (r as? VolumeBreakoutResult.Reject)?.reason)
        assertEquals(1, r.barsAgo)
    }

    @Test fun failedBreakoutOneBarAgoRejected() {
        val failed = Candle(19 * VolumeBreakoutFixtures.TF_MS, 1.36, 1.36, 1.28, 1.30, 300.0)
        assertIs<VolumeBreakoutResult.Reject>(detect(VolumeBreakoutFixtures.setup() + failed))
    }

    @Test fun tooFewCandlesRejected() {
        val r = detect(VolumeBreakoutFixtures.setup().takeLast(10))
        assertTrue("мало свечей" in assertIs<VolumeBreakoutResult.Reject>(r).reason)
    }
}
