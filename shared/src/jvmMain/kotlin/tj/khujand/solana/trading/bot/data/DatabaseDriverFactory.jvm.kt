package tj.khujand.solana.trading.bot.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

actual fun createDatabaseDriver(dbName: String): SqlDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$dbName")
    DrxDatabase.Schema.create(driver)
    return driver
}
