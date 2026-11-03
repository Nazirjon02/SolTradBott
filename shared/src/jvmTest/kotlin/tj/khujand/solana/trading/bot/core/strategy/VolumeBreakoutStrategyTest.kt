package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.strategy.VolumeBreakoutFixtures.TF_MS
import tj.khujand.solana.trading.bot.domain.backtest.BacktestCoin
import tj.khujand.solana.trading.bot.domain.backtest.BacktestSettings
import tj.khujand.solana.trading.bot.domain.backtest.Backtester
import tj.khujand.solana.trading.bot.domain.backtest.CandleSlices
import tj.khujand.solana.trading.bot.domain.backtest.CoinSeries
import tj.khujand.solana.trading.bot.domain.backtest.backtestStrategy
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.volumeBreakoutDefaults

/**
 * «Пробой на объёме» поверх детектора: только закрытые бары, цена входа — закрытие бара пробоя,
 * подключение к фабрике и бэктестеру. Сам детектор покрыт VolumeBreakoutDetectorTest.
 */
class VolumeBreakoutStrategyTest {

    /** Цена кандидата — из кеша сканера, до пробоя (устарела). */
    private val candidate = TokenCandidate(
        mint = "MINT", symbol = "VB", name = "Volume", pairAddress = "POOL", dexId = "raydium",
        priceUsd = 1.30, liquidityUsd = 50_000.0, marketCap = 300_000.0, volumeH1Usd = 10_000.0,
        buysH1 = 10, sellsH1 = 5, priceChangeM5 = 0.0, priceChangeH1 = 0.0,
        tokenAgeMinutes = 30L, score = 0.0, scannedAt = 0L,
    )

    private val cfg = volumeBreakoutDefaults()

    @Test fun entersAtCloseOfBreakoutBar() = runBlocking {
        val strategy = VolumeBreakoutStrategy(cfg, nowMs = { 19 * TF_MS + 5_000L }) // бар пробоя (18) закрыт
        val signal = assertNotNull(strategy.analyze(candidate, VolumeBreakoutFixtures.setup(), emptyList()))
        assertEquals(1.36, signal.entryPrice, "закрытие бара пробоя, а не устаревшая цена кеша сканера")
        assertTrue(signal.stopLoss < 1.27 && signal.stopLoss > 1.36 * 0.85, "стоп под базой ${signal.stopLoss}")
        assertTrue(signal.confidence >= MIN_CONFIDENCE, "проходит общий порог уверенности")
        assertTrue(signal.reason.startsWith("Пробой на объёме"), signal.reason)
    }

    @Test fun formingBreakoutBarIsIgnored() = runBlocking {
        // Бар пробоя ещё формируется: закрыться он может и под уровнем — сигнала нет.
        val strategy = VolumeBreakoutStrategy(cfg, nowMs = { 18 * TF_MS + 30_000L })
        assertNull(strategy.analyze(candidate, VolumeBreakoutFixtures.setup(), emptyList()))
    }

    @Test fun factoryAndBacktestCreateThisStrategy() {
        assertIs<VolumeBreakoutStrategy>(createStrategy(cfg))
        assertIs<VolumeBreakoutStrategy>(backtestStrategy(cfg, CandleSlices("minute" to 1)))
    }

    @Test fun backtestEntersOnBarAfterBreakout() = runBlocking {
        // После пробоя (бар 18) — рост: вход по открытию бара 19, в пределах 3% от закрытия пробоя.
        val rally = (19 until 40).map { i ->
            val o = 1.365 * Math.pow(1.03, (i - 19).toDouble())
            Candle(i * TF_MS, o, o * 1.03, o * 0.995, o * 1.025, 300.0)
        }
        val coin = BacktestCoin("POOL", "MINT", "VB", "VB / SOL", "raydium", 0L, 2.0, 1e6, 1e5)
        val report = Backtester.run(
            cfg.copy(scanFiltersEnabled = false), // возраст и метрики в истории фикстуры не моделируются
            listOf(CoinSeries(coin, VolumeBreakoutFixtures.setup() + rally, emptyList())),
            BacktestSettings(costPerSidePercent = 0.0),
        )
        val first = report.trades.firstOrNull()
        assertNotNull(first, "ожидалась сделка")
        assertEquals(19 * TF_MS, first.entryTimeMs)
        assertEquals(1.365, first.entryPrice, 1e-9)
        assertTrue(first.initialStop < 1.27, "стоп под базой ${first.initialStop}")
    }
}
