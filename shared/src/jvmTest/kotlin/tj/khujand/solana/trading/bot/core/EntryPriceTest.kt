package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.EntryLoss
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager.FreshEntry
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.DexScreenerPrice
import tj.khujand.solana.trading.bot.exchange.dex.JupiterPriceBatch
import tj.khujand.solana.trading.bot.exchange.dex.JupiterTokenPrice
import tj.khujand.solana.trading.bot.exchange.dex.PriceFeed
import tj.khujand.solana.trading.bot.exchange.dex.PriceSources
import tj.khujand.solana.trading.bot.exchange.dex.SOL_MINT
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner
import tj.khujand.solana.trading.bot.migrateStrategyTable

/**
 * Цена входа по сигналу: живая цена Jupiter (запасной — DexScreener) не старше минуты заменяет цену сигнала,
 * стоп и тейк тянутся за ней; свежей цены нет — вход запрещён (NO_PRICE), убежала больше 3% — PRICE_RAN.
 */
class EntryPriceTest {

    private var now = System.currentTimeMillis()

    private class Sources(private val clock: () -> Long) : PriceSources {
        var jupiter: JupiterTokenPrice? = null
        var dex: Pair<Double, Long>? = null // цена, Age мс
        override suspend fun jupiter(mints: List<String>): JupiterPriceBatch {
            val tokens = buildMap {
                put(SOL_MINT, JupiterTokenPrice(100.0, 10_000L))
                jupiter?.let { put("mint1", it) }
            }
            return JupiterPriceBatch(tokens, 0L, clock())
        }
        override suspend fun dexScreener(mint: String): DexScreenerPrice? =
            dex?.let { (p, age) -> DexScreenerPrice(p, age, clock()) }
    }

    private val sources = Sources { now }

    private val manager: StrategyManager = run {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db).apply { setDemoMode(true) }
        val cache = AccountCache(db)
        val client = DexClient()
        val executor = TradeExecutor(client, db, settings, cache, ActivityLog())
        val risk = RiskManager(db, cache, isDemo = executor::isDemo, protection = settings::getProtection)
        StrategyManager(
            client, risk, NoopNotifier, db, TokenScanner(client, TokenCache(db)), executor, ActivityLog(), settings,
            priceFeed = PriceFeed(sources) { now },
        )
    }

    private val signal = Signal(
        mint = "mint1", symbol = "MEME", pairAddress = "pool1", confidence = 0.8, reason = "test",
        entryPrice = 1.0, stopLoss = 0.9, takeProfit = 1.3,
    )

    @Test fun jupiterPriceReplacesSignalPriceAndScalesStops() = runBlocking {
        sources.jupiter = JupiterTokenPrice(1.02, 10_000L)
        sources.dex = 1.0 to 0L
        val s = assertIs<FreshEntry.Ready>(manager.withFreshEntryPrice(signal)).signal
        assertEquals(1.02, s.entryPrice, 1e-12)
        assertEquals(0.918, s.stopLoss, 1e-12)
        assertEquals(1.326, s.takeProfit, 1e-12)
        assertEquals("JUPITER", s.priceSource)
    }

    @Test fun dexScreenerIsUsedWhenJupiterHasNoPrice() = runBlocking {
        sources.dex = 1.01 to 15_000L
        val s = assertIs<FreshEntry.Ready>(manager.withFreshEntryPrice(signal)).signal
        assertEquals(1.01, s.entryPrice, 1e-12)
        assertEquals("DEXSCREENER", s.priceSource)
    }

    @Test fun noFreshPriceForbidsEntry() = runBlocking {
        assertEquals(FreshEntry.Lost(EntryLoss.NO_PRICE), manager.withFreshEntryPrice(signal), "оба источника молчат")
        // Jupiter: последняя сделка монеты на 1 000 слотов раньше SOL (≥ 3 мин); DexScreener: кеш 90 с.
        sources.jupiter = JupiterTokenPrice(1.0, 9_000L)
        sources.dex = 1.0 to 90_000L
        assertEquals(FreshEntry.Lost(EntryLoss.NO_PRICE), manager.withFreshEntryPrice(signal), "у обоих цена устарела")
    }

    @Test fun priceThatRanAwayCancelsEntry() = runBlocking {
        sources.jupiter = JupiterTokenPrice(1.05, 10_000L)
        assertEquals(FreshEntry.Lost(EntryLoss.PRICE_RAN), manager.withFreshEntryPrice(signal))
    }
}
