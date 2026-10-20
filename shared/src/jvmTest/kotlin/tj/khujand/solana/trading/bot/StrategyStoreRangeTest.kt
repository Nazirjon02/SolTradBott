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

    @Test fun freshDbGetsDefaultStrategies() {
        val db = freshDb()
        seedDefaultStrategies(db)
        val ids = StrategyStore(db).loadAll().map { it.id }.toSet()
        assertEquals(setOf("dars-memecoins", BREAKOUT_PULLBACK_ID, MEME_PLAYBOOK_ID, ELLIOTT_ID), ids)
    }

    @Test fun memePlaybookSeededOnceIntoExistingDb() {
        val db = freshDb()
        val store = StrategyStore(db)
        store.save(StrategyConfig(id = "user", name = "user strategy"))

        seedDefaultStrategies(db)
        val seeded = store.loadAll().first { it.id == MEME_PLAYBOOK_ID }
        assertEquals(false, seeded.isActive, "включается вручную")

        store.delete(MEME_PLAYBOOK_ID)
        seedDefaultStrategies(db)
        assertTrue(store.loadAll().none { it.id == MEME_PLAYBOOK_ID }, "удалённую не возвращаем")
    }

    @Test fun memePlaybookParamsSurviveSaveAndReload() {
        val store = StrategyStore(freshDb())
        store.save(memePlaybookDefaults().copy(mpFibMax = 0.75, mpLookbackBars = 96, trailTier2Pct = 25.0, staleExitMinutes = 720))
        val r = store.loadAll().first { it.id == MEME_PLAYBOOK_ID }
        assertEquals(StrategyType.MEME_PLAYBOOK.name, r.type)
        assertEquals(true, r.partialTpEnabled)
        assertEquals(100.0, r.tp1Percent)
        assertEquals(50.0, r.tp1ClosePercent)
        assertEquals(true, r.breakEvenAfterTp1)
        assertEquals(true, r.runnerEnabled)
        assertEquals(true, r.tieredTrailEnabled)
        assertEquals(40.0, r.trailTier1Pct)
        assertEquals(25.0, r.trailTier2Pct)
        assertEquals(true, r.staleExitEnabled)
        assertEquals(720, r.staleExitMinutes)
        assertEquals(96, r.mpLookbackBars)
        assertEquals(0.382, r.mpFibMin)
        assertEquals(0.75, r.mpFibMax)
        assertEquals(3.0, r.mpMinLiqToMcPct)
        assertEquals(true, r.mpRequireRevokedAuthorities)
        assertEquals(true, r.mpRejectCopycats)
        assertEquals(20.0, r.liquidityExitDropPercent)
    }

    @Test fun elliottSeededOnceIntoExistingDb() {
        val db = freshDb()
        val store = StrategyStore(db)
        store.save(StrategyConfig(id = "user", name = "user strategy"))

        seedDefaultStrategies(db)
        val seeded = store.loadAll().first { it.id == ELLIOTT_ID }
        assertEquals(false, seeded.isActive, "включается вручную")
        assertEquals(StrategyType.ELLIOTT.name, seeded.type)

        store.delete(ELLIOTT_ID)
        seedDefaultStrategies(db)
        assertTrue(store.loadAll().none { it.id == ELLIOTT_ID }, "удалённую не возвращаем")
    }

    @Test fun elliottParamsSurviveSaveAndReload() {
        val store = StrategyStore(freshDb())
        store.save(
            elliottDefaults().copy(
                ewHigherTf = "4h", ewRequireHtf = true, ewSwingAtr = 3.2, ewSubDegree = 0.3,
                ewStructure = "STRICT", ewTradeWave3 = false, ewTradeWave5 = true, ewTradeCorrection = true,
                ewW2RetraceMin = 0.5, ewW2RetraceMax = 0.7, ewW4RetraceMin = 0.3, ewW4RetraceMax = 0.45,
                ewMinGuidelines = 3, ewWave3Entry = "TURN", ewEntryWindowBars = 5, ewStopAnchor = "PIVOT",
                ewStopBufferAtr = 0.35, ewW3Target = 2.0, ewW5Target = 0.618, ewMinRewardRisk = 2.5,
            )
        )
        val r = store.loadAll().first { it.id == ELLIOTT_ID }
        assertEquals(StrategyType.ELLIOTT.name, r.type)
        assertEquals("15m", r.timeframe)
        assertEquals(true, r.riskSizingEnabled)
        assertEquals(720L, r.minTokenAgeMinutes)
        assertEquals("4h", r.ewHigherTf)
        assertEquals(true, r.ewRequireHtf)
        assertEquals(3.2, r.ewSwingAtr)
        assertEquals(0.3, r.ewSubDegree)
        assertEquals("STRICT", r.ewStructure)
        assertEquals(false, r.ewTradeWave3)
        assertEquals(true, r.ewTradeWave5)
        assertEquals(true, r.ewTradeCorrection)
        assertEquals(0.5, r.ewW2RetraceMin)
        assertEquals(0.7, r.ewW2RetraceMax)
        assertEquals(0.3, r.ewW4RetraceMin)
        assertEquals(0.45, r.ewW4RetraceMax)
        assertEquals(3, r.ewMinGuidelines)
        assertEquals("TURN", r.ewWave3Entry)
        assertEquals(5, r.ewEntryWindowBars)
        assertEquals("PIVOT", r.ewStopAnchor)
        assertEquals(0.35, r.ewStopBufferAtr)
        assertEquals(2.0, r.ewW3Target)
        assertEquals(0.618, r.ewW5Target)
        assertEquals(2.5, r.ewMinRewardRisk)
    }

    /**
     * Старая БД после удаления Momentum и RSI+EMA: физически в ней остались колонки RSI/EMA/порога
     * объёма (с DEFAULT) и строки этих стратегий. Строки удаляются при старте, а чтение и запись
     * стратегий с лишними колонками работают.
     */
    @Test fun removedStrategyTypesAndColumnsDoNotBreakOldDb() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        listOf(
            "rsi_period INTEGER NOT NULL DEFAULT 14", "rsi_overbought REAL NOT NULL DEFAULT 70.0",
            "rsi_oversold REAL NOT NULL DEFAULT 30.0", "ema_fast INTEGER NOT NULL DEFAULT 9",
            "ema_slow INTEGER NOT NULL DEFAULT 21", "volume_threshold REAL NOT NULL DEFAULT 1.5",
        ).forEach { driver.execute(null, "ALTER TABLE strategy ADD COLUMN $it", 0) }
        listOf("momentum-scalp" to "MOMENTUM", "rsi-ema-memecoins" to "RSI_EMA", "dars-memecoins" to "DARS").forEach { (id, type) ->
            driver.execute(null, "INSERT INTO strategy (id, name, type, created_at, updated_at) VALUES ('$id', '$id', '$type', 0, 0)", 0)
        }
        val db = DrxDatabase(driver)

        seedDefaultStrategies(db)

        val store = StrategyStore(db)
        assertEquals(setOf("dars-memecoins", BREAKOUT_PULLBACK_ID, MEME_PLAYBOOK_ID, ELLIOTT_ID), store.loadAll().map { it.id }.toSet())
        store.save(StrategyConfig(id = "new", name = "new", atrPeriod = 10)) // insert с лишними колонками
        store.save(store.loadAll().first { it.id == "new" }.copy(atrPeriod = 12)) // и update
        assertEquals(12, store.loadAll().first { it.id == "new" }.atrPeriod)
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
            "tiered_trail_enabled", "trail_tier1_pct", "trail_tier2_pct", "trail_tier3_pct", "trail_tier4_pct",
            "stale_exit_enabled", "stale_exit_minutes",
            "mp_lookback_bars", "mp_min_impulse_pct", "mp_fib_min", "mp_fib_max", "mp_pullback_volume_ratio",
            "mp_trigger_volume_mult", "mp_min_liq_to_mc_pct", "mp_require_revoked_authorities", "mp_reject_copycats",
            "ew_higher_tf", "ew_require_htf", "ew_swing_atr", "ew_sub_degree", "ew_structure",
            "ew_trade_wave3", "ew_trade_wave5", "ew_trade_correction",
            "ew_w2_retrace_min", "ew_w2_retrace_max", "ew_w4_retrace_min", "ew_w4_retrace_max",
            "ew_min_guidelines", "ew_wave3_entry", "ew_entry_window_bars", "ew_stop_anchor",
            "ew_stop_buffer_atr", "ew_w3_target", "ew_w5_target", "ew_min_reward_risk",
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
        assertEquals(false, cfg.tieredTrailEnabled)
        assertEquals(false, cfg.staleExitEnabled)
        assertEquals(0.786, cfg.mpFibMax)
        assertEquals("1h", cfg.ewHigherTf)
        assertEquals(2.5, cfg.ewSwingAtr)
        assertEquals("BREAK", cfg.ewWave3Entry)
        assertEquals(true, cfg.ewTradeWave3)
        assertEquals(false, cfg.ewTradeCorrection)
        val trade = db.tradeQueries.getOpenTrades().executeAsList().single()
        assertEquals(0.0, trade.initial_stop)   // нет данных → монитор возьмёт R из SL% конфига
        assertEquals(0.0, trade.entry_atr_frac)
        assertEquals(0L, trade.peak_at)         // нет данных → «нет нового максимума» считается от входа
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
