package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.ExitQuote
import tj.khujand.solana.trading.bot.core.engine.NO_PRICE_ALERT_CYCLES
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.TradeMonitor
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.DexScreenerPrice
import tj.khujand.solana.trading.bot.exchange.dex.JupiterPriceBatch
import tj.khujand.solana.trading.bot.exchange.dex.JupiterTokenPrice
import tj.khujand.solana.trading.bot.exchange.dex.Liquidity
import tj.khujand.solana.trading.bot.exchange.dex.PairSnapshot
import tj.khujand.solana.trading.bot.exchange.dex.PriceFeed
import tj.khujand.solana.trading.bot.exchange.dex.PriceSources
import tj.khujand.solana.trading.bot.exchange.dex.SOL_MINT
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair
import tj.khujand.solana.trading.bot.migrateStrategyTable

/**
 * Откуда монитор берёт цену для стопа и тейка: Jupiter → пара DexScreener → DexScreener по монете → котировка
 * продажи; смена источника со скачком ждёт подтверждения; цены нет совсем — предупреждение. DEMO, без сети:
 * вход 1.0, стоп 0.9, тейк 1.3, ликвидность на входе $100 000.
 */
class TradeMonitorPriceTest {

    private class Sources(private val clock: () -> Long) : PriceSources {
        var jupiterPrice: Double? = null
        var dexPrice: Double? = null
        override suspend fun jupiter(mints: List<String>): JupiterPriceBatch {
            val tokens = buildMap {
                put(SOL_MINT, JupiterTokenPrice(100.0, 1_000L))
                jupiterPrice?.let { p -> mints.filter { it != SOL_MINT }.forEach { put(it, JupiterTokenPrice(p, 1_000L)) } }
            }
            return JupiterPriceBatch(tokens, 0L, clock())
        }
        override suspend fun dexScreener(mint: String): DexScreenerPrice? =
            dexPrice?.let { DexScreenerPrice(it, 0L, clock()) }
    }

    private class Recorder : TradeNotifier {
        val sent = mutableListOf<String>()
        override suspend fun send(text: String) { sent += text }
    }

    private class Env(
        val db: DrxDatabase,
        val executor: TradeExecutor,
        val monitor: TradeMonitor,
        val sources: Sources,
        val notifier: Recorder,
    ) {
        var pairPrice: Double? = null
        var pairLiquidity: Double? = 100_000.0
        var pairUp = true
        var quote = ExitQuote(null, "нет маршрута продажи (тест)")
    }

