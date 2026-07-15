package tj.khujand.solana.trading.bot.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.getAppContext
import tj.khujand.solana.trading.bot.migrateStrategyTable

actual fun createDatabaseDriver(dbName: String): SqlDriver =
    AndroidSqliteDriver(DrxDatabase.Schema, getAppContext(), dbName).also { migrateStrategyTable(it) }
