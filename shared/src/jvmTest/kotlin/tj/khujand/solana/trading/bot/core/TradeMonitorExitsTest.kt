package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.TradeMonitor
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.migrateStrategyTable

/**
 * Выходы в R на DEMO-счёте: реальные TradeExecutor + TradeMonitor + БД, цена подаётся напрямую
 * в [TradeMonitor.applyExits] (без сети). Вход 1.0, начальный стоп 0.9 → R = 0.1.
 */
class TradeMonitorExitsTest {

    private class Env(val db: DrxDatabase, val executor: TradeExecutor, val monitor: TradeMonitor)

    private fun env(): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db)
        settings.setDemoMode(true)
        val executor = TradeExecutor(DexClient(), db, settings, AccountCache(db), ActivityLog())
        val monitor = TradeMonitor(DexClient(), db, executor, NoopNotifier, ActivityLog())
        return Env(db, executor, monitor)
    }

    private val rConfig = StrategyConfig(
        id = "s1",
        name = "R",
        takeProfitPercent = 10.0,   // основной TP на +1R — ниже TP1 лестницы (+1.5R)
        tp1ClosePercent = 40.0,
        tp2ClosePercent = 30.0,
        breakEvenOffsetPercent = 2.0,
        rLadderEnabled = true,
        tp1R = 1.5,
        tp2R = 3.0,
        breakEvenAfterTp1 = true,
        runnerEnabled = true,
        atrTrailEnabled = true,
        atrTrailMult = 3.0,
        atrTrailActivationR = 1.0,
    )

    private fun open(e: Env, config: StrategyConfig, atrFrac: Double = 0.05): String = runBlocking {
        val signal = Signal(
            mint = "m", symbol = "MEME", pairAddress = "p", confidence = 0.8, reason = "test",
            entryPrice = 1.0, stopLoss = 0.9, takeProfit = 1.0 * (1 + config.takeProfitPercent / 100),
        )
        e.executor.openTrade(signal, config, sizeUsd = 1_000.0, entryLiquidityUsd = 0.0, entryAtrFrac = atrFrac)!!
    }

    private fun trade(e: Env, id: String): TradeRow = e.db.tradeQueries.getById(id).executeAsOne()

    @Test
    fun openStoresRiskBasis() {
        val e = env()
        val t = trade(e, open(e, rConfig, atrFrac = 0.05))
        assertEquals(0.9, t.initial_stop, 1e-9)
        assertEquals(0.05, t.entry_atr_frac, 1e-9)
    }

    @Test
    fun runnerIgnoresMainTakeProfit() = runBlocking {
        val e = env()
        val id = open(e, rConfig)
        // +12% = 1.2R: выше основного TP (+10%), но раннер не закрывает позицию, а TP1 (+1.5R) ещё не достигнут.
        e.monitor.applyExits(trade(e, id), rConfig, price = 1.12, liquidity = null)
        val t = trade(e, id)
        assertEquals("OPEN", t.status)
        assertEquals(t.qty, t.qty_remaining, 1e-9)
    }

    @Test
    fun withoutRunnerMainTakeProfitClosesAll() = runBlocking {
        val e = env()
        val cfg = rConfig.copy(runnerEnabled = false)
        val id = open(e, cfg)
        e.monitor.applyExits(trade(e, id), cfg, price = 1.12, liquidity = null)
        assertEquals("CLOSED", trade(e, id).status)
    }

    @Test
    fun tp1ClosesPortionAndMovesStopToBreakEven() = runBlocking {
        val e = env()
        val id = open(e, rConfig)
        e.monitor.applyExits(trade(e, id), rConfig, price = 1.16, liquidity = null) // 1.6R ≥ TP1 1.5R
        val t = trade(e, id)
        assertEquals("OPEN", t.status)
        assertEquals(1L, t.tp1_done)
        assertEquals(t.qty * 0.6, t.qty_remaining, t.qty * 1e-6)   // закрыто 40%
        assertEquals(1.02, t.stop_loss, 1e-9)                         // безубыток + 2%
        assertEquals(0.9, t.initial_stop, 1e-9)                       // R не потерян
    }

    @Test
    fun ladderTakesTp2ThenRunnerExitsOnAtrTrail() = runBlocking {
        val e = env()
        val id = open(e, rConfig, atrFrac = 0.05)
        e.monitor.applyExits(trade(e, id), rConfig, price = 1.16, liquidity = null) // TP1
        e.monitor.applyExits(trade(e, id), rConfig, price = 1.31, liquidity = null) // 3.1R → TP2
        var t = trade(e, id)
        assertEquals(1L, t.tp2_done)
        assertEquals(t.qty * 0.3, t.qty_remaining, t.qty * 1e-6)   // остался раннер 30%

        // Рост до 1.60: стоп тянется за пиком на 3 × 5% = 15% → 1.36.
        e.monitor.applyExits(trade(e, id), rConfig, price = 1.60, liquidity = null)
        t = trade(e, id)
        assertEquals("OPEN", t.status)
        assertEquals(1.36, t.stop_loss, 1e-9)

        // Откат ниже трейлинга — раннер закрыт с прибылью.
        e.monitor.applyExits(trade(e, id), rConfig, price = 1.35, liquidity = null)
        t = trade(e, id)
        assertEquals("CLOSED", t.status)
        assertTrue((t.pnl ?: 0.0) > 0.0, "суммарный PnL ${t.pnl}")
    }

    @Test
    fun timeStopSparesTradeAfterTp1() = runBlocking {
        val e = env()
        val cfg = rConfig.copy(timeStopMinutes = 1)
        val id = open(e, cfg)
        e.monitor.applyExits(trade(e, id), cfg, price = 1.16, liquidity = null) // TP1 взят
        // «Состариваем» сделку: тайм-стоп истёк бы, но TP1 уже взят — сделка пошла.
        val aged = trade(e, id).copy(opened_at = 0L)
        e.monitor.applyExits(aged, cfg, price = 1.10, liquidity = null)
        assertEquals("OPEN", trade(e, id).status)
    }

    @Test
    fun timeStopClosesTradeThatDidNotGo() = runBlocking {
        val e = env()
        val cfg = rConfig.copy(timeStopMinutes = 1)
        val id = open(e, cfg)
        val aged = trade(e, id).copy(opened_at = 0L)
        e.monitor.applyExits(aged, cfg, price = 1.03, liquidity = null)
        assertEquals("CLOSED", trade(e, id).status)
    }

    @Test
    fun legacyExitsUnchangedWhenTogglesOff() = runBlocking {
        // Без новых тумблеров: % лестница работает как раньше, основной TP закрывает всё.
        val e = env()
        val cfg = StrategyConfig(id = "s1", takeProfitPercent = 30.0, partialTpEnabled = true, tp1Percent = 15.0, tp1ClosePercent = 50.0)
        val id = open(e, cfg)
        e.monitor.applyExits(trade(e, id), cfg, price = 1.16, liquidity = null)
        assertEquals(1L, trade(e, id).tp1_done)
        e.monitor.applyExits(trade(e, id), cfg, price = 1.31, liquidity = null)
        assertEquals("CLOSED", trade(e, id).status)
    }
}
