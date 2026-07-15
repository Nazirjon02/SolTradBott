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
    }

    /**
     * Симуляция «старой» БД без range-колонок (главная причина, ради которой добавлена миграция):
     * migrateStrategyTable должен добавить колонки, а getAll (SELECT *) — не упасть и корректно
     * отдать дефолты для старой строки.
     */
    @Test fun migrationRescuesOldDb() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        // «Откатываем» схему до состояния без range-колонок.
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN range_filter_enabled", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN range_max_entry_pct", 0)
        driver.execute(null, "ALTER TABLE strategy DROP COLUMN range_lookback_bars", 0)
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

        // Идемпотентность: повторный вызов не ломает БД.
        migrateStrategyTable(driver)
        assertEquals(1, db.strategyQueries.getAll().executeAsList().size)
    }
}
