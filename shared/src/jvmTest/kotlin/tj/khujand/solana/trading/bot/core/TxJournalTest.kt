package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.BuyIntent
import tj.khujand.solana.trading.bot.core.engine.SellIntent
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.crypto.encodeBase58
import tj.khujand.solana.trading.bot.crypto.transactionSignatureBase58
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Tx_journal
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient

/**
 * Журнал REAL-транзакций: подпись известна до отправки, итог учитывается ровно один раз — обычным путём
 * или сверкой (поздно подтверждённая покупка записывает сделку, продажа — выход), продажа «в полёте»
 * не даёт отправить вторую. Сетевые вызовы сверки здесь не участвуют — проверяется учёт итога.
 */
class TxJournalTest {

    private val json = Json { encodeDefaults = true }

    private class Env(val db: DrxDatabase, val executor: TradeExecutor)

    private fun env(): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db)
        settings.setDemoMode(false)
        return Env(db, TradeExecutor(DexClient(), db, settings, AccountCache(db), ActivityLog(), FakeSwapQuotes()))
    }

    private fun buyIntent() = BuyIntent(
        signal = Signal("mintX", "MEME", "pair", 0.8, "сетап", entryPrice = 0.001, stopLoss = 0.0009, takeProfit = 0.0013),
        strategyId = "s1",
        strategyName = "S1",
        lamportsIn = 500_000_000L,
        quotedOut = 6_000_000L,
        solPriceUsd = 100.0,
        entryLiquidityUsd = 20_000.0,
        entryAtrFrac = 0.03,
        tokenBefore = 0L,
    )

    private fun journal(db: DrxDatabase, sig: String, kind: String, payload: String, tradeId: String? = null, createdAt: Long = 1_000L): Tx_journal {
        db.txJournalQueries.insert(sig, kind, "mintX", "MEME", tradeId, payload, createdAt, createdAt)
        return db.txJournalQueries.getBySignature(sig).executeAsOne()
    }

    /** REAL-сделка: 1e6 raw-единиц за $50 по цене 0.001. */
    private fun realTrade(db: DrxDatabase, id: String = "t1", status: String = "OPEN") {
        db.tradeQueries.insert(
            id = id, strategy_id = "s1", strategy_name = "S1", mint = "mintX", symbol = "MEME", pair_address = "pair",
            entry_price = 0.001, exit_price = null, qty = 1_000_000.0, qty_remaining = 1_000_000.0, size_usd = 50.0,
            size_sol = 0.5, stop_loss = 0.0009, take_profit = 0.0013, peak_price = 0.001, tp1_done = 0, tp2_done = 0,
            entry_liquidity_usd = 0.0, pnl = null, pnl_percent = null, fee = null, status = status, open_reason = "t",
            close_reason = null, is_demo = 0, opened_at = 0L, closed_at = null,
        )
    }

    private fun sell(full: Boolean, qty: Double, proceeds: Double, tp1: Boolean = false) = json.encodeToString(
        SellIntent(
            tradeId = "t1", requestedQty = qty, fullRequested = full, reason = if (full) "Stop Loss" else "TP1",
            tp1Done = tp1, tp2Done = false, proceedsUsd = proceeds, soldAll = full, currentPrice = 0.0008,
        )
    )

    @Test fun signatureIsReadFromSignedTransaction() {
        val sig = ByteArray(64) { (it + 1).toByte() }
        val signed = byteArrayOf(1) + sig + byteArrayOf(9, 9, 9)
        assertEquals(encodeBase58(sig), transactionSignatureBase58(Base64.getEncoder().encodeToString(signed)))

        val unsigned = byteArrayOf(1) + ByteArray(64) + byteArrayOf(9)
        assertNull(transactionSignatureBase58(Base64.getEncoder().encodeToString(unsigned)), "пустая подпись — не подписана")
        assertNull(transactionSignatureBase58("не base64!"))
    }

    @Test fun lateBuyIsRecordedOnce() = runBlocking {
        val e = env()
        val row = journal(e.db, "sig-buy", "BUY", json.encodeToString(buyIntent()))
        assertEquals(1L, e.db.txJournalQueries.hasPendingBuy("mintX").executeAsOne(), "пока покупка «в полёте» — монету не покупаем")

        assertNotNull(e.executor.recordRecoveredBuy(row, receivedRaw = 5_500_000L))
        val t = e.db.tradeQueries.getAll().executeAsList().single()
        assertEquals("OPEN", t.status)
        assertEquals(0L, t.is_demo)
        assertEquals("REAL", t.exec_mode)
        assertEquals(5_500_000.0, t.qty) // фактический приход, а не котировка
        assertEquals(50.0, t.size_usd, 1e-9)
        assertEquals(1_000L, t.opened_at) // время покупки — из журнала
        assertEquals(0.0009, t.initial_stop)
        val j = e.db.txJournalQueries.getBySignature("sig-buy").executeAsOne()
        assertEquals("CONFIRMED", j.status)
        assertEquals(t.id, j.trade_id)
        assertEquals(0L, e.db.txJournalQueries.hasPendingBuy("mintX").executeAsOne())

        // Повтор (обычный путь и сверка одновременно) второй сделки не пишет.
        assertNull(e.executor.recordRecoveredBuy(row, receivedRaw = 5_500_000L))
        assertEquals(1, e.db.tradeQueries.getAll().executeAsList().size)
    }

    @Test fun lateBuyWithoutTokensWritesNoTrade() = runBlocking {
        val e = env()
        val row = journal(e.db, "sig-buy", "BUY", json.encodeToString(buyIntent()))
        assertTrue("токенов на кошельке нет" in assertNotNull(e.executor.recordRecoveredBuy(row, receivedRaw = 0L)))
        assertTrue(e.db.tradeQueries.getAll().executeAsList().isEmpty())
        assertEquals("CONFIRMED", e.db.txJournalQueries.getBySignature("sig-buy").executeAsOne().status)
    }

    @Test fun lateSaleClosesTradeOnce() = runBlocking {
        val e = env()
        realTrade(e.db)
        val row = journal(e.db, "sig-sell", "SELL", sell(full = true, qty = 1_000_000.0, proceeds = 40.0), tradeId = "t1")
        assertNotNull(e.executor.applyLateSale(row))
        val t = e.db.tradeQueries.getById("t1").executeAsOne()
        assertEquals("CLOSED", t.status)
        assertEquals(-10.0, t.pnl!!, 1e-9)
        assertEquals("Stop Loss", t.close_reason)
        assertEquals(0.0008, t.exit_price!!, 1e-12) // 0.001 × 40/50 — в единицах цены входа
        assertEquals("CONFIRMED", e.db.txJournalQueries.getBySignature("sig-sell").executeAsOne().status)

        assertNull(e.executor.applyLateSale(row), "вторая сверка ту же продажу не учитывает")
    }

    @Test fun latePartialSaleKeepsPositionOpen() = runBlocking {
        val e = env()
        realTrade(e.db)
        val row = journal(e.db, "sig-sell", "SELL", sell(full = false, qty = 500_000.0, proceeds = 30.0, tp1 = true), tradeId = "t1")
        assertNotNull(e.executor.applyLateSale(row))
        val t = e.db.tradeQueries.getById("t1").executeAsOne()
        assertEquals("OPEN", t.status)
        assertEquals(500_000.0, t.qty_remaining)
        assertEquals(1L, t.tp1_done)
        assertEquals(5.0, t.pnl!!, 1e-9) // $30 за половину, стоившую $25
    }

    @Test fun lateSaleOfClosedTradeIsOnlySettled() = runBlocking {
        val e = env()
        realTrade(e.db, status = "CLOSED")
        val row = journal(e.db, "sig-sell", "SELL", sell(full = true, qty = 1_000_000.0, proceeds = 40.0), tradeId = "t1")
        assertNull(e.executor.applyLateSale(row))
        val j = e.db.txJournalQueries.getBySignature("sig-sell").executeAsOne()
        assertEquals("CONFIRMED", j.status)
        assertEquals("сделка уже закрыта", j.note)
    }

    @Test fun pendingSellBlocksSecondSale() = runBlocking {
        val e = env()
        realTrade(e.db)
        journal(e.db, "sig-sell", "SELL", sell(full = true, qty = 1_000_000.0, proceeds = 40.0), tradeId = "t1")
        assertTrue(e.executor.hasPendingSell("t1"))
        val t = e.db.tradeQueries.getById("t1").executeAsOne()
        assertNull(e.executor.closeTrade(t, 0.0008, 100.0, "Stop Loss"))
        val after = e.db.tradeQueries.getById("t1").executeAsOne()
        assertEquals("OPEN", after.status)
        assertEquals(0L, after.exit_failures, "ожидание сверки — не неудача продажи")
    }

    @Test fun reconcileSkipsFreshRecords() = runBlocking {
        val e = env()
        journal(e.db, "sig-fresh", "BUY", json.encodeToString(buyIntent()), createdAt = System.currentTimeMillis())
        // Свежую запись ещё ведёт обычный путь — сверка её не трогает (и в сеть не ходит).
        assertTrue(e.executor.reconcileJournal().isEmpty())
        assertEquals("PENDING", e.db.txJournalQueries.getBySignature("sig-fresh").executeAsOne().status)
    }

    @Test fun corruptedRecordIsClosed() = runBlocking {
        val e = env()
        val row = journal(e.db, "sig-bad", "BUY", "{")
        assertNull(e.executor.recordRecoveredBuy(row, receivedRaw = 1L))
        assertEquals("FAILED", e.db.txJournalQueries.getBySignature("sig-bad").executeAsOne().status)
    }
}
