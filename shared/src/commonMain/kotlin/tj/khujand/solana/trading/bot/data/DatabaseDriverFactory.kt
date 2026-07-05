package tj.khujand.solana.trading.bot.data

import app.cash.sqldelight.db.SqlDriver

expect fun createDatabaseDriver(dbName: String): SqlDriver
