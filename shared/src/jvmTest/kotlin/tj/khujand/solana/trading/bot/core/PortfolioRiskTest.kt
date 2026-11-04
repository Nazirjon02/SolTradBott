package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.risk.drawdownFromPeakUsd
import tj.khujand.solana.trading.bot.core.risk.lossStreakPauseUntil
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache

/**
 * Лимиты портфеля: все стратегии текущего режима вместе — позиции, сумма в позициях, убыток за день
 * и неделю (с открытыми позициями по последней цене), пауза после серии убытков, брони под входы.
 */
class PortfolioRiskTest {

    private var now = System.currentTimeMillis()

    private fun db(): DrxDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        return DrxDatabase(driver)
    }

    private fun rm(
        db: DrxDatabase,
        s: ProtectionSettings = ProtectionSettings(),
        demo: Boolean = true,
        freeUsd: Double = 1_000.0,
    ): RiskManager {
        val cache = AccountCache(db)
        if (demo) cache.set(AccountCache.COIN_DEMO, freeUsd, freeUsd) else cache.set(AccountCache.COIN_SOL, freeUsd / 100, freeUsd)
        return RiskManager(db, cache, isDemo = { demo }, protection = { s }, nowMs = { now })
    }

    /** Открытая сделка по цене 1.0; [lastPrice] — последняя цена монитора (0 — ещё не было). */
    private fun open(db: DrxDatabase, id: String, strategy: String = "s1", demo: Boolean = true, sizeUsd: Double = 100.0, lastPrice: Double = 0.0) {
        db.tradeQueries.insert(
            id = id, strategy_id = strategy, strategy_name = strategy, mint = "m-$id", symbol = "T", pair_address = "p",
            entry_price = 1.0, exit_price = null, qty = sizeUsd, qty_remaining = sizeUsd, size_usd = sizeUsd, size_sol = 0.0,
            stop_loss = 0.9, take_profit = 1.3, peak_price = 1.0, tp1_done = 0, tp2_done = 0, entry_liquidity_usd = 0.0,
            pnl = null, pnl_percent = null, fee = null, status = "OPEN", open_reason = "t", close_reason = null,
            is_demo = if (demo) 1 else 0, opened_at = now, closed_at = null,
        )
        if (lastPrice > 0) db.tradeQueries.updateLastPrice(lastPrice, id)
    }

    private fun closed(db: DrxDatabase, id: String, pnl: Double?, closedAt: Long, demo: Boolean = true) {
        db.tradeQueries.insert(
            id = id, strategy_id = "s1", strategy_name = "s1", mint = "m-$id", symbol = "T", pair_address = "p",
            entry_price = 1.0, exit_price = 1.0, qty = 100.0, qty_remaining = 0.0, size_usd = 100.0, size_sol = 0.0,
            stop_loss = 0.9, take_profit = 1.3, peak_price = 1.0, tp1_done = 0, tp2_done = 0, entry_liquidity_usd = 0.0,
            pnl = pnl, pnl_percent = pnl, fee = null, status = "CLOSED", open_reason = "t", close_reason = "t",
            is_demo = if (demo) 1 else 0, opened_at = closedAt, closed_at = closedAt,
        )
    }

    @Test fun openPositionsOfAllStrategiesShareOneLimit() {
        val db = db()
        open(db, "a", strategy = "s1")
        open(db, "b", strategy = "s2")
        val risk = rm(db)
        assertNull(risk.portfolioBlockReason(), "2 из 3 — можно")
        open(db, "c", strategy = "s3")
        assertTrue("3 из 3" in assertNotNull(risk.portfolioBlockReason()))
    }

    @Test fun otherModePositionsDoNotCount() {
        val db = db()
        listOf("a", "b", "c").forEach { open(db, it, demo = false) }
        assertNull(rm(db, demo = true).portfolioBlockReason(), "REAL-позиции не занимают DEMO-слоты")
        assertNotNull(rm(db, demo = false).portfolioBlockReason())
    }

    @Test fun exposureLimitCountsNewEntry() {
        val db = db()
        open(db, "a", sizeUsd = 300.0) // капитал = 1000 свободных + 300 в позиции = 1300, лимит 50% = 650
        val risk = rm(db)
        assertNull(risk.portfolioBlockReason(), "без размера сумма в позициях не проверяется")
        assertNull(risk.portfolioBlockReason(newSizeUsd = 300.0)) // 600 ≤ 650
        assertTrue("в позициях будет" in assertNotNull(risk.portfolioBlockReason(newSizeUsd = 400.0))) // 700 > 650
    }

    @Test fun dailyLossIncludesOpenPositionsAtLastPrice() {
        val db = db()
        // Позиция $100 упала вдвое: −$50; капитал = 400 + 50 = 450, лимит 10% = $45.
        open(db, "a", sizeUsd = 100.0, lastPrice = 0.5)
        assertTrue("за день" in assertNotNull(rm(db, freeUsd = 400.0).portfolioBlockReason()))

        val db2 = db()
        open(db2, "a", sizeUsd = 100.0, lastPrice = 0.6) // −$40 при лимите $46
        assertNull(rm(db2, freeUsd = 400.0).portfolioBlockReason())
    }

    @Test fun weeklyLossWindowIsSevenDays() {
        val db = db()
        closed(db, "old", pnl = -250.0, closedAt = now - 3 * 24 * 60 * 60_000L)
        // За день убытка нет, за 7 дней −250 при лимите 20% от 1000.
        assertTrue("7 дней" in assertNotNull(rm(db).portfolioBlockReason()))

        val db2 = db()
        closed(db2, "older", pnl = -250.0, closedAt = now - 8 * 24 * 60 * 60_000L)
        assertNull(rm(db2).portfolioBlockReason(), "убыток старше недели не считается")
    }

    @Test fun lossStreakPausesEntriesUntilPauseEnds() {
        val db = db()
        closed(db, "l1", pnl = -5.0, closedAt = now - 20 * 60_000L)
        closed(db, "l2", pnl = -5.0, closedAt = now - 10 * 60_000L)
        closed(db, "l3", pnl = -5.0, closedAt = now - 5 * 60_000L)
        val risk = rm(db)
        assertTrue("пауза до" in assertNotNull(risk.portfolioBlockReason()))
        now += 26 * 60_000L // пауза 30 мин от последнего убытка истекла
        assertNull(risk.portfolioBlockReason())
    }

    @Test fun winBreaksLossStreak() {
        val db = db()
        closed(db, "l1", pnl = -5.0, closedAt = now - 20 * 60_000L)
        closed(db, "l2", pnl = -5.0, closedAt = now - 10 * 60_000L)
        closed(db, "w", pnl = 3.0, closedAt = now - 5 * 60_000L)
        assertNull(rm(db).portfolioBlockReason())
    }

    @Test fun lossStreakRules() {
        val t = 1_000_000L
        val losses = listOf(-1.0 to t, -2.0 to t - 1, -3.0 to t - 2)
        assertEquals(t + 30 * 60_000L, lossStreakPauseUntil(losses, 3, 30))
        assertNull(lossStreakPauseUntil(losses.take(2), 3, 30), "убытков меньше лимита")
        assertNull(lossStreakPauseUntil(listOf(-1.0 to t, null to t - 1, -3.0 to t - 2), 3, 30), "сделка без PnL прерывает серию")
        assertNull(lossStreakPauseUntil(losses, 0, 30), "0 — выключено")
    }

    @Test fun reservationHoldsSlotUntilReleased() = runBlocking {
        val db = db()
        open(db, "a")
        val risk = rm(db, ProtectionSettings(maxOpenPositions = 2))
        val first = assertIs<RiskManager.EntrySlot.Granted>(risk.reserveEntry(50.0))
        assertIs<RiskManager.EntrySlot.Denied>(risk.reserveEntry(50.0), "открыта 1 + бронь 1 = лимит")
        risk.releaseEntry(first)
        assertIs<RiskManager.EntrySlot.Granted>(risk.reserveEntry(50.0))
        Unit
    }

    @Test fun concurrentReservationsNeverExceedLimit() = runBlocking {
        val risk = rm(db(), ProtectionSettings(maxOpenPositions = 3, maxExposurePct = 0.0))
        val slots = (1..20).map { async(Dispatchers.Default) { risk.reserveEntry(10.0) } }.awaitAll()
        assertEquals(3, slots.count { it is RiskManager.EntrySlot.Granted })
    }

    @Test fun pendingRealBuyTakesSlot() {
        val db = db()
        db.txJournalQueries.insert("sig", "BUY", "mint", "T", null, "{}", now, now)
        val risk = rm(db, ProtectionSettings(maxOpenPositions = 1), demo = false)
        assertNotNull(risk.portfolioBlockReason(), "покупка «в полёте» уже заняла слот")
    }

    @Test fun drawdownIsMeasuredFromPeakOfTradeCurve() {
        // Кривая: +100 (пик 100) → −30 → −40 = 30; открытая позиция −20 → сейчас 10, просадка 90.
        assertEquals(90.0, drawdownFromPeakUsd(listOf(100.0, -30.0, -40.0), openPnlUsd = -20.0), 1e-9)
        assertEquals(0.0, drawdownFromPeakUsd(listOf(10.0, 20.0), openPnlUsd = 5.0), 1e-9, "на новом максимуме просадки нет")
        assertEquals(15.0, drawdownFromPeakUsd(listOf(-5.0, -10.0), openPnlUsd = 0.0), 1e-9, "пик — исходный ноль")
    }

    @Test fun drawdownLimitBlocksEntriesWhenEnabled() {
        val db = db()
        closed(db, "w", pnl = 300.0, closedAt = now - 20 * 24 * 60 * 60_000L)
        closed(db, "l", pnl = -250.0, closedAt = now - 10 * 24 * 60 * 60_000L)
        // Капитал сейчас 1000, отдано с пика 250 → просадка 250 / 1250 = 20%. Недельный убыток не задет: оба
        // закрытия старше 7 дней.
        assertNull(rm(db).portfolioBlockReason(), "по умолчанию лимит просадки выключен")
        assertTrue("просадка портфеля" in assertNotNull(rm(db, ProtectionSettings(maxDrawdownPct = 20.0)).portfolioBlockReason()))
        assertNull(rm(db, ProtectionSettings(maxDrawdownPct = 25.0)).portfolioBlockReason())
    }

    @Test fun hardStopOnlyForLossLimitsAndOnlyWhenEnabled() {
        val db = db()
        closed(db, "l", pnl = -150.0, closedAt = now - 60_000L) // −150 за день при лимите 10% от 1000
        assertNull(rm(db).hardStopReason(), "без тумблера лимит лишь ставит входы на паузу")
        assertTrue("за день" in assertNotNull(rm(db, ProtectionSettings(autoStopOnLimit = true)).hardStopReason()))

        val busy = db()
        listOf("a", "b", "c").forEach { open(busy, it) }
        assertNotNull(rm(busy, ProtectionSettings(autoStopOnLimit = true)).portfolioBlockReason())
        assertNull(rm(busy, ProtectionSettings(autoStopOnLimit = true)).hardStopReason(), "лимит позиций — не повод останавливать бота")
    }

    @Test fun disabledPortfolioAllowsEverything() = runBlocking {
        val db = db()
        (1..10).forEach { open(db, "t$it", lastPrice = 0.1) }
        val risk = rm(db, ProtectionSettings(portfolioEnabled = false), freeUsd = 10.0)
        assertNull(risk.portfolioBlockReason(newSizeUsd = 1_000.0))
        assertIs<RiskManager.EntrySlot.Granted>(risk.reserveEntry(1_000.0))
        Unit
    }
}
