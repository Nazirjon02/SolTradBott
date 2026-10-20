package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.BaseToken
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair

/** Свечи 5m для «Мем-плейбука»: база ~1.0 → импульс до 2.0 → откат → бар-триггер. */
internal object MemeFixtures {
    private const val BAR_MS = 300_000L

    private fun MutableList<Candle>.bar(o: Double, h: Double, l: Double, c: Double, v: Double) {
        add(Candle(size * BAR_MS, o, h, l, c, v))
    }

    /** База и импульс: минимум 0.98 (бар 20), пик 2.0 (бар 26) — импульс +104%, размах 1.02. */
    fun impulse(): MutableList<Candle> = mutableListOf<Candle>().apply {
        repeat(20) { bar(1.0, 1.01, 0.99, 1.0, 100.0) }
        bar(1.0, 1.005, 0.98, 1.0, 100.0)
        listOf(1.0, 1.17, 1.34, 1.51, 1.68).forEach { o -> bar(o, o + 0.155, o - 0.005, o + 0.15, 500.0) }
        bar(1.85, 2.0, 1.84, 1.99, 500.0)
    }

    /**
     * Полный сетап: откат 4 бара до [pullbackLow] на объёме ~135 (≈30% импульса), затем триггер
     * 1.45 → 1.60 выше максимума прошлого бара. При pullbackLow = 1.39 глубина 0.598 — «золотая зона».
     */
    fun setup(pullbackLow: Double = 1.39, triggerVolume: Double = 300.0): List<Candle> = impulse().apply {
        bar(1.99, 1.995, 1.85, 1.86, 150.0)
        bar(1.86, 1.87, 1.71, 1.72, 140.0)
        bar(1.72, 1.73, 1.57, 1.58, 130.0)
        bar(1.58, 1.585, pullbackLow, 1.45, 120.0)
        bar(1.45, 1.61, minOf(1.44, pullbackLow + 0.05), 1.60, triggerVolume)
    }
}

class MemeFibDetectorTest {

    /** SL 25% — как в сиде «Мем-плейбука»; у дефолтного конфига 15% стоп этого сетапа слишком далёк. */
    private val cfg = StrategyConfig(id = "mp", type = StrategyType.MEME_PLAYBOOK.name, stopLossPercent = 25.0)

    private fun rejectReason(candles: List<Candle>, config: StrategyConfig = cfg): String =
        assertIs<MemeFibResult.Reject>(MemeFibDetector.detect(candles, config)).reason

    @Test fun detectsGoldenZonePullback() {
        val setup = assertIs<MemeFibResult.Setup>(MemeFibDetector.detect(MemeFixtures.setup(), cfg))
        assertEquals(0.98, setup.impulseLow, 1e-9)
        assertEquals(2.0, setup.impulseHigh, 1e-9)
        assertEquals(0.598, setup.depth, 1e-3)
        // Стоп под минимумом отката 1.39 (−0.5 ATR), а не под уровнем 0.786 (≈1.20).
        val stop = 1.60 * (1 - setup.stopFrac)
        assertTrue(stop in 1.25..1.39, "стоп $stop")
        assertTrue(setup.roomR >= 1.0, "до максимума ${setup.roomR}R")
        assertTrue(setup.confidence >= 0.8, "золотая зона + высохший объём: ${setup.confidence}")
        assertTrue(setup.reason.contains("золотая зона"), setup.reason)
    }

    @Test fun rejectsPullbackDeeperThanFibMax() {
        // Минимум 1.18: глубина (2.0 − 1.18) / 1.02 = 0.80 ≥ 0.786 — пустой памп.
        assertTrue(rejectReason(MemeFixtures.setup(pullbackLow = 1.18)).contains("пустой памп"))
    }

