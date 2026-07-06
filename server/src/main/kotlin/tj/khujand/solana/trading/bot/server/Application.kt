package tj.khujand.solana.trading.bot.server

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val riskManager = RiskManager(db, accountCache)
    val executor = TradeExecutor(client, db, settingsStore, accountCache, activityLog)

    // Telegram-контроллер подключается на этапе 6; пока нотификации глушим.
    val notifier = NoopNotifier

    val strategyManager = StrategyManager(
        client, riskManager, notifier, db, scanner, executor, activityLog, settingsStore
    )
    val engine = BotEngine(client, strategyManager, notifier, db, accountCache, executor, activityLog)

    // Монитор сопровождения позиций: на DEX нет биржевых SL/TP — выходы исполняет бот.
    val tradeMonitor = TradeMonitor(client, db, executor, notifier, activityLog)
    tradeMonitor.start()

    val scope = CoroutineScope(Dispatchers.Default)

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
            }

            webSocket("/ws") {
                val job = scope.launch {
                    engine.status.collect { status ->
                        send(Json.encodeToString(mapOf("type" to "status", "value" to status.name)))
                    }
                }
                for (frame in incoming) { /* входящие команды от UI */ }
                job.cancel()
            }
        }
    }.start(wait = true)
}