    private fun env(): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db).apply { setDemoMode(true) }
        val executor = TradeExecutor(DexClient(), db, settings, AccountCache(db), ActivityLog())
        val sources = Sources { clock }
        val notifier = Recorder()
        lateinit var e: Env
        val monitor = TradeMonitor(
            DexClient(), db, executor, notifier, ActivityLog(),
            // Кеш цен Jupiter живёт 5 с — в тестах часы монитора и PriceFeed шагают по 15 с, как настоящий цикл.
            priceFeed = PriceFeed(sources) { clock },
            nowMs = { clock },
            pairSnapshot = {
                if (!e.pairUp) null
                else PairSnapshot(
                    TokenPair(pairAddress = it, priceUsd = e.pairPrice?.toString(), liquidity = Liquidity(e.pairLiquidity)),
                    ageMs = 0L, fetchedAtMs = clock,
                )
            },
            exitQuote = { e.quote },
        )
        e = Env(db, executor, monitor, sources, notifier)
        return e
    }

    private var clock = System.currentTimeMillis()

    /** Один цикл монитора; часы — на 15 с вперёд, чтобы кеш цен не перекрывал следующий цикл. */
    private suspend fun cycle(e: Env) {
        e.monitor.checkOnce()
        clock += 15_000L
    }

    private fun open(e: Env): String = runBlocking {
        val signal = Signal(
            mint = "mint1", symbol = "MEME", pairAddress = "pool1", confidence = 0.8, reason = "test",
            entryPrice = 1.0, stopLoss = 0.9, takeProfit = 1.3, priceSource = "JUPITER",
        )
        e.executor.openTrade(signal, StrategyConfig(id = "s1", name = "S1"), sizeUsd = 100.0, entryLiquidityUsd = 100_000.0)!!
    }

    private fun trade(e: Env, id: String): TradeRow = e.db.tradeQueries.getById(id).executeAsOne()

    @Test fun jupiterPriceDrivesStopEvenWhenDexScreenerLags() = runBlocking {
        val e = env()
        val id = open(e)
        assertEquals("JUPITER", trade(e, id).entry_price_source)
        e.sources.jupiterPrice = 0.85 // стоп 0.9 пробит
        e.pairPrice = 0.97            // DexScreener ещё показывает старую цену
        cycle(e)
        val t = trade(e, id)
        assertEquals("CLOSED", t.status)
        assertEquals("Stop Loss", t.close_reason)
        assertEquals(0.85, t.exit_price!!, 1e-9)
        assertEquals("JUPITER", t.exit_price_source)
    }

    @Test fun dexScreenerPairIsTheFallbackWhenJupiterHasNoPrice() = runBlocking {
        val e = env()
        val id = open(e)
        e.sources.jupiterPrice = null
        e.pairPrice = 1.35
        cycle(e)
        val t = trade(e, id)
        assertEquals("Take Profit", t.close_reason)
        assertEquals("DEXSCREENER", t.exit_price_source)
    }

    @Test fun sellQuoteIsTheLastResortWhenNoPriceSourceAnswers() = runBlocking {
        val e = env()
        val id = open(e)
        e.pairUp = false
        e.quote = ExitQuote(0.8, null)
        cycle(e)
        val t = trade(e, id)
        assertEquals("CLOSED", t.status)
        assertEquals("QUOTE", t.exit_price_source)
        assertEquals(0.8, t.exit_price!!, 1e-9)
    }

    @Test fun noPriceAtAllAlertsOnceAndKeepsPositionOpen() = runBlocking {
        val e = env()
        val id = open(e)
        e.pairUp = false
        repeat(NO_PRICE_ALERT_CYCLES + 3) { cycle(e) }
        assertEquals("OPEN", trade(e, id).status, "без цены позицию не трогаем")
        assertEquals(1, e.notifier.sent.size, "одно предупреждение, а не спам каждые 15 с")
        assertTrue("нет цены" in e.notifier.sent.single())
        assertTrue("нет маршрута" in e.notifier.sent.single(), "причина из котировки видна человеку")
    }

    @Test fun lagArtefactOnSourceSwitchDoesNotTriggerStop() = runBlocking {
        val e = env()
        val id = open(e)
        e.sources.jupiterPrice = 1.0
        e.pairPrice = 1.0
        cycle(e)
        // Jupiter пропал, DexScreener даёт 0.85 (ниже стопа) — это скачок при смене источника: ждём подтверждения.
        e.sources.jupiterPrice = null
        e.pairPrice = 0.85
        cycle(e)
        assertEquals("OPEN", trade(e, id).status)
        // Jupiter вернулся с прежним уровнем — артефакт отброшен, стоп не сработал.
        e.sources.jupiterPrice = 1.0
        cycle(e)
        assertEquals("OPEN", trade(e, id).status)
    }

    @Test fun confirmedLevelOnNewSourceDoesTriggerStop() = runBlocking {
        val e = env()
        val id = open(e)
        e.sources.jupiterPrice = 1.0
        cycle(e)
        e.sources.jupiterPrice = null
        e.pairPrice = 0.85
        cycle(e)
        assertEquals("OPEN", trade(e, id).status, "первая цена нового источника ждёт подтверждения")
        e.pairPrice = 0.84
        cycle(e)
        val t = trade(e, id)
        assertEquals("CLOSED", t.status, "уровень подтвердился — стоп исполнен")
        assertEquals("DEXSCREENER", t.exit_price_source)
    }

    @Test fun heldSwitchDoesNotRaiseTrailingPeak() = runBlocking {
        val e = env()
        val id = open(e)
        e.sources.jupiterPrice = 1.1
        cycle(e)
        assertEquals(1.1, trade(e, id).peak_price, 1e-9)
        e.sources.jupiterPrice = null
        e.pairPrice = 1.25 // отставший источник «выше рынка» — пик трейлинга от него не растёт
        cycle(e)
        assertEquals(1.1, trade(e, id).peak_price, 1e-9)
    }

    @Test fun liquidityStopStaysOnDexScreener() = runBlocking {
        val e = env()
        val id = open(e)
        e.sources.jupiterPrice = 1.05
        e.pairLiquidity = 40_000.0 // −60% ликвидности при пороге 50%
        cycle(e)
        val t = trade(e, id)
        assertEquals("CLOSED", t.status)
        assertTrue(t.close_reason!!.startsWith("Ликвидность-стоп"), t.close_reason)
        assertEquals("JUPITER", t.exit_price_source)
    }

    @Test fun demoMarketPnlEqualsPnl() = runBlocking {
        val e = env()
        val id = open(e)
        e.sources.jupiterPrice = 1.31
        cycle(e)
        val t = trade(e, id)
        assertEquals("Take Profit", t.close_reason)
        assertEquals(t.pnl!!, t.market_pnl, 1e-9)
        assertEquals(31.0, t.market_pnl, 1e-9)
    }
}
