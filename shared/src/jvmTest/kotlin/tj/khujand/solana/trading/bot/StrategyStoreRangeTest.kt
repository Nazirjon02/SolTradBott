package tj.khujand.solana.trading.bot

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.core.strategy.toStrategyConfig
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционная проверка «сохраняется»: реальный драйвер + схема + миграция + StrategyStore + маппер.
 * Точная копия прод-пути createDatabaseDriver (Schema.create → migrateStrategyTable).
 */
class StrategyStoreRangeTest {

    private fun freshDb(): DrxDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        return DrxDatabase(driver)
    }

    @Test fun rangeParamsSurviveSaveAndReload() {
        val store = StrategyStore(freshDb())
        store.save(
            StrategyConfig(
                id = "s1",
                name = "with-range",
                type = StrategyType.DARS.name,
                rangeFilterEnabled = true,
                rangeMaxEntryPct = 0.6,
                rangeLookbackBars = 50,
            )
        )
        val reloaded = store.loadAll().first { it.id == "s1" }
        assertTrue(reloaded.rangeFilterEnabled)
        assertEquals(0.6, reloaded.rangeMaxEntryPct)
        assertEquals(50, reloaded.rangeLookbackBars)
    }

    @Test fun defaultsWhenNotSet() {
        val store = StrategyStore(freshDb())
        store.save(StrategyConfig(id = "s2", name = "default"))
        val reloaded = store.loadAll().first { it.id == "s2" }
        assertEquals(false, reloaded.rangeFilterEnabled)
        assertEquals(0.8, reloaded.rangeMaxEntryPct)
        assertEquals(100, reloaded.rangeLookbackBars)
        assertEquals(50, reloaded.scoreThreshold)
        assertEquals(true, reloaded.scanFiltersEnabled)
        assertEquals(true, reloaded.maxDailyLossEnabled)
        assertEquals(true, reloaded.maxDrawdownEnabled)
    }

    @Test fun riskTogglesSurviveSaveAndReload() {
        val store = StrategyStore(freshDb())
        store.save(
            StrategyConfig(
                id = "s4",
                name = "no-risk",
                type = StrategyType.DARS.name,
                maxDailyLossEnabled = false,
                maxDrawdownEnabled = false,
            )
        )
        val reloaded = store.loadAll().first { it.id == "s4" }
        assertEquals(false, reloaded.maxDailyLossEnabled)
        assertEquals(false, reloaded.maxDrawdownEnabled)
    }

    @Test fun scoreParamsSurviveSaveAndReload() {
        val store = StrategyStore(freshDb())
        store.save(
            StrategyConfig(
                id = "s3",
                name = "by-score",
                type = StrategyType.SCORE.name,
                scoreThreshold = 70,
                scanFiltersEnabled = false,
            )
        )
        val reloaded = store.loadAll().first { it.id == "s3" }
        assertEquals(70, reloaded.scoreThreshold)
        assertEquals(false, reloaded.scanFiltersEnabled)
    }

    @Test fun exitSizingAndBreakoutParamsSurviveSaveAndReload() {
        val store = StrategyStore(freshDb())
        store.save(breakoutPullbackDefaults().copy(tp1R = 2.0, atrTrailMult = 2.5, bpBreakoutLookback = 30, bpHigherTf = "1h"))
        val reloaded = store.loadAll().first { it.id == BREAKOUT_PULLBACK_ID }
        assertEquals(StrategyType.BREAKOUT_PULLBACK.name, reloaded.type)
        assertEquals("5m", reloaded.timeframe)
        assertEquals(true, reloaded.rLadderEnabled)
        assertEquals(2.0, reloaded.tp1R)
        assertEquals(true, reloaded.breakEvenAfterTp1)
        assertEquals(true, reloaded.runnerEnabled)
        assertEquals(true, reloaded.atrTrailEnabled)
        assertEquals(2.5, reloaded.atrTrailMult)
        assertEquals(true, reloaded.riskSizingEnabled)
        assertEquals(1.0, reloaded.riskPerTradePercent)
        assertEquals(30, reloaded.bpBreakoutLookback)
        assertEquals("1h", reloaded.bpHigherTf)
        assertEquals(0.7, reloaded.bpPullbackVolumeRatio)
    }

    @Test fun newTogglesOffByDefault() {
        // Старые стратегии не должны менять поведение: все новые выходы и размер от риска выключены.
        val store = StrategyStore(freshDb())
        store.save(StrategyConfig(id = "plain", name = "plain"))
        val reloaded = store.loadAll().first { it.id == "plain" }
        assertEquals(false, reloaded.rLadderEnabled)
        assertEquals(false, reloaded.breakEvenAfterTp1)
        assertEquals(false, reloaded.runnerEnabled)
        assertEquals(false, reloaded.atrTrailEnabled)
        assertEquals(false, reloaded.riskSizingEnabled)
    }

    @Test fun breakoutPullbackSeededOnceIntoExistingDb() {
        val db = freshDb()
        val store = StrategyStore(db)
        store.save(StrategyConfig(id = "user", name = "user strategy"))

        seedDefaultStrategies(db)
        assertTrue(store.loadAll().any { it.id == BREAKOUT_PULLBACK_ID }, "досеяна в существующую БД")
        assertTrue(store.loadAll().none { it.id == "dars-memecoins" }, "базовые в непустую БД не сеются")

        // Пользователь удалил — повторный запуск не возвращает.
        store.delete(BREAKOUT_PULLBACK_ID)
        seedDefaultStrategies(db)
        assertTrue(store.loadAll().none { it.id == BREAKOUT_PULLBACK_ID })
    }

    @Test fun freshDbGetsAllFourStrategies() {
        val db = freshDb()
        seedDefaultStrategies(db)
        val ids = StrategyStore(db).loadAll().map { it.id }.toSet()
        assertEquals(setOf("dars-memecoins", "momentum-scalp", "rsi-ema-memecoins", BREAKOUT_PULLBACK_ID), ids)
    }

    /** Старая БД без новых колонок strategy/trade: миграция добавляет их, SELECT * не падает. */
    @Test fun migrationAddsExitAndTradeColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        val dropped = listOf(
            "r_ladder_enabled", "tp1_r", "tp2_r", "break_even_after_tp1", "runner_enabled",
            "atr_trail_enabled", "atr_trail_mult", "atr_trail_activation_r",
            "risk_sizing_enabled", "risk_per_trade_percent", "max_pool_share_percent",
            "bp_higher_tf", "bp_require_htf_trend", "bp_breakout_lookback", "bp_breakout_volume_mult",
            "bp_max_bars_since_breakout", "bp_pullback_volume_ratio", "bp_trigger_volume_mult",
        )
        dropped.forEach { driver.execute(null, "ALTER TABLE strategy DROP COLUMN $it", 0) }
        // Таблица trade в старом формате (без initial_stop / entry_atr_frac). DROP COLUMN для
        // последней колонки SQLite не переваривает из-за комментария в CREATE — пересоздаём таблицу.
        driver.execute(null, "DROP TABLE trade", 0)
        driver.execute(
            null,
            """
            CREATE TABLE trade (
                id TEXT NOT NULL PRIMARY KEY, strategy_id TEXT NOT NULL, strategy_name TEXT NOT NULL,
                mint TEXT NOT NULL, symbol TEXT NOT NULL, pair_address TEXT NOT NULL DEFAULT '',
                entry_price REAL NOT NULL, exit_price REAL, qty REAL NOT NULL, qty_remaining REAL NOT NULL,
                size_usd REAL NOT NULL, size_sol REAL NOT NULL DEFAULT 0, stop_loss REAL NOT NULL,
                take_profit REAL NOT NULL, peak_price REAL NOT NULL, tp1_done INTEGER NOT NULL DEFAULT 0,
                tp2_done INTEGER NOT NULL DEFAULT 0, entry_liquidity_usd REAL NOT NULL DEFAULT 0,
                pnl REAL, pnl_percent REAL, fee REAL, status TEXT NOT NULL DEFAULT 'OPEN',
                open_reason TEXT NOT NULL, close_reason TEXT, is_demo INTEGER NOT NULL DEFAULT 1,
                opened_at INTEGER NOT NULL, closed_at INTEGER
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO strategy (id, name, type, created_at, updated_at) VALUES ('old', 'legacy', 'DARS', 0, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO trade (id, strategy_id, strategy_name, mint, symbol, entry_price, qty, qty_remaining, " +
                "size_usd, stop_loss, take_profit, peak_price, open_reason, opened_at) " +
                "VALUES ('t1', 'old', 'legacy', 'm', 'S', 1.0, 10.0, 10.0, 10.0, 0.9, 1.3, 1.0, 'x', 0)",
            0,
        )

        migrateStrategyTable(driver)

        val db = DrxDatabase(driver)
        val cfg = db.strategyQueries.getAll().executeAsList().single().toStrategyConfig()
        assertEquals(false, cfg.rLadderEnabled)
        assertEquals(1.5, cfg.tp1R)
        assertEquals("15m", cfg.bpHigherTf)
        val trade = db.tradeQueries.getOpenTrades().executeAsList().single()
        assertEquals(0.0, trade.initial_stop)   // нет данных → монитор возьмёт R из SL% конфига
        assertEquals(0.0, trade.entry_atr_frac)
    }

    /**
     * Симуляция «старой» БД без range-колонок (главная причина, ради которой добавлена миграция):
     * migrateStrategyTable должен добавить колонки, а getAll (SELECT *) — не упасть и корректно
     * отдать дефолты для старой строки.
     */
    @Test fun migrationRescuesOldDb() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        // «Откатываем» схему до состояния без поздних колонок (range + вход по баллу).
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN range_filter_enabled", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN range_max_entry_pct", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN range_lookback_bars", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN score_threshold", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN scan_filters_enabled", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN max_daily_loss_enabled", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN max_drawdown_enabled", 0)
        // Строка в старом формате (прочие колонки берут DEFAULT из схемы).
        driver.execute(
            null,
            "INSERT INTO strategy (id, name, type, created_at, updated_at) VALUES ('old', 'legacy', 'DARS', 0, 0)",
            0,
        )

        migrateStrategyTable(driver) // прод-миграция

        val db = DrxDatabase(driver)
        val all = db.strategyQueries.getAll().executeAsList()
        assertEquals(1, all.size)
        val cfg = all.first().toStrategyConfig()
        assertEquals("old", cfg.id)
        assertEquals("legacy", cfg.name)
        assertEquals(false, cfg.rangeFilterEnabled)
        assertEquals(0.8, cfg.rangeMaxEntryPct)
        assertEquals(100, cfg.rangeLookbackBars)
        assertEquals(50, cfg.scoreThreshold)
        assertEquals(true, cfg.scanFiltersEnabled)
        assertEquals(true, cfg.maxDailyLossEnabled)
        assertEquals(true, cfg.maxDrawdownEnabled)

        // Идемпотентность: повторный вызов не ломает БД.
        migrateStrategyTable(driver)
        assertEquals(1, db.strategyQueries.getAll().executeAsList().size)
    }
}
