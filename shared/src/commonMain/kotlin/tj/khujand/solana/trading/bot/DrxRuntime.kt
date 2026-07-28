package tj.khujand.solana.trading.bot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.BotEngine
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.TradeMonitor
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.createDatabaseDriver
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalyzerService
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner
import tj.khujand.solana.trading.bot.telegram.TelegramBotController
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

    // Информационный анализатор монеты «Dars» (порт MarketAnalyzer из MRX) — «второе мнение»
    // к движку: считает фазу/сетап/зону входа/запреты по свечам GeckoTerminal, без торговли.
    val marketAnalyzer = MarketAnalyzerService()
    val executor = TradeExecutor(client, db, settingsStore, accountCache, activityLog)
    val riskManager = RiskManager(db, accountCache, executor::isDemo)

    val notifier = TelegramNotifier(
        settingsStore.getTelegramToken() ?: "",
        settingsStore.getTelegramChatId() ?: 0L,
    )

    val strategyManager = StrategyManager(
        client, riskManager, notifier, db, scanner, executor, activityLog, settingsStore
    )
    val engine = BotEngine(client, strategyManager, notifier, db, accountCache, executor, settingsStore, activityLog)
    val tradeMonitor = TradeMonitor(client, db, executor, notifier, activityLog)

    // Собственный scope рантайма: здесь живёт long-polling Telegram-контроллера, чтобы он
    // переживал уход Activity так же, как движок (раньше polling был привязан к Compose и
    // умирал вместе с UI). SupervisorJob — падение одной корутины не роняет остальные.
    private val runtimeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var telegramController: TelegramBotController? = null
    private var telegramJob: Job? = null

    init {
        tradeMonitor.start()
        syncTelegramController()
    }

    /**
     * (Пере)создаёт и запускает Telegram-бот управления по текущим настройкам.
     * Вызывается из init и при сохранении настроек (смена токена/чата). Пустой токен/чат
     * гасит контроллер. Именно этот метод (а не Compose) владеет жизненным циклом polling.
     */
    fun syncTelegramController() {
        telegramJob?.cancel()
        telegramController?.close()
        telegramController = null
        telegramJob = null

        val token = settingsStore.getTelegramToken()
        val chatId = settingsStore.getTelegramChatId()
        if (token.isNullOrBlank() || chatId == null || chatId == 0L) return

        val controller = TelegramBotController(
            botToken = token,
            allowedChatId = chatId,
            engine = engine,
            strategyManager = strategyManager,
            executor = executor,
            db = db,
            tokenCache = tokenCache,
            settingsStore = settingsStore,
        )
        telegramController = controller
        telegramJob = runtimeScope.launch { controller.startPolling() }
        activityLog.info("📲 Telegram подключён — слушаю команды")
    }

    /** Полное гашение стека (выход из приложения). После этого объект мёртв. */
    fun shutdown() {
        telegramJob?.cancel()
        telegramController?.close()
        runtimeScope.cancel()
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
