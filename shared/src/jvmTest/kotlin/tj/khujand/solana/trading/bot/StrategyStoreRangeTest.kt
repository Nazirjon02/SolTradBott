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
