package tj.khujand.solana.trading.bot.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import tj.khujand.solana.trading.bot.core.NoopNotifier
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.BotEngine
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.TradeMonitor
import tj.khujand.solana.trading.bot.core.engine.candleFeedProblems
import tj.khujand.solana.trading.bot.core.engine.healthReport
import tj.khujand.solana.trading.bot.core.engine.watchCandleFeed
import tj.khujand.solana.trading.bot.core.risk.EntryGuard
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.core.strategy.WatchBoard
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.createDatabaseDriver
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.JupiterSwapQuotes
import tj.khujand.solana.trading.bot.exchange.dex.KeyBudget
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner
import tj.khujand.solana.trading.bot.seedDefaultStrategies
import tj.khujand.solana.trading.bot.telegram.TelegramBotController
import tj.khujand.solana.trading.bot.telegram.TelegramNotifier

fun main() {
    val config = DrxConfig()

    val driver = createDatabaseDriver(config.dbPath)
    val db = DrxDatabase(driver)
    // Добавляем стратегии по умолчанию при первом запуске
    seedDefaultStrategies(db)

    val settingsStore = SettingsStore(db)
    // env-конфиг сеет начальные значения в БД, если там ещё пусто (дальше рулят UI/Telegram).
    if (settingsStore.getDemoMode() == null) settingsStore.setDemoMode(config.demoMode)
    if (config.walletSeed.isNotBlank() && settingsStore.getWalletSeed() == null) {
        settingsStore.setWalletSeed(config.walletSeed)
    }
    if (config.telegramToken.isNotBlank() && settingsStore.getTelegramToken() == null) {
        settingsStore.setTelegramToken(config.telegramToken)
    }
    if (config.telegramChatId != 0L && settingsStore.getTelegramChatId() == null) {
        settingsStore.setTelegramChatId(config.telegramChatId)
    }
    if (config.coinGeckoKey.isNotBlank() && settingsStore.getCoinGeckoKey() == null) {
        settingsStore.setCoinGeckoKey(config.coinGeckoKey)
    }
    // Ключ свечей и счёт его расхода — до первого запроса. Торговля идёт без ключа; ключ тратят только запросы
    // человека (бэктест, «Анализ», график), которых у сервера без UI почти нет.
    GeckoTerminalApi.setKeyBudget(
        KeyBudget(
            monthlyQuota = settingsStore::getCoinGeckoQuota,
            load = settingsStore::getCoinGeckoUsage,
            save = settingsStore::setCoinGeckoUsage,
        )
    )
    GeckoTerminalApi.setApiKey(settingsStore.getCoinGeckoKey())

    val rpcUrl = settingsStore.getRpcUrl() ?: config.rpcUrl
    val client = DexClient(rpcUrl = rpcUrl)
    val accountCache = AccountCache(db)
    val tokenCache = TokenCache(db)
    val scanner = TokenScanner(client, tokenCache)
    val activityLog = ActivityLog()
    // Котировки Jupiter и mint-аккаунты — общие для проверки входа и исполнения (один кеш).
    val quotes = JupiterSwapQuotes(client)
    val executor = TradeExecutor(client, db, settingsStore, accountCache, activityLog, quotes)
    val riskManager = RiskManager(db, accountCache, executor::isDemo, settingsStore::getProtection)
    val entryGuard = EntryGuard(quotes, settingsStore::getProtection)

    // Telegram: токен из БД (приоритет) или из env. Пусто — нотификации глушатся.
    val tgToken = settingsStore.getTelegramToken() ?: config.telegramToken
    val tgChatId = settingsStore.getTelegramChatId() ?: config.telegramChatId
    val notifier = if (tgToken.isNotBlank() && tgChatId != 0L) TelegramNotifier(tgToken, tgChatId) else NoopNotifier

    // Доска наблюдения — для Telegram /watch (UI сервера нет).
    val watchBoard = WatchBoard()
    val strategyManager = StrategyManager(
        client, riskManager, notifier, db, scanner, executor, activityLog, settingsStore, entryGuard, watchBoard
    )
    val engine = BotEngine(client, strategyManager, notifier, db, accountCache, executor, settingsStore, activityLog)

    // Монитор сопровождения позиций: на DEX нет биржевых SL/TP — выходы исполняет бот.
    val tradeMonitor = TradeMonitor(client, db, executor, notifier, activityLog, protection = settingsStore::getProtection)
    tradeMonitor.start()

    val apiKey = resolveApiKey(config.apiKey, settingsStore)

    // SupervisorJob обязателен: в этом scope живёт и long-polling Telegram, и коллекторы
    // статуса для каждого WebSocket. С обычным Job падение одной корутины (например,
    // send в отвалившийся сокет) отменяло весь scope и молча убивало Telegram-управление.
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Лимит и ключ источника свечей — в ленту активности (UI у сервера нет, но лента видна в логах и Telegram).
    scope.watchCandleFeed(GeckoTerminalApi.status, activityLog)

    // После рестарта процесса (docker `restart: always`, перезагрузка VPS) возвращаем движок в то
    // состояние, в котором его оставили, — как это делает Android-сервис. Иначе бот молча стоял
    // до ручного /start, хотя до падения торговал.
    scope.launch {
        when (settingsStore.getDesiredState()) {
            BotStatus.RUNNING.name -> engine.start()
            BotStatus.PAUSED.name -> engine.pause()
            else -> Unit
        }
    }

    // Telegram-управление: long-polling запускается только при заданном токене.
    if (tgToken.isNotBlank()) {
        val telegramBot = TelegramBotController(
            tgToken, tgChatId, engine, strategyManager, executor, db, tokenCache, settingsStore, riskManager, watchBoard
        )
        scope.launch { telegramBot.startPolling() }
    }

    embeddedServer(Netty, port = config.serverPort) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
        install(CORS) { anyHost() }
        install(Authentication) {
            bearer("api-key") {
                authenticate { cred ->
                    // Сравнение за постоянное время — без утечки ключа по таймингу ответа.
                    val ok = MessageDigest.isEqual(cred.token.toByteArray(), apiKey.toByteArray())
                    if (ok) UserIdPrincipal("admin") else null
                }
            }
        }

        routing {
            // Для внешнего мониторинга: 503 + список проблем, если бот должен торговать, а не торгует.
            get("/health") {
                val now = System.currentTimeMillis()
                val report = healthReport(
                    status = engine.getStatus(),
                    desiredState = settingsStore.getDesiredState(),
                    mode = executor.modeLabel(),
                    lastOkAt = activityLog.stats.value.lastOkAt,
                    engineStartedAt = engine.startedAt,
                    nowMs = now,
                    dataProblems = candleFeedProblems(GeckoTerminalApi.status.value, now),
                )
                call.respond(if (report.problems.isEmpty()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, report)
            }

            authenticate("api-key") {
                route("/api/v1") {
                    post("/bot/start") { engine.start(); call.respond(mapOf("status" to engine.getStatus().name)) }
                    post("/bot/stop") { engine.stop(); call.respond(mapOf("status" to "stopped")) }
                    post("/bot/pause") { engine.pause(); call.respond(mapOf("status" to "paused")) }
                    post("/bot/resume") { engine.resume(); call.respond(mapOf("status" to "resumed")) }
                    get("/bot/status") {
                        call.respond(
                            mapOf(
                                "status" to engine.getStatus().name,
                                "mode" to executor.modeLabel()
                            )
                        )
                    }

                    get("/stats") { call.respond(engine.getStats()) }
                    get("/positions") { call.respond(engine.getPositions()) }
                    post("/positions/closeall") { engine.closeAllPositions(); call.respond(mapOf("status" to "closed")) }
                    post("/positions/{id}/close") {
                        val id = call.parameters["id"]
                        if (id.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
                            return@post
                        }
                        val result = engine.closePosition(id)
                        when (result.status) {
                            BotEngine.ClosePositionStatus.CLOSED -> call.respond(
                                mapOf("status" to "closed", "symbol" to (result.symbol ?: ""), "pnlUsd" to result.pnlUsd.toString())
                            )
                            BotEngine.ClosePositionStatus.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, mapOf("status" to "not_found"))
                            BotEngine.ClosePositionStatus.NOT_OPEN -> call.respond(HttpStatusCode.Conflict, mapOf("status" to "not_open"))
                            BotEngine.ClosePositionStatus.FAILED -> call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "failed"))
                        }
                    }
                    get("/balance") { call.respond(engine.getBalance()) }

                    get("/strategies") {
                        val list = db.strategyQueries.getAll().executeAsList()
                        call.respond(list.map { mapOf("id" to it.id, "name" to it.name, "active" to (it.is_active == 1L).toString()) })
                    }

                    get("/trades") {
                        val trades = db.tradeQueries.getAll().executeAsList()
                        call.respond(trades.size)
                    }

                    // Текущие кандидаты сканера (из TokenCache).
                    get("/tokens/scanner") {
                        call.respond(
                            tokenCache.all().map {
                                mapOf(
                                    "mint" to it.mint,
                                    "symbol" to it.symbol,
                                    "priceUsd" to it.priceUsd.toString(),
                                    "liquidityUsd" to it.liquidityUsd.toString(),
                                    "marketCap" to it.marketCap.toString(),
                                    "score" to it.score.toString(),
                                    "ageMinutes" to it.tokenAgeMinutes.toString()
                                )
                            }
                        )
                    }

                    // Переключатель DEMO/SHADOW/REAL (глобальный). SHADOW — DEMO по котировкам Jupiter.
                    post("/mode/demo") {
                        settingsStore.setDemoMode(true)
                        settingsStore.setShadowQuotes(false)
                        call.respond(mapOf("mode" to executor.modeLabel()))
                    }
                    post("/mode/shadow") {
                        settingsStore.setDemoMode(true)
                        settingsStore.setShadowQuotes(true)
                        call.respond(mapOf("mode" to executor.modeLabel()))
                    }
                    post("/mode/real") {
                        settingsStore.setDemoMode(false)
                        call.respond(mapOf("mode" to executor.modeLabel()))
                    }
                }

                // Под авторизацией, как и остальной API: без ключа наружу отдаём только /health.
                webSocket("/ws") {
                    // Коллектор живёт в scope самой сессии, а не приложения: при обрыве сокета
                    // он гаснет вместе с ней и не тащит ошибку в общий scope.
                    val job = launch {
                        try {
                            engine.status.collect { status ->
                                send(Json.encodeToString(mapOf("type" to "status", "value" to status.name)))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // сокет закрылся на середине отправки — просто гасим коллектор
                        }
                    }
                    try {
                        for (frame in incoming) { /* входящие команды от UI */ }
                    } finally {
                        job.cancel()
                    }
                }
            }
        }
    }.start(wait = true)
}

private const val API_KEY_SETTING = "server.api_key"
private const val LEGACY_DEFAULT_API_KEY = "soltrad-secret"

/**
 * Ключ REST API: из SOLTRAD_API_KEY, а если не задан — случайный, сгенерированный один раз
 * и сохранённый в БД (переживает рестарты). Печатается в лог при старте, чтобы его можно было взять.
 */
private fun resolveApiKey(configured: String, settingsStore: SettingsStore): String {
    if (configured.isNotBlank()) {
        if (configured == LEGACY_DEFAULT_API_KEY) {
            println("⚠️ SOLTRAD_API_KEY = «$LEGACY_DEFAULT_API_KEY» — общеизвестный ключ из примера, смените его!")
        }
        return configured
    }
    val stored = settingsStore.get(API_KEY_SETTING)?.takeIf { it.isNotBlank() }
    val key = stored ?: generateApiKey().also { settingsStore.set(API_KEY_SETTING, it) }
    println("🔑 SOLTRAD_API_KEY не задан — используется ключ из БД: $key")
    return key
}

private fun generateApiKey(): String {
    val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}
