package tj.khujand.solana.trading.bot

import app.cash.sqldelight.db.SqlDriver

/**
 * Идемпотентно добавляет новые колонки в таблицу `strategy` для уже существующих БД.
 *
 * Зачем: `createDatabaseDriver` вызывает `DrxDatabase.Schema.create`, но все таблицы —
 * `CREATE TABLE IF NOT EXISTS`, а версионных .sqm-миграций в проекте нет. Значит на уже
 * созданной БД новые колонки из CREATE TABLE НЕ появятся, и `SELECT *` (getAll) упадёт.
 *
 * Логика (как в MRX `migrateStrategyTable`):
 * - Новая БД → CREATE TABLE уже создал все колонки → ALTER падает «duplicate column» → ловим.
 * - Старая БД → ALTER добавляет недостающие колонки с DEFAULT для существующих строк.
 *
 * Выполняем через SqlDriver напрямую, минуя сгенерированный StrategyQueries.
 */
fun migrateStrategyTable(driver: SqlDriver) {
    val alters = listOf(
        "ALTER TABLE strategy ADD COLUMN range_filter_enabled INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE strategy ADD COLUMN range_max_entry_pct REAL NOT NULL DEFAULT 0.8",
        "ALTER TABLE strategy ADD COLUMN range_lookback_bars INTEGER NOT NULL DEFAULT 100",
    )
    for (sql in alters) {
        try {
            driver.execute(null, sql, 0)
        } catch (_: Throwable) {
            // колонка уже существует — это норма
        }
    }
}
