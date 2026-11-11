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
import tj.khujand.solana.trading.bot.core.engine.beginNewRun
import tj.khujand.solana.trading.bot.core.engine.watchCandleFeed
import tj.khujand.solana.trading.bot.core.risk.EntryGuard
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.core.strategy.WatchBoard
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.createDatabaseDriver
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.domain.backtest.BacktestService
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalyzerService
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.JupiterSwapQuotes
import tj.khujand.solana.trading.bot.exchange.dex.KeyBudget
import tj.khujand.solana.trading.bot.exchange.dex.RequestLane
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner
import tj.khujand.solana.trading.bot.exchange.dex.toTokenCandidate
import kotlin.time.Clock
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
    // Монету вне сканера ищет по mint/тикеру через DexScreener.
    val marketAnalyzer = MarketAnalyzerService(
        resolver = { query ->
            client.dexScreener.findSolanaPair(query)
                ?.toTokenCandidate(Clock.System.now().toEpochMilliseconds())
        },
        // Скан запускает человек и смотрит на прогресс: пусть монеты дождутся очереди к GeckoTerminal,
        // а не отвалятся с «мало свечей», как только очередь стала длиннее обычного ожидания стратегии.
        // Разбор запускает человек — свечи идут с ключом CoinGecko, пока хватает его дневного лимита.
        candleSource = { pool, tf, agg, limit ->
            GeckoTerminalApi.getCandles(pool, tf, agg, limit, maxWaitMs = ANALYSIS_MAX_WAIT_MS, lane = RequestLane.USER)
        },
    )

    // Бэктест стратегий по истории GeckoTerminal — держит загруженные свечи между прогонами.
    val backtest = BacktestService()

    // Котировки Jupiter и mint-аккаунты — общие для проверки входа и исполнения (один кеш).
    private val quotes = JupiterSwapQuotes(client)
    val executor = TradeExecutor(client, db, settingsStore, accountCache, activityLog, quotes)
    val riskManager = RiskManager(db, accountCache, executor::isDemo, settingsStore::getProtection)
    private val entryGuard = EntryGuard(quotes, settingsStore::getProtection)

    val notifier = TelegramNotifier(
        settingsStore.getTelegramToken() ?: "",
        settingsStore.getTelegramChatId() ?: 0L,
    )

    // Доска наблюдения: что стратегии видят по каждому кандидату — из того же цикла сканера.
    val watchBoard = WatchBoard()

    val strategyManager = StrategyManager(
        client, riskManager, notifier, db, scanner, executor, activityLog, settingsStore, entryGuard, watchBoard
    )
    val engine = BotEngine(client, strategyManager, notifier, db, accountCache, executor, settingsStore, activityLog)
    val tradeMonitor = TradeMonitor(client, db, executor, notifier, activityLog, protection = settingsStore::getProtection)

    // Собственный scope рантайма: здесь живёт long-polling Telegram-контроллера, чтобы он
    // переживал уход Activity так же, как движок (раньше polling был привязан к Compose и
    // умирал вместе с UI). SupervisorJob — падение одной корутины не роняет остальные.
    private val runtimeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var telegramController: TelegramBotController? = null
    private var telegramJob: Job? = null

    init {
        // Ключ — до первого запроса свечей и до наблюдателя: он сообщает в ленту, с ключом ли работаем.
        // Счёт расхода ключа живёт в настройках — перезапуск не обнуляет дневной лимит.
        GeckoTerminalApi.setKeyBudget(
            KeyBudget(
                monthlyQuota = settingsStore::getCoinGeckoQuota,
                load = settingsStore::getCoinGeckoUsage,
                save = settingsStore::setCoinGeckoUsage,
            )
        )
        GeckoTerminalApi.setApiKey(settingsStore.getCoinGeckoKey())
        runtimeScope.watchCandleFeed(GeckoTerminalApi.status, activityLog)
        tradeMonitor.start()
        syncTelegramController()
    }

    // Авто-возобновление при запуске приложения — один раз на процесс: не спорить с пользователем,
    // который уже сам нажал «Стоп» или «Старт».
    private var launchResumeConsumed = false

    /**
     * Вернуть ли торговлю при запуске приложения (десктоп, открытие приложения, перезагрузка телефона):
     * см. [shouldResumeOnLaunch]. true — только при первом вызове за процесс.
     */
    fun takeResumeOnLaunch(): Boolean {
        if (launchResumeConsumed) return false
        launchResumeConsumed = true
        return shouldResumeOnLaunch(settingsStore.getResumeOnLaunch(), engine.getStatus(), settingsStore.getDesiredState())
    }

    /** Возвращает движок в состояние, в котором его оставили: RUNNING или PAUSED. */
    fun restoreDesiredState() {
        runtimeScope.launch {
            when (settingsStore.getDesiredState()) {
                BotStatus.RUNNING.name -> engine.start()
                BotStatus.PAUSED.name -> engine.pause()
                else -> Unit
            }
        }
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
            riskManager = riskManager,
            watchBoard = watchBoard,
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
        marketAnalyzer.close()
        tradeMonitor.close()
        engine.shutdown()
        notifier.close()
        client.close()
    }

    /**
     * Ручная покупка монеты из «Анализа»: пара — из кеша сканера или DexScreener, цена — живая прямо перед
     * покупкой. Дальше — [StrategyManager.openManual]: лимиты портфеля, проверка монеты, исполнение режима.
     */
    suspend fun manualBuy(mint: String, sizeUsd: Double, stopLossPercent: Double, takeProfitPercent: Double): StrategyManager.ManualResult {
        val now = Clock.System.now().toEpochMilliseconds()
        val candidate = tokenCache.all().firstOrNull { it.mint == mint }
            ?: runCatching { client.dexScreener.findSolanaPair(mint)?.toTokenCandidate(now) }.getOrNull()
            ?: return StrategyManager.ManualResult.Rejected("пара монеты не найдена на DexScreener")
        val live = runCatching { client.getTokenPriceUsd(mint) }.getOrNull()
        return strategyManager.openManual(candidate, sizeUsd, stopLossPercent, takeProfitPercent, live)
    }

    /**
     * «Новый старт» текущего режима: статистика и лимиты убытка — с этого момента, сделки остаются
     * (см. [beginNewRun]). [newDemoBalance] — новый демо-счёт (только DEMO); null — прежний.
     */
    fun startNewRun(newDemoBalance: Double?): String =
        beginNewRun(db, executor, riskManager, newDemoBalance, Clock.System.now().toEpochMilliseconds())
            .also { activityLog.success(it) }

    /** Полный сброс бота: сделки, журнал транзакций, кеши, демо-счёт. Стратегии и настройки не трогаем. */
    fun resetBotData() {
        db.tradeQueries.deleteAll()
        db.tradingRunQueries.deleteAll()
        db.txJournalQueries.deleteAll()
        db.tokenCacheQueries.deleteAll()
        db.accountCacheQueries.deleteAll()
        executor.resetDemoBalance()
        activityLog.clear()
    }
}

/** Сколько разбор на экране «Анализ» ждёт очереди к GeckoTerminal (без ключа запрос уходит раз в 15 с). */
private const val ANALYSIS_MAX_WAIT_MS = 3 * 60_000L

/**
 * Возобновлять ли торговлю при запуске приложения: включён тумблер [enabled], движок ещё стоит, а
 * оставили его работающим или на паузе ([desiredState] — имя [BotStatus]).
 */
internal fun shouldResumeOnLaunch(enabled: Boolean, status: BotStatus, desiredState: String?): Boolean =
    enabled && status == BotStatus.STOPPED &&
        (desiredState == BotStatus.RUNNING.name || desiredState == BotStatus.PAUSED.name)

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
