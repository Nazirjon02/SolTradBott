package tj.khujand.solana.trading.bot

import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.BotEngine
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.TradeMonitor
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.createDatabaseDriver
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner
import tj.khujand.solana.trading.bot.telegram.TelegramNotifier

/**
 * Готовый «торговый стек» приложения (аналог BotRuntime в MRX App.kt,
 * вынесен в singleton, чтобы Android-сервис и Compose-UI делили один движок).
 *
 * Сервер собирает такой же стек сам в Application.kt.
 */
class DrxRuntime(val db: DrxDatabase) {
    val settingsStore = SettingsStore(db)
    val strategyStore = StrategyStore(db)
    val activityLog = ActivityLog()

    val client = DexClient(rpcUrl = settingsStore.getRpcUrl() ?: "https://api.mainnet-beta.solana.com")
    val accountCache = AccountCache(db)
    val tokenCache = TokenCache(db)
    val scanner = TokenScanner(client, tokenCache)
    val riskManager = RiskManager(db, accountCache)
    val executor = TradeExecutor(client, db, settingsStore, accountCache, activityLog)

    val notifier = TelegramNotifier(
        settingsStore.getTelegramToken() ?: "",
        settingsStore.getTelegramChatId() ?: 0L,
    )

    val strategyManager = StrategyManager(
        client, riskManager, notifier, db, scanner, executor, activityLog, settingsStore
    )
    val engine = BotEngine(client, strategyManager, notifier, db, accountCache, executor, activityLog)
    val tradeMonitor = TradeMonitor(client, db, executor, notifier, activityLog)

    init {
        tradeMonitor.start()
    }

    /** Полное гашение стека (выход из приложения). После этого объект мёртв. */
    fun shutdown() {
        tradeMonitor.close()
        engine.shutdown()
        notifier.close()
        client.close()
    }

    /** Полный сброс бота: сделки, кеши, демо-счёт. Стратегии и настройки не трогаем. */
    fun resetBotData() {
        db.tradeQueries.deleteAll()
        db.tokenCacheQueries.deleteAll()
        db.accountCacheQueries.deleteAll()
        executor.resetDemoBalance()
        activityLog.clear()
    }
}

/**
 * Держатель singleton-рантайма. `init` вызывается из entry-point платформы
 * (MainActivity / desktop main); UI и Android-сервис читают `get()`.
 */
object DrxRuntimeHolder {
    private var runtime: DrxRuntime? = null

    fun init(dbPath: String): DrxRuntime {
        runtime?.let { return it }
        val driver = createDatabaseDriver(dbPath)
        val db = DrxDatabase(driver)
        seedDefaultStrategies(db)
        return DrxRuntime(db).also { runtime = it }
    }

    fun get(): DrxRuntime? = runtime
}
