package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.seedDefaultStrategies

class RiskManagerTest {

    private fun inMemoryDb(): DrxDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        return DrxDatabase(driver)
    }

    private fun insertClosedTrade(db: DrxDatabase, strategyId: String, pnl: Double, pnlPercent: Double) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.tradeQueries.insert(
            id = "t-$pnl-${kotlin.random.Random.nextInt()}",
            strategy_id = strategyId, strategy_name = "test", mint = "m", symbol = "TST",
            pair_address = "p", entry_price = 1.0, exit_price = 1.0, qty = 100.0, qty_remaining = 0.0,
            size_usd = 100.0, size_sol = 0.0, stop_loss = 0.8, take_profit = 1.3, peak_price = 1.0,
            tp1_done = 0, tp2_done = 0, entry_liquidity_usd = 0.0, pnl = pnl, pnl_percent = pnlPercent,
            fee = null, status = "CLOSED", open_reason = "test", close_reason = "test",
            is_demo = 1, opened_at = now, closed_at = now,
        )
    }

    private fun insertOpenTrade(db: DrxDatabase, strategyId: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.tradeQueries.insert(
            id = "open-${kotlin.random.Random.nextInt()}",
            strategy_id = strategyId, strategy_name = "test", mint = "m", symbol = "TST",
            pair_address = "p", entry_price = 1.0, exit_price = null, qty = 100.0, qty_remaining = 100.0,
            size_usd = 100.0, size_sol = 0.0, stop_loss = 0.8, take_profit = 1.3, peak_price = 1.0,
            tp1_done = 0, tp2_done = 0, entry_liquidity_usd = 0.0, pnl = null, pnl_percent = null,
            fee = null, status = "OPEN", open_reason = "test", close_reason = null,
            is_demo = 1, opened_at = now, closed_at = null,
        )
    }

    @Test
    fun canTradeWhenClean() {
        val db = inMemoryDb()
        val cache = AccountCache(db)
        cache.set("DEMO_USD", 1000.0, 1000.0)
        val rm = RiskManager(db, cache)
        assertTrue(rm.canTrade(StrategyConfig(id = "s1")))
    }

    @Test
    fun blocksOnDailyLoss() {
        val db = inMemoryDb()
        val cache = AccountCache(db)
        cache.set("DEMO_USD", 1000.0, 1000.0)
        val rm = RiskManager(db, cache)
        val config = StrategyConfig(id = "s1", maxDailyLoss = 5.0) // лимит: -50 USD
        insertClosedTrade(db, "s1", pnl = -60.0, pnlPercent = -6.0)
        assertFalse(rm.canTrade(config), "дневной убыток -60 при лимите -50 должен блокировать")
    }

    @Test
    fun blocksOnMaxPositions() {
        val db = inMemoryDb()
        val cache = AccountCache(db)
        cache.set("DEMO_USD", 1000.0, 1000.0)
        val rm = RiskManager(db, cache)
        val config = StrategyConfig(id = "s1", maxPositions = 1)
        insertOpenTrade(db, "s1")
        assertFalse(rm.canTrade(config))
    }

    @Test
    fun blocksOnDrawdown() {
        val db = inMemoryDb()
        val cache = AccountCache(db)
        cache.set("DEMO_USD", 100_000.0, 100_000.0) // большой баланс, чтобы дневной лимит не мешал
        val rm = RiskManager(db, cache)
        val config = StrategyConfig(id = "s1", maxDrawdown = 15.0)
        insertClosedTrade(db, "s1", pnl = -20.0, pnlPercent = -20.0)
        assertFalse(rm.canTrade(config), "просадка -20% при лимите 15% должна блокировать")
    }

    @Test
    fun positionSizeFromBalance() {
        val db = inMemoryDb()
        val cache = AccountCache(db)
        cache.set("DEMO_USD", 2000.0, 2000.0)
        val rm = RiskManager(db, cache)
        val size = rm.calculatePositionSizeUsd(StrategyConfig(id = "s1", positionSize = 5.0))
        assertEquals(100.0, size, 0.001)
    }

    @Test
    fun realModeExcludesDemoBalance() {
        val db = inMemoryDb()
        val cache = AccountCache(db)
        // Реальный кошелёк $500 + фантомный демо-счёт $10 000, оставшийся в кеше.
        cache.set("SOL", 5.0, 500.0)
        cache.set("DEMO_USD", 10_000.0, 10_000.0)
        val rm = RiskManager(db, cache, isDemo = { false })
        // В REAL размер считается ТОЛЬКО от реального баланса: 5% от $500 = $25 (не от $10 500).
        val size = rm.calculatePositionSizeUsd(StrategyConfig(id = "s1", positionSize = 5.0))
        assertEquals(25.0, size, 0.001)
    }

    @Test
    fun seedCreatesThreeStrategies() {
        val db = inMemoryDb()
        seedDefaultStrategies(db)
        val all = db.strategyQueries.getAll().executeAsList()
        assertEquals(3, all.size)
        assertEquals(1, all.count { it.is_active == 1L })
        assertEquals("DARS", all.first { it.is_active == 1L }.type)
    }
}
