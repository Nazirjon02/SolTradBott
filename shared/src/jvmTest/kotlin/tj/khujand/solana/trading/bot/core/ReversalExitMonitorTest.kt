package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.TradeMonitor
import tj.khujand.solana.trading.bot.core.strategy.REVERSAL_EXIT_REASON
import tj.khujand.solana.trading.bot.core.strategy.ReversalFixtures
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.migrateStrategyTable

/**
 * «Выход по развороту» в мониторе сделок на DEMO-счёте: свечи подаются подменой, время — фиксированное.
 * Разворот в свечах начинается с бара входа (65 баров аптренда до него).
 */
class ReversalExitMonitorTest {

    private val barMs = ReversalFixtures.BAR_MS
    private val config = StrategyConfig(id = "s1", name = "R", timeframe = "1m", takeProfitPercent = 500.0)

    private class Env(val db: DrxDatabase, val executor: TradeExecutor)

    private fun env(): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db)
        settings.setDemoMode(true)
        return Env(db, TradeExecutor(DexClient(), db, settings, AccountCache(db), ActivityLog()))
    }

    private fun open(e: Env): TradeRow = runBlocking {
        val signal = Signal(
            mint = "m", symbol = "MEME", pairAddress = "p", confidence = 0.8, reason = "test",
            entryPrice = 1.0, stopLoss = 0.5, takeProfit = 6.0,
        )
        val id = e.executor.openTrade(signal, config, sizeUsd = 1_000.0, entryLiquidityUsd = 0.0, entryAtrFrac = 0.05)!!
        e.db.tradeQueries.getById(id).executeAsOne()
    }

    private fun monitor(e: Env, candles: List<Candle>, now: Long, fetches: IntArray) = TradeMonitor(
        DexClient(), e.db, e.executor, NoopNotifier, ActivityLog(),
        protection = { ProtectionSettings(reversalExitEnabled = true) },
        candleSource = { _, _, _ -> fetches[0]++; candles },
        nowMs = { now },
    )

    /** Свечи [closes] так, что бар 65 — бар входа сделки [t]. */
    private fun candlesAroundEntry(t: TradeRow, closes: List<Double>): List<Candle> =
        ReversalFixtures.candles(closes, startMs = t.opened_at / barMs * barMs - 65 * barMs)

    @Test
    fun reversalAfterEntryClosesTrade() = runBlocking {
        val e = env()
        val t = open(e)
        val candles = candlesAroundEntry(t, ReversalFixtures.upThenDown())
        val now = candles.last().openTimeMs + barMs + 25_000L // последний бар закрылся 25 с назад
        val fetches = IntArray(1)
        monitor(e, candles, now, fetches).checkReversal(t, config, price = 1.4)

        val closed = e.db.tradeQueries.getById(t.id).executeAsOne()
        assertEquals("CLOSED", closed.status)
        assertEquals(REVERSAL_EXIT_REASON, closed.close_reason)
        assertEquals(1, fetches[0])
    }

    @Test
    fun checksOncePerClosedBar() = runBlocking {
        val e = env()
        val t = open(e)
        val candles = candlesAroundEntry(t, ReversalFixtures.uptrend()) // разворота нет
        val now = candles.last().openTimeMs + barMs + 25_000L
        val fetches = IntArray(1)
        val m = monitor(e, candles, now, fetches)
        m.checkReversal(t, config, price = 1.5)
        m.checkReversal(t, config, price = 1.5) // тот же закрытый бар — свечи второй раз не нужны

        assertEquals(1, fetches[0])
        assertEquals("OPEN", e.db.tradeQueries.getById(t.id).executeAsOne().status)
    }

    @Test
    fun waitsUntilClosedBarSettles() = runBlocking {
        val e = env()
        val t = open(e)
        val candles = candlesAroundEntry(t, ReversalFixtures.upThenDown())
        val now = candles.last().openTimeMs + barMs + 5_000L // бар закрылся 5 с назад — GeckoTerminal мог не успеть
        val fetches = IntArray(1)
        monitor(e, candles, now, fetches).checkReversal(t, config, price = 1.4)

        assertEquals(0, fetches[0])
        assertEquals("OPEN", e.db.tradeQueries.getById(t.id).executeAsOne().status)
    }
}
