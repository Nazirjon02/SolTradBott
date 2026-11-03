package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.QuoteResult
import tj.khujand.solana.trading.bot.exchange.dex.SwapQuote

/**
 * SHADOW: DEMO-счёт, но вход и выход — по котировкам Jupiter, и неудачная продажа ведёт себя как в REAL
 * (счётчик неудач, рост slippage, пауза). Рынок в тестах: токен $0.001, SOL $100 → по рынку 100 raw-единиц
 * (6 знаков) за lamport; котировка покупки даёт 98 — на 2% хуже рынка.
 */
class ShadowExecutionTest {

    private class Env(val db: DrxDatabase, val executor: TradeExecutor, val quotes: FakeSwapQuotes, val settings: SettingsStore)

    private fun env(shadow: Boolean = true): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db)
        settings.setDemoMode(true)
        settings.setShadowQuotes(shadow)
        val quotes = FakeSwapQuotes(tokensPerLamport = 98.0)
        val executor = TradeExecutor(DexClient(), db, settings, AccountCache(db), ActivityLog(), quotes)
        return Env(db, executor, quotes, settings)
    }

    private val config = StrategyConfig(id = "s1", name = "S1")

    private fun signal() = Signal(
        mint = "mint1", symbol = "MEME", pairAddress = "pair1", confidence = 0.8, reason = "test",
        entryPrice = 0.001, stopLoss = 0.00085, takeProfit = 0.0013,
    )

    private fun open(e: Env, buyQuote: SwapQuote? = null): String = runBlocking {
        e.executor.openTrade(signal(), config, sizeUsd = 100.0, entryLiquidityUsd = 0.0, buyQuote = buyQuote)!!
    }

    private fun trade(e: Env, id: String): TradeRow = e.db.tradeQueries.getById(id).executeAsOne()

    @Test fun shadowEntryTakesAmountsFromQuote() {
        val e = env()
        val t = trade(e, open(e))
        assertEquals("SHADOW", t.exec_mode)
        assertEquals(98_000.0, t.qty, 1e-6) // 1e9 lamports × 98 raw / 1e6; по рынку было бы 100 000
        assertEquals(100.0, t.size_usd, 1e-9)
        assertEquals(1.0, t.size_sol, 1e-12)
        assertEquals(0.001, t.entry_price) // цена входа — рыночная, издержки — в количестве
        assertEquals(9_900.0, e.executor.demoBalanceUsd(), 1e-6)
    }

    @Test fun shadowExitTakesProceedsFromSellQuote() = runBlocking {
        val e = env()
        val id = open(e)
        // Цена выросла на 20%: за lamport дают в 1.2 раза меньше токенов.
        e.quotes.tokensPerLamport = 100.0 / 1.2
        val result = assertNotNull(e.executor.closeTrade(trade(e, id), currentPrice = 0.0012, portionOfOriginal = 100.0, reason = "TP"))
        // 9.8e10 raw / 83.33 × 0.98 = 1.15248e9 lamports = $115.248: +20% рынка минус издержки входа и выхода.
        assertEquals(15.248, result.pnlUsd, 0.01)
        assertTrue(result.isFull)
        val t = trade(e, id)
        assertEquals("CLOSED", t.status)
        assertEquals(0.00115248, t.exit_price!!, 1e-8)
        assertEquals(10_015.248, e.executor.demoBalanceUsd(), 0.01)
        assertTrue("sell:98000000000@300" in e.quotes.calls)
    }

    @Test fun failedShadowExitCountsFailuresAndEscalatesSlippage() = runBlocking {
        val e = env()
        val id = open(e)
        e.quotes.sell = { QuoteResult.NoRoute("COULD_NOT_FIND_ANY_ROUTE") }
        repeat(4) { i ->
            assertNull(e.executor.closeTrade(trade(e, id), 0.0009, 100.0, "Stop Loss"))
            val t = trade(e, id)
            assertEquals("OPEN", t.status)
            assertEquals((i + 1).toLong(), t.exit_failures)
            assertTrue("маршрута" in t.exit_note)
        }
        // Slippage растёт с каждой неудачей: 3% → 6% → 12% → потолок 15%.
        val slippages = e.quotes.calls.filter { it.startsWith("sell") }.map { it.substringAfter('@').toInt() }
        assertEquals(listOf(300, 600, 1200, 1500), slippages)
        // С третьей неудачи подряд — «продажа заблокирована»: следующая попытка не раньше паузы.
        assertTrue(trade(e, id).exit_retry_at > System.currentTimeMillis())
        assertEquals(9_900.0, e.executor.demoBalanceUsd(), 1e-6, "неудачная продажа не трогает баланс")

        // Маршрут вернулся — частичная продажа проходит, счётчик неудач сброшен.
        e.quotes.sell = null
        assertNotNull(e.executor.closeTrade(trade(e, id), 0.0009, 50.0, "TP1", tp1Done = true))
        val t = trade(e, id)
        assertEquals(0L, t.exit_failures)
        assertEquals("", t.exit_note)
        assertEquals(0L, t.exit_retry_at)
        assertEquals(49_000.0, t.qty_remaining, 1e-6)
    }

    @Test fun noBuyRouteMeansNoEntry() = runBlocking {
        val e = env()
        e.quotes.buy = { QuoteResult.NoRoute("TOKEN_NOT_TRADABLE") }
        assertNull(e.executor.openTrade(signal(), config, sizeUsd = 100.0, entryLiquidityUsd = 0.0))
        assertEquals(10_000.0, e.executor.demoBalanceUsd(), 1e-6)
    }

    @Test fun freshGuardQuoteIsReused() {
        val e = env()
        val pre = SwapQuote(1_000_000_000L, 99_000_000_000L, 0.1, JsonObject(emptyMap()), System.currentTimeMillis())
        val t = trade(e, open(e, buyQuote = pre))
        assertTrue(e.quotes.calls.none { it.startsWith("buy") }, "котировка из проверки входа не запрашивается заново")
        assertEquals(99_000.0, t.qty, 1e-6)
    }

    @Test fun staleGuardQuoteIsRefreshed() {
        val e = env()
        val stale = SwapQuote(1_000_000_000L, 99_000_000_000L, 0.1, JsonObject(emptyMap()), System.currentTimeMillis() - 60_000L)
        val t = trade(e, open(e, buyQuote = stale))
        assertTrue(e.quotes.calls.any { it.startsWith("buy") })
        assertEquals(98_000.0, t.qty, 1e-6)
    }

    @Test fun shadowTradeExitsByQuoteEvenAfterShadowTurnedOff() = runBlocking {
        val e = env()
        val id = open(e)
        e.settings.setShadowQuotes(false)
        assertNotNull(e.executor.closeTrade(trade(e, id), 0.001, 100.0, "manual"))
        assertTrue(e.quotes.calls.any { it.startsWith("sell") }, "сделка SHADOW закрывается по котировке")
    }

    @Test fun plainDemoIgnoresQuotes() = runBlocking {
        val e = env(shadow = false)
        val id = open(e)
        val t = trade(e, id)
        assertEquals("DEMO", t.exec_mode)
        assertEquals(100_000.0, t.qty, 1e-6)
        val result = assertNotNull(e.executor.closeTrade(t, 0.0012, 100.0, "TP"))
        assertEquals(20.0, result.pnlUsd, 1e-6)
        assertTrue(e.quotes.calls.isEmpty())
    }
}
