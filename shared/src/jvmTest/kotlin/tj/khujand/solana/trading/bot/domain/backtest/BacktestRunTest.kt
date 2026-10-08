package tj.khujand.solana.trading.bot.domain.backtest

import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.strategy.BreakoutFixtures
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.GtPool
import tj.khujand.solana.trading.bot.exchange.dex.GtPoolList
import tj.khujand.solana.trading.bot.exchange.dex.OhlcvPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Полный прогон бэктеста на фикстуре и загрузчик истории без сети. */
class BacktestRunTest {

    private val tf = 300_000L

    private val coin = BacktestCoin("pool", "mint", "BP", "BP / SOL", "raydium", 0L, 1.4, 1e6, 1e5)

    private val cfg = StrategyConfig(
        id = "bp",
        name = "BP",
        type = StrategyType.BREAKOUT_PULLBACK.name,
        timeframe = "5m",
        bpRequireHtfTrend = false,
        scanFiltersEnabled = false,
    )

    /** Сетап (триггер — бар 31) + продолжение вверх: вход по открытию бара 32. */
    private fun series(): List<Candle> = BreakoutFixtures.setup() + (32 until 40).map { i ->
        val o = 1.385 + (i - 32) * 0.03
        Candle(i * tf, o, o + 0.035, o - 0.005, o + 0.03, 120.0)
    }

    @Test
    fun breakoutPullbackEntersOnNextBarOpen() = runBlocking {
        val report = Backtester.run(cfg, listOf(CoinSeries(coin, series(), emptyList())), BacktestSettings(costPerSidePercent = 0.0))
        assertTrue(report.trades.isNotEmpty(), "ожидалась сделка")
        val first = report.trades.first()
        assertEquals(32 * tf, first.entryTimeMs)
        assertEquals(1.385, first.entryPrice, 1e-9)
        assertTrue(first.initialStop < first.entryPrice)
        assertEquals(1, report.coinsWithData)
    }

    @Test
    fun noSignalsWithoutSetup() = runBlocking {
        val report = Backtester.run(cfg, listOf(CoinSeries(coin, BreakoutFixtures.base(), emptyList())), BacktestSettings())
        assertEquals(0, report.trades.size)
        assertEquals(0, report.stats.trades)
    }

    @Test
    fun scanFiltersUseEstimatedHistoricMetrics() {
        // Сейчас MC 1M при цене 1.4; в прошлом при цене 0.7 → MC ≈ 500K, ликвидность ≈ 100K × √0.5.
        val candles = listOf(Candle(0, 0.7, 0.7, 0.7, 0.7, 10.0), Candle(tf, 0.7, 0.7, 0.7, 0.7, 10.0))
        val c = Backtester.candidateAt(coin, candles, 1, tf, nowMs = 2 * tf)
        assertEquals(500_000.0, c.marketCap, 1e-6)
        assertEquals(1e5 * kotlin.math.sqrt(0.5), c.liquidityUsd, 1e-6)
        assertEquals(20.0, c.volumeH1Usd, 1e-9)
    }

    @Test
    fun loaderPagesBackUntilCutoffAndRetriesOnRateLimit() = runBlocking {
        val now = 3_000L * tf
        val all = (0 until 3_000).map { i -> Candle(i * tf, 1.0, 1.0, 1.0, 1.0, 1.0) }
        val befores = mutableListOf<Long?>()
        var rateLimitedOnce = false
        val sleeps = mutableListOf<Long>()
        val loader = BacktestDataLoader(
            pageSource = { _, _, _, limit, before ->
                befores += before
                if (!rateLimitedOnce) {
                    rateLimitedOnce = true
                    OhlcvPage(emptyList(), 429)
                } else {
                    val older = all.filter { before == null || it.openTimeMs / 1000 < before }
                    OhlcvPage(older.takeLast(limit), 200)
                }
            },
            minRequestIntervalMs = 0,
            rateLimitBackoffMs = 1_000,
            nowMs = { now },
            sleep = { sleeps += it },
        )
        // 7 дней 5m = 2016 свечей → 3 страницы (+1 повтор после 429).
        val candles = loader.loadHistory("pool", "5m", cutoffMs = now - 7 * 86_400_000L)
        assertEquals(2016, candles.size)
        assertEquals(candles.sortedBy { it.openTimeMs }, candles)
        assertEquals(listOf(1_000L), sleeps)
        assertEquals(4, befores.size)
        assertEquals(null, befores[0])
        assertEquals(null, befores[1])
    }

    @Test
    fun loaderSkipsMajorsAndDuplicateTokens() = runBlocking {
        fun pool(addr: String, mint: String) = GtPool(addr, "$addr / SOL", mint, "So1", "raydium", 1.0, 1e6, null, 1e5, 0L)
        val loader = BacktestDataLoader(
            poolSource = { _, page ->
                when (page) {
                    1 -> listOf(
                        pool("p1", "So11111111111111111111111111111111111111112"),
                        pool("p2", "MEME1"),
                        pool("p3", "MEME1"),
                    ) to 200
                    2 -> listOf(pool("p4", "MEME2"), pool("p5", "MEME3")) to 200
                    else -> emptyList<GtPool>() to 200
                }
            },
            minRequestIntervalMs = 0,
            sleep = {},
        )
        val coins = loader.loadCoins(GtPoolList.TRENDING, count = 2)
        assertEquals(listOf("p2", "p4"), coins.map { it.pool })
        assertEquals("p2", coins.first().symbol)
        assertEquals(1e6, coins.first().marketCapNow) // нет market_cap → FDV
    }
}
