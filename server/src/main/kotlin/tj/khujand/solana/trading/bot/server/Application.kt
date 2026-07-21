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
import tj.khujand.solana.trading.bot.core.NoopNotifier
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

    val rpcUrl = settingsStore.getRpcUrl() ?: config.rpcUrl
    val client = DexClient(rpcUrl = rpcUrl)
    val accountCache = AccountCache(db)
    val tokenCache = TokenCache(db)
    val scanner = TokenScanner(client, tokenCache)
    val activityLog = ActivityLog()
    val executor = TradeExecutor(client, db, settingsStore, accountCache, activityLog)
    val riskManager = RiskManager(db, accountCache, executor::isDemo)

    // Telegram: токен из БД (приоритет) или из env. Пусто — нотификации глушатся.
    val tgToken = settingsStore.getTelegramToken() ?: config.telegramToken
    val tgChatId = settingsStore.getTelegramChatId() ?: config.telegramChatId
    val notifier = if (tgToken.isNotBlank() && tgChatId != 0L) TelegramNotifier(tgToken, tgChatId) else NoopNotifier

    val strategyManager = StrategyManager(
        client, riskManager, notifier, db, scanner, executor, activityLog, settingsStore
    )
    val engine = BotEngine(client, strategyManager, notifier, db, accountCache, executor, activityLog)

    // Монитор сопровождения позиций: на DEX нет биржевых SL/TP — выходы исполняет бот.
    val tradeMonitor = TradeMonitor(client, db, executor, notifier, activityLog)
    tradeMonitor.start()

    // SupervisorJob обязателен: в этом scope живёт и long-polling Telegram, и коллекторы
    // статуса для каждого WebSocket. С обычным Job падение одной корутины (например,
    // send в отвалившийся сокет) отменяло весь scope и молча убивало Telegram-управление.
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Telegram-управление: long-polling запускается только при заданном токене.
    if (tgToken.isNotBlank()) {
        val telegramBot = TelegramBotController(
            tgToken, tgChatId, engine, strategyManager, executor, db, tokenCache, settingsStore
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
                    if (cred.token == config.apiKey) UserIdPrincipal("admin") else null
                }
            }
        }

        routing {
            get("/health") { call.respond(mapOf("status" to "ok", "bot" to "DRX")) }

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
                                "mode" to if (executor.isDemo()) "DEMO" else "REAL"
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

                    // Переключатель DEMO/REAL (глобальный).
                    post("/mode/demo") {
                        settingsStore.setDemoMode(true)
                        call.respond(mapOf("mode" to "DEMO"))
                    }
                    post("/mode/real") {
                        settingsStore.setDemoMode(false)
                        call.respond(mapOf("mode" to "REAL"))
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
