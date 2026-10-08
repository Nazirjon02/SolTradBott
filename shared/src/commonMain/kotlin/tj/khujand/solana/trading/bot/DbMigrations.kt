package tj.khujand.solana.trading.bot

import app.cash.sqldelight.db.SqlDriver

/**
 * Идемпотентно добавляет новые колонки в таблицы `strategy` и `trade` для уже существующих БД.
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
        "ALTER TABLE strategy ADD COLUMN score_threshold INTEGER NOT NULL DEFAULT 50",
        "ALTER TABLE strategy ADD COLUMN scan_filters_enabled INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE strategy ADD COLUMN max_daily_loss_enabled INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE strategy ADD COLUMN max_drawdown_enabled INTEGER NOT NULL DEFAULT 1",
        // Выходы в R
        "ALTER TABLE strategy ADD COLUMN r_ladder_enabled INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE strategy ADD COLUMN tp1_r REAL NOT NULL DEFAULT 1.5",
        "ALTER TABLE strategy ADD COLUMN tp2_r REAL NOT NULL DEFAULT 3.0",
        "ALTER TABLE strategy ADD COLUMN break_even_after_tp1 INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE strategy ADD COLUMN runner_enabled INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE strategy ADD COLUMN atr_trail_enabled INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE strategy ADD COLUMN atr_trail_mult REAL NOT NULL DEFAULT 3.0",
        "ALTER TABLE strategy ADD COLUMN atr_trail_activation_r REAL NOT NULL DEFAULT 1.0",
        // Размер от риска
        "ALTER TABLE strategy ADD COLUMN risk_sizing_enabled INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE strategy ADD COLUMN risk_per_trade_percent REAL NOT NULL DEFAULT 1.0",
        "ALTER TABLE strategy ADD COLUMN max_pool_share_percent REAL NOT NULL DEFAULT 1.0",
        // Пробой + откат
        "ALTER TABLE strategy ADD COLUMN bp_higher_tf TEXT NOT NULL DEFAULT '15m'",
        "ALTER TABLE strategy ADD COLUMN bp_require_htf_trend INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE strategy ADD COLUMN bp_breakout_lookback INTEGER NOT NULL DEFAULT 20",
        "ALTER TABLE strategy ADD COLUMN bp_breakout_volume_mult REAL NOT NULL DEFAULT 2.0",
        "ALTER TABLE strategy ADD COLUMN bp_max_bars_since_breakout INTEGER NOT NULL DEFAULT 20",
        "ALTER TABLE strategy ADD COLUMN bp_pullback_volume_ratio REAL NOT NULL DEFAULT 0.7",
        "ALTER TABLE strategy ADD COLUMN bp_trigger_volume_mult REAL NOT NULL DEFAULT 1.2",
        // Таблица trade: база для R и ATR-трейлинга (0 = нет данных, старые сделки)
        "ALTER TABLE trade ADD COLUMN initial_stop REAL NOT NULL DEFAULT 0",
        "ALTER TABLE trade ADD COLUMN entry_atr_frac REAL NOT NULL DEFAULT 0",
    )
    for (sql in alters) {
        try {
            driver.execute(null, sql, 0)
        } catch (_: Throwable) {
            // колонка уже существует — это норма
        }
    }
}
