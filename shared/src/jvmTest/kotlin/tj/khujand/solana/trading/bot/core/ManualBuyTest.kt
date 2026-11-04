package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.MANUAL_STRATEGY_ID
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager.ManualResult
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner
import tj.khujand.solana.trading.bot.migrateStrategyTable

/**
 * Ручная покупка (порт ManualEntryRiskTest из MRX): человек рядом с ботом проходит те же лимиты портфеля,
 * что и бот, а сделку дальше ведёт монитор по стопу и тейку из диалога. DEMO, без сети.
 */
class ManualBuyTest {

    private class Env(val db: DrxDatabase, val manager: StrategyManager, val executor: TradeExecutor)

    private fun env(protection: ProtectionSettings = ProtectionSettings(guardEnabled = false)): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db).apply { setDemoMode(true); setProtection(protection) }
        val cache = AccountCache(db)
        val client = DexClient()
        val executor = TradeExecutor(client, db, settings, cache, ActivityLog())
        executor.setDemoBalance(1_000.0)
        val risk = RiskManager(db, cache, isDemo = executor::isDemo, protection = settings::getProtection)
        val manager = StrategyManager(client, risk, NoopNotifier, db, TokenScanner(client, TokenCache(db)), executor, ActivityLog(), settings)
        return Env(db, manager, executor)
    }

    private fun coin(mint: String = "mint1") = TokenCandidate(
        mint = mint, symbol = "MEME", name = "Meme", pairAddress = "pool-$mint", dexId = "raydium",
        priceUsd = 2.0, liquidityUsd = 50_000.0, marketCap = 1e6, volumeH1Usd = 1e4, buysH1 = 0, sellsH1 = 0,
        priceChangeM5 = 0.0, priceChangeH1 = 0.0, tokenAgeMinutes = 600, score = 0.0, scannedAt = 0L,
    )

    @Test
    fun manualBuyOpensTradeWithDialogStopAndTake() = runBlocking {
        val e = env()
        val r = e.manager.openManual(coin(), sizeUsd = 100.0, stopLossPercent = 10.0, takeProfitPercent = 50.0, livePrice = 2.5)
        assertIs<ManualResult.Opened>(r)
        val t = e.db.tradeQueries.getOpenTrades().executeAsList().single()
        assertEquals(MANUAL_STRATEGY_ID, t.strategy_id)
        assertEquals(2.5, t.entry_price, 1e-9) // живая цена, а не цена кандидата
        assertEquals(2.25, t.stop_loss, 1e-9)
        assertEquals(3.75, t.take_profit, 1e-9)
        assertEquals(900.0, e.executor.demoBalanceUsd(), 1e-9)
    }

    @Test
    fun secondManualBuyOfOpenCoinIsRejected() = runBlocking {
        val e = env()
        e.manager.openManual(coin(), 100.0, 10.0, 50.0, null)
        val again = assertIs<ManualResult.Rejected>(e.manager.openManual(coin(), 100.0, 10.0, 50.0, null))
        assertTrue(again.reason.contains("открытая позиция"), again.reason)
    }

    @Test
    fun portfolioLimitsApplyToManualBuys() = runBlocking {
        val e = env(ProtectionSettings(guardEnabled = false, maxOpenPositions = 1))
        assertIs<ManualResult.Opened>(e.manager.openManual(coin("a"), 100.0, 10.0, 50.0, null))
        val denied = assertIs<ManualResult.Rejected>(e.manager.openManual(coin("b"), 100.0, 10.0, 50.0, null))
        assertTrue(denied.reason.startsWith("портфель"), denied.reason)
    }

    @Test
    fun badInputAndEmptyBalanceAreRejected() = runBlocking {
        val e = env()
        assertIs<ManualResult.Rejected>(e.manager.openManual(coin(), 0.0, 10.0, 50.0, null))
        assertIs<ManualResult.Rejected>(e.manager.openManual(coin(), 100.0, 95.0, 50.0, null))
        val broke = assertIs<ManualResult.Rejected>(e.manager.openManual(coin(), 5_000.0, 10.0, 50.0, null))
        assertTrue(broke.reason.contains("демо-счёте"), broke.reason)
        assertTrue(e.db.tradeQueries.getOpenTrades().executeAsList().isEmpty())
    }
}
