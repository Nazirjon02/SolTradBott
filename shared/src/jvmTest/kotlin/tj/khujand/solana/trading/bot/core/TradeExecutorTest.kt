package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient

/** DEMO-исполнение: открытие/закрытие/частичные фиксации на виртуальном счёте. */
class TradeExecutorTest {

    private fun makeExecutor(): Triple<TradeExecutor, DrxDatabase, AccountCache> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db)
        settings.setDemoMode(true)
        val cache = AccountCache(db)
        val executor = TradeExecutor(DexClient(), db, settings, cache, ActivityLog())
        return Triple(executor, db, cache)
    }

    private fun signal(entry: Double = 0.001) = Signal(
        mint = "mint1", symbol = "MEME", pairAddress = "pair1",
        confidence = 0.8, reason = "test",
        entryPrice = entry, stopLoss = entry * 0.85, takeProfit = entry * 1.30,
    )

    @Test
    fun demoOpenDecrementsBalance() = runBlocking {
        val (executor, db, _) = makeExecutor()
        assertEquals(10_000.0, executor.demoBalanceUsd(), 0.001)

        val id = executor.openTrade(signal(), StrategyConfig(id = "s1", name = "S1"), sizeUsd = 500.0, entryLiquidityUsd = 20_000.0)
        assertNotNull(id)
        assertEquals(9_500.0, executor.demoBalanceUsd(), 0.001)

        val trade = db.tradeQueries.getById(id).executeAsOne()
        assertEquals("OPEN", trade.status)
        assertEquals(500.0, trade.size_usd, 0.001)
        assertEquals(trade.qty, trade.qty_remaining, 1e-9)
    }

    @Test
    fun demoOpenFailsWithoutBalance() = runBlocking {
        val (executor, _, _) = makeExecutor()
        val id = executor.openTrade(signal(), StrategyConfig(id = "s1"), sizeUsd = 50_000.0, entryLiquidityUsd = 0.0)
        assertNull(id, "вход больше демо-баланса должен отклоняться")
    }

    @Test
    fun demoFullCloseWithProfit() = runBlocking {
        val (executor, db, _) = makeExecutor()
        val entry = 0.001
        val id = executor.openTrade(signal(entry), StrategyConfig(id = "s1"), sizeUsd = 1_000.0, entryLiquidityUsd = 0.0)!!
        val trade = db.tradeQueries.getById(id).executeAsOne()

        // +30% к цене → закрытие всей позиции
        val result = executor.closeTrade(trade, currentPrice = entry * 1.3, portionOfOriginal = 100.0, reason = "Take Profit")
        assertNotNull(result)
        assertTrue(result.isFull)
        assertEquals(300.0, result.pnlUsd, 0.5)

        val closed = db.tradeQueries.getById(id).executeAsOne()
        assertEquals("CLOSED", closed.status)
        assertEquals("Take Profit", closed.close_reason)
        assertEquals(30.0, closed.pnl_percent ?: 0.0, 0.5)

        // Баланс: 10000 - 1000 + 1000 + 300 = 10300
        assertEquals(10_300.0, executor.demoBalanceUsd(), 1.0)
    }

    @Test
    fun demoPartialThenFullClose() = runBlocking {
        val (executor, db, _) = makeExecutor()
        val entry = 0.002
        val id = executor.openTrade(signal(entry), StrategyConfig(id = "s1"), sizeUsd = 1_000.0, entryLiquidityUsd = 0.0)!!
        var trade = db.tradeQueries.getById(id).executeAsOne()

        // Частичная фиксация 50% на +15%
        val partial = executor.closeTrade(trade, entry * 1.15, portionOfOriginal = 50.0, reason = "TP1", tp1Done = true)
        assertNotNull(partial)
        assertTrue(!partial.isFull)
        assertEquals(75.0, partial.pnlUsd, 0.5) // 500 * 15%

        trade = db.tradeQueries.getById(id).executeAsOne()
        assertEquals("OPEN", trade.status)
        assertEquals(1L, trade.tp1_done)
        assertEquals(trade.qty / 2, trade.qty_remaining, trade.qty * 1e-6)

        // Добиваем остаток на -10% от входа
        val full = executor.closeTrade(trade, entry * 0.9, portionOfOriginal = 100.0, reason = "Stop Loss")
        assertNotNull(full)
        assertTrue(full.isFull)
        assertEquals(-50.0, full.pnlUsd, 0.5) // 500 * -10%

        val closed = db.tradeQueries.getById(id).executeAsOne()
        assertEquals("CLOSED", closed.status)
        assertEquals(75.0 - 50.0, closed.pnl ?: 0.0, 1.0) // суммарный PnL накоплен
    }

    @Test
    fun resetDemoBalance() = runBlocking {
        val (executor, _, _) = makeExecutor()
        executor.openTrade(signal(), StrategyConfig(id = "s1"), sizeUsd = 500.0, entryLiquidityUsd = 0.0)
        executor.resetDemoBalance()
        assertEquals(10_000.0, executor.demoBalanceUsd(), 0.001)
    }
}