    @Test fun rejectsShallowPullback() {
        // Откат лишь до 0.284 импульса — ещё не в зоне 0.382.
        val candles = MemeFixtures.impulse().apply {
            add(Candle(size * 300_000L, 1.99, 1.995, 1.85, 1.86, 150.0))
            add(Candle(size * 300_000L, 1.86, 1.87, 1.71, 1.72, 140.0))
            add(Candle(size * 300_000L, 1.72, 1.90, 1.72, 1.89, 300.0))
        }
        assertTrue(rejectReason(candles).contains("мельче"))
    }

    @Test fun rejectsWeakImpulse() {
        assertTrue(rejectReason(MemeFixtures.setup(), cfg.copy(mpMinImpulsePct = 150.0)).contains("импульс"))
    }

    @Test fun rejectsPullbackWithoutVolumeDryUp() {
        assertTrue(rejectReason(MemeFixtures.setup(), cfg.copy(mpPullbackVolumeRatio = 0.2)).contains("объём в откате"))
    }

    @Test fun rejectsWeakTriggerVolume() {
        assertTrue(rejectReason(MemeFixtures.setup(triggerVolume = 140.0)).contains("объём триггера"))
    }

    @Test fun rejectsWithoutTriggerBar() {
        // Последний бар — красный бар отката: разворота ещё нет.
        assertTrue(rejectReason(MemeFixtures.setup().dropLast(1)).contains("нет триггера"))
    }

    @Test fun rejectsWhenStopWiderThanConfig() {
        assertTrue(rejectReason(MemeFixtures.setup(), cfg.copy(stopLossPercent = 10.0)).contains("дальше SL"))
    }

    @Test fun rejectsBeforePullbackForms() {
        assertTrue(rejectReason(MemeFixtures.impulse()).contains("отката ещё нет"))
    }

    @Test fun liquidityShareOfMarketCap() {
        val c = TokenCandidate(
            mint = "M", symbol = "S", name = "", pairAddress = "P", dexId = "", priceUsd = 1.0,
            liquidityUsd = 20_000.0, marketCap = 1_000_000.0, volumeH1Usd = 0.0, buysH1 = 0, sellsH1 = 0,
            priceChangeM5 = 0.0, priceChangeH1 = 0.0, tokenAgeMinutes = 60, score = 0.0, scannedAt = 0,
        )
        assertTrue(liquidityDeepEnough(c, 2.0))                            // 2% ≥ 2%
        assertFalse(liquidityDeepEnough(c, 3.0))                           // 2% < 3%
        assertFalse(liquidityDeepEnough(c.copy(marketCap = 0.0), 3.0))     // капитализация неизвестна
        assertTrue(liquidityDeepEnough(c.copy(marketCap = 0.0), 0.0))      // фильтр выключен
    }

    private fun pair(mint: String, createdAt: Long?) = TokenPair(
        chainId = "solana", baseToken = BaseToken(address = mint, symbol = "CAT"), pairCreatedAt = createdAt,
    )

    private val t0 = 1_700_000_000_000L // мс
    private val hour = 3_600_000L

    @Test fun copycatIsYoungerTokenWithSameTicker() {
        val pairs = listOf(pair("OLD", t0), pair("NEW", t0 + hour))
        assertTrue(isCopycat("NEW", t0 + hour, pairs))
        assertFalse(isCopycat("OLD", t0, pairs), "оригинал — самая ранняя монета с тикером")
    }

    @Test fun ownOlderPairMakesTokenOriginal() {
        // Сканер видит новый пул монеты (после миграции), но её первая пара — раньше чужой.
        val pairs = listOf(pair("MINE", t0), pair("OTHER", t0 + hour))
        assertFalse(isCopycat("MINE", t0 + 5 * hour, pairs))
    }

    @Test fun copycatHandlesSecondsAndMissingDates() {
        // pairCreatedAt в секундах нормализуется; пара без даты не считается более старой.
        assertTrue(isCopycat("NEW", t0 + hour, listOf(pair("OLD", t0 / 1000))))
        assertFalse(isCopycat("NEW", t0 + hour, listOf(pair("UNDATED", null))))
        assertFalse(isCopycat("NEW", t0 + hour, emptyList()))
    }
}
