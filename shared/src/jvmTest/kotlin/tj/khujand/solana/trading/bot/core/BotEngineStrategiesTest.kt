package tj.khujand.solana.trading.bot.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tj.khujand.solana.trading.bot.StrategyStore
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.ActivityLevel
import tj.khujand.solana.trading.bot.core.engine.BotEngine
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.PairsResult
import tj.khujand.solana.trading.bot.exchange.dex.ScanSources
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner
import tj.khujand.solana.trading.bot.migrateStrategyTable

/**
 * Движок запускает все включённые стратегии. Раньше потолок был 3: из шести включённых работали первые три,
 * а остальные молча стояли, хотя на экране значились включёнными. Сеть не нужна — источники сканера пустые.
 */
class BotEngineStrategiesTest {

    private class Env(val engine: BotEngine, val manager: StrategyManager, val store: StrategyStore, val log: ActivityLog)

    private fun env(): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        val settings = SettingsStore(db).apply { setDemoMode(true) }
        val cache = AccountCache(db)
        val client = DexClient()
        val log = ActivityLog()
        val executor = TradeExecutor(client, db, settings, cache, log)
        val risk = RiskManager(db, cache, isDemo = executor::isDemo, protection = settings::getProtection)
        val noSources = object : ScanSources {
            override suspend fun discoverMints(): List<String> = emptyList()
            override suspend fun pairsFor(mints: List<String>) = PairsResult(emptyList(), emptySet())
        }
        val manager = StrategyManager(client, risk, NoopNotifier, db, TokenScanner(TokenCache(db), noSources), executor, log, settings)
        val engine = BotEngine(client, manager, NoopNotifier, db, cache, executor, settings, log)
        return Env(engine, manager, StrategyStore(db), log)
    }

    private fun Env.addStrategies(types: List<StrategyType>, active: Boolean = true) = types.forEach { type ->
        store.save(StrategyConfig(id = "s-${type.name}", name = type.displayName, type = type.name, isActive = active, timeframe = "5m"))
    }

    private suspend fun StrategyManager.awaitRunning(n: Int) {
        withTimeout(5_000) { while (activeCount() < n) delay(20) }
    }

    @Test
    fun allSixActiveStrategiesRun() = runBlocking {
        val e = env()
        e.addStrategies(StrategyType.entries)
        e.engine.start()
        e.manager.awaitRunning(StrategyType.entries.size)
        assertEquals(6, e.manager.activeCount())
        assertTrue(e.log.events.value.none { it.level == ActivityLevel.WARN && it.message.contains("не запущены") })
        e.engine.stop()
    }

    @Test
    fun strategiesSwitchedOnWhileRunningStartToo() = runBlocking {
        val e = env()
        e.addStrategies(StrategyType.entries.take(3))
        e.addStrategies(StrategyType.entries.drop(3), active = false)
        e.engine.start()
        e.manager.awaitRunning(3)

        // Раньше четвёртая получала LIMIT_REACHED и не запускалась.
        StrategyType.entries.drop(3).forEach { type ->
            e.store.setActive("s-${type.name}", true)
            assertEquals(BotEngine.StrategyToggle.STARTED, e.engine.applyStrategyActive("s-${type.name}", true))
        }
        e.manager.awaitRunning(6)
        assertEquals(6, e.manager.activeCount())
        e.engine.stop()
    }
}
