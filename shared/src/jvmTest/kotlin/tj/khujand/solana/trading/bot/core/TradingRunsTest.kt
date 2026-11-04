package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.beginNewRun
import tj.khujand.solana.trading.bot.core.engine.runStartedAt
import tj.khujand.solana.trading.bot.core.engine.runSummaries
import tj.khujand.solana.trading.bot.core.engine.startNewRun
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.migrateStrategyTable

/** «Новый старт»: забеги по режимам, итоги по сделкам, лимиты убытка — с начала забега. */
class TradingRunsTest {

    private val now = System.currentTimeMillis()
    private val hour = 60 * 60_000L

    private fun db(): DrxDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        return DrxDatabase(driver)
    }

    private fun closed(db: DrxDatabase, id: String, pnl: Double, closedAt: Long, demo: Boolean = true, strategy: String = "s1") {
        db.tradeQueries.insert(
            id = id, strategy_id = strategy, strategy_name = strategy, mint = "m-$id", symbol = "T", pair_address = "p",
            entry_price = 1.0, exit_price = 1.0, qty = 100.0, qty_remaining = 0.0, size_usd = 100.0, size_sol = 0.0,
            stop_loss = 0.9, take_profit = 1.3, peak_price = 1.0, tp1_done = 0, tp2_done = 0, entry_liquidity_usd = 0.0,
            pnl = pnl, pnl_percent = pnl, fee = 0.5, status = "CLOSED", open_reason = "t", close_reason = "t",
            is_demo = if (demo) 1 else 0, opened_at = closedAt, closed_at = closedAt,
        )
    }

    @Test
    fun runsArePerModeAndKeepPastTotals() {
        val db = db()
        assertEquals(0L, db.runStartedAt(true), "стартов не было — вся история")
        closed(db, "old", pnl = -40.0, closedAt = now - 5 * hour)
        db.startNewRun(demo = true, nowMs = now - 3 * hour, equityUsd = 960.0, startBalance = 960.0)
        closed(db, "a", pnl = 30.0, closedAt = now - 2 * hour, strategy = "Dars")
        closed(db, "b", pnl = -10.0, closedAt = now - hour, strategy = "Dars")
        closed(db, "real", pnl = 99.0, closedAt = now - hour, demo = false)

        assertEquals(now - 3 * hour, db.runStartedAt(true))
        assertEquals(0L, db.runStartedAt(false), "забег DEMO не трогает REAL")

        val runs = runSummaries(db, demo = true)
        assertEquals(listOf(0, 1), runs.map { it.number }, "сделки до первого старта — забег №0")
        assertEquals(-40.0, runs[0].pnl, 1e-9)
        val current = runs[1]
        assertTrue(current.current)
        assertEquals(2, current.trades)
        assertEquals(1, current.wins)
        assertEquals(20.0, current.pnl, 1e-9)
        assertEquals(20.0 / 960.0 * 100, current.pnlPercent!!, 1e-9)
        assertEquals(listOf("Dars"), current.strategies)

        // Второй старт закрывает первый забег с итоговым капиталом.
        db.startNewRun(demo = true, nowMs = now, equityUsd = 980.0, startBalance = 980.0)
        val after = runSummaries(db, demo = true)
        assertEquals(listOf(0, 1, 2), after.map { it.number })
        assertEquals(now, after[1].endedAt)
        assertEquals(980.0, after[1].endBalance)
        assertEquals(0, after[2].trades)
    }

    @Test
    fun newRunResetsLossLimitsAndSetsDemoBalance() {
        val db = db()
        val settings = SettingsStore(db).apply { setDemoMode(true) }
        val cache = AccountCache(db)
        val executor = TradeExecutor(DexClient(), db, settings, cache, ActivityLog())
        executor.setDemoBalance(1_000.0)
        val risk = RiskManager(db, cache, isDemo = executor::isDemo, protection = settings::getProtection)

        closed(db, "l", pnl = -150.0, closedAt = now - 60_000L) // −150 за день при лимите 10% капитала
        assertTrue("за день" in assertNotNull(risk.portfolioBlockReason()))

        val message = beginNewRun(db, executor, risk, newDemoBalance = 5_000.0, nowMs = System.currentTimeMillis())
        assertTrue(message.contains("Новый старт №1"), message)
        assertNull(risk.portfolioBlockReason(), "убыток до старта лимит больше не видит")
        assertEquals(5_000.0, executor.demoBalanceUsd(), 1e-9)
        assertEquals(5_000.0, runSummaries(db, demo = true).last().startBalance)
    }
}
