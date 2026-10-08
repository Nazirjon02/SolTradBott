package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Свечи для «Пробой + откат»: база 25 баров под уровнем 1.05 → серия из трёх пробойных баров
 * на объёме (пик 1.55) → откат из трёх баров на затухающем объёме (минимум 1.30, выше уровня) →
 * бар-триггер выше максимума предыдущего на возросшем объёме.
 */
internal object BreakoutFixtures {

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double, v: Double) =
        Candle(openTimeMs = i * 300_000L, open = o, high = h, low = l, close = c, volume = v)

    fun base(): List<Candle> = (0 until 25).map { i ->
        if (i % 2 == 0) bar(i, 1.00, 1.05, 0.99, 1.03, 100.0)
        else bar(i, 1.03, 1.04, 0.98, 1.00, 100.0)
    }

    fun impulse(volume: Double = 300.0): List<Candle> = listOf(
        bar(25, 1.03, 1.16, 1.02, 1.15, volume),
        bar(26, 1.15, 1.32, 1.14, 1.30, volume * 0.93),
        bar(27, 1.30, 1.55, 1.29, 1.52, volume * 0.87),
    )

    fun pullback(volume: Double = 70.0, lowestLow: Double = 1.30): List<Candle> = listOf(
        bar(28, 1.52, 1.53, 1.42, 1.43, volume * 1.15),
        bar(29, 1.43, 1.44, 1.34, 1.35, volume),
        bar(30, 1.35, 1.36, lowestLow, 1.32, volume * 0.85),
    )

    fun trigger(volume: Double = 150.0): Candle = bar(31, 1.32, 1.385, 1.31, 1.38, volume)

    fun setup(): List<Candle> = base() + impulse() + pullback() + trigger()
}

class BreakoutPullbackDetectorTest {

    private val cfg = StrategyConfig(id = "bp", type = StrategyType.BREAKOUT_PULLBACK.name)

    @Test
    fun breakoutPullbackSetupPasses() {
        val r = BreakoutPullbackDetector.detect(BreakoutFixtures.setup(), cfg)
        assertIs<BreakoutPullbackResult.Setup>(r, (r as? BreakoutPullbackResult.Reject)?.reason)
        // Уровень пробоя — вершина базы ДО серии пробойных баров, а не максимум внутри импульса.
        assertEquals(1.05, r.pivot, 1e-9)
        assertEquals(1.55, r.peak, 1e-9)
        assertEquals(1.30, r.pullbackLow, 1e-9)
        assertTrue(r.stopFrac > 0.0 && r.stopFrac * 100 <= cfg.stopLossPercent, "стоп ${r.stopFrac}")
        assertTrue(r.roomR >= 1.0, "запас хода ${r.roomR}R")
        assertTrue(r.confidence >= 0.6, "уверенность ${r.confidence}")
    }

    @Test
    fun breakoutWithoutVolumeRejected() {
        val candles = BreakoutFixtures.base() + BreakoutFixtures.impulse(volume = 110.0) +
            BreakoutFixtures.pullback(volume = 30.0) + BreakoutFixtures.trigger(volume = 60.0)
        val r = BreakoutPullbackDetector.detect(candles, cfg)
        assertIs<BreakoutPullbackResult.Reject>(r)
        assertTrue("нет пробоя" in r.reason, r.reason)
    }

    @Test
    fun pullbackBackIntoBaseIsFalseBreakout() {
        // Откат провалился под уровень пробоя 1.05 — цена вернулась в базу.
        val candles = BreakoutFixtures.base() + BreakoutFixtures.impulse() +
            BreakoutFixtures.pullback(lowestLow = 0.95) + BreakoutFixtures.trigger()
        val r = BreakoutPullbackDetector.detect(candles, cfg)
        assertIs<BreakoutPullbackResult.Reject>(r)
        assertTrue("ложный" in r.reason, r.reason)
    }

    @Test
    fun heavyVolumePullbackRejected() {
        // Объём в откате не «высох» — продавцы активны.
        val candles = BreakoutFixtures.base() + BreakoutFixtures.impulse() +
            BreakoutFixtures.pullback(volume = 250.0) + BreakoutFixtures.trigger(volume = 600.0)
        val r = BreakoutPullbackDetector.detect(candles, cfg)
        assertIs<BreakoutPullbackResult.Reject>(r)
        assertTrue("объём в откате" in r.reason, r.reason)
    }

    @Test
    fun noTriggerBarRejected() {
        // Последний бар продолжает откат (медвежий) — разворота нет, «нож не ловим».
        val falling = Candle(31 * 300_000L, 1.32, 1.33, 1.30, 1.305, 150.0)
        val candles = BreakoutFixtures.base() + BreakoutFixtures.impulse() + BreakoutFixtures.pullback() + falling
        val r = BreakoutPullbackDetector.detect(candles, cfg)
        assertIs<BreakoutPullbackResult.Reject>(r)
        assertTrue("триггер" in r.reason, r.reason)
    }

    @Test
    fun weakTriggerVolumeRejected() {
        val candles = BreakoutFixtures.base() + BreakoutFixtures.impulse() +
            BreakoutFixtures.pullback() + BreakoutFixtures.trigger(volume = 50.0)
        val r = BreakoutPullbackDetector.detect(candles, cfg)
        assertIs<BreakoutPullbackResult.Reject>(r)
        assertTrue("объём триггера" in r.reason, r.reason)
    }

    @Test
    fun stopBeyondConfiguredStopLossRejected() {
        // Тот же сетап, но SL конфига 5% — стоп под откатом дальше → «сетап не тугой».
        val r = BreakoutPullbackDetector.detect(BreakoutFixtures.setup(), cfg.copy(stopLossPercent = 5.0))
        assertIs<BreakoutPullbackResult.Reject>(r)
        assertTrue("не тугой" in r.reason, r.reason)
    }

    @Test
    fun tooFewCandlesRejected() {
        val r = BreakoutPullbackDetector.detect(BreakoutFixtures.base().take(10), cfg)
        assertIs<BreakoutPullbackResult.Reject>(r)
    }
}
