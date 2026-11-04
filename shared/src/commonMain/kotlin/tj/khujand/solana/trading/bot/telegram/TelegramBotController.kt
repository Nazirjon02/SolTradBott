package tj.khujand.solana.trading.bot.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tj.khujand.solana.trading.bot.core.engine.BotEngine
import tj.khujand.solana.trading.bot.core.engine.EXEC_REAL
import tj.khujand.solana.trading.bot.core.engine.EXEC_SHADOW
import tj.khujand.solana.trading.bot.core.engine.EXIT_BLOCKED_AFTER
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.beginNewRun
import tj.khujand.solana.trading.bot.core.engine.runSummaries
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.core.strategy.WatchBoard
import tj.khujand.solana.trading.bot.core.strategy.WatchStatus
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.util.tradeTimeLine
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.util.formatLargeNumber
import tj.khujand.solana.trading.bot.util.formatNumber

private val BotStatus.emoji: String
    get() = when (this) {
        BotStatus.RUNNING -> "🟢"
        BotStatus.PAUSED -> "⏸"
        BotStatus.STOPPED -> "🔴"
    }

/** Команды в меню-подсказке Telegram (setMyCommands). */
private val BOT_COMMANDS = listOf(
    "menu" to "Панель управления",
    "status" to "Статус бота",
    "positions" to "Открытые позиции",
    "close" to "Закрыть позицию: /close СИМВОЛ",
    "balance" to "Баланс",
    "stats" to "Статистика",
    "report" to "Отчёт за день",
    "scanner" to "Кандидаты сканера",
    "watch" to "Доска наблюдения: чего ждут стратегии",
    "strategies" to "Управление стратегиями",
    "mode" to "Режим DEMO / REAL",
    "signalonly" to "Режим «только сигнал»",
    "settings" to "Защита и автоматика",
    "runs" to "Забеги: итоги каждого «Нового старта»",
    "reset" to "Новый старт: итоги и лимиты с нуля (/reset 10000 — и демо-счёт)",
    "pause" to "Пауза",
    "resume" to "Продолжить",
    "stop" to "Остановить бота",
    "closeall" to "Закрыть все позиции",
    "help" to "Помощь по командам",
)

/**
 * Переключатель из меню «🛡 Защита» (`set:<key>`): меняет настройку в [store] и возвращает ответ для чата;
 * null — ключ неизвестен. Действует сразу: защиту и автоматику читают на каждой проверке.
 */
internal fun toggleSetting(store: SettingsStore, key: String): String? {
    val p = store.getProtection()
    return when (key) {
        "guard" -> {
            val v = !p.guardEnabled
            store.setProtection(p.copy(guardEnabled = v))
            if (v) "🛡 Проверка монеты включена"
            else "⚠️ Проверка монеты ВЫКЛЮЧЕНА — бот может купить монету без маршрута продажи или с неотозванной authority"
        }
        "portfolio" -> {
            val v = !p.portfolioEnabled
            store.setProtection(p.copy(portfolioEnabled = v))
            if (v) "📊 Лимиты портфеля включены" else "⚠️ Лимиты портфеля ВЫКЛЮЧЕНЫ — стратегии ограничены только своими лимитами"
        }
        "autostop" -> {
            val v = !p.autoStopOnLimit
            store.setProtection(p.copy(autoStopOnLimit = v))
            if (v) "🛑 При лимите убытка портфеля бот будет остановлен целиком" else "⏸ При лимите убытка — только пауза входов"
        }
        "revexit" -> {
            val v = !p.reversalExitEnabled
            store.setProtection(p.copy(reversalExitEnabled = v))
            if (v) "🔄 Выход по развороту включён" else "⭕ Выход по развороту выключен"
        }
        "resume" -> {
            val v = !store.getResumeOnLaunch()
            store.setResumeOnLaunch(v)
            if (v) "▶️ Приложение будет возобновлять торговлю при запуске" else "⭕ Приложение не возобновляет торговлю само"
        }
        else -> null
    }
}

/**
 * Telegram-бот управления: long-polling, inline-меню, доступ только с allowedChatId.
 *
 * Одни и те же действия доступны и текстовой командой (`/status`), и кнопкой
 * (`cmd:status`) — оба маршрута сходятся в [runAction], поэтому подтверждения
 * и поведение всегда одинаковы. Исключение — `/start`/`/menu`, которые по
 * соглашению Telegram открывают панель, тогда как кнопка «🟢 Старт» (`cmd:start`)
 * запускает движок.
 */
class TelegramBotController(
    private val botToken: String,
    private val allowedChatId: Long,
    private val engine: BotEngine,
    private val strategyManager: StrategyManager,
    private val executor: TradeExecutor,
    private val db: DrxDatabase,
    private val tokenCache: TokenCache,
    private val settingsStore: SettingsStore,
    /** Капитал для «Нового старта» (`/reset`); null — команда недоступна. */
    private val riskManager: RiskManager? = null,
    /** Доска наблюдения для `/watch`; null — не ведётся. */
    private val watchBoard: WatchBoard? = null,
) {
    private val apiUrl = "https://api.telegram.org/bot$botToken"
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        // Таймаут запроса должен превышать long-poll timeout (30 c), иначе каждый цикл падает.
        install(HttpTimeout) { requestTimeoutMillis = 40_000 }
    }

    suspend fun startPolling() {
        registerCommands()
        // Пропускаем накопившийся за простой бэклог — иначе устаревшие команды
        // (например, /stop, отправленный вчера) выполнятся при перезапуске.
        var offset = skipBacklog()
        while (true) {
            try {
                val response = client.get("$apiUrl/getUpdates") {
                    parameter("offset", offset)
                    parameter("timeout", 30)
                    parameter("allowed_updates", """["message","callback_query"]""")
                }
                val updates = response.body<TgUpdatesResponse>()
                updates.result.forEach { update ->
                    // offset сдвигаем ДО обработки: апдейт, упавший с исключением, иначе приходил бы
                    // снова каждые 5 с и команда (например, /closeall) выполнялась бы по кругу.
                    offset = update.updateId + 1
                    try {
                        processUpdate(update)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("⚠️ Telegram: ошибка обработки апдейта ${update.updateId}: ${e.message}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(5_000)
            }
        }
    }

    /** Возвращает offset за последним ожидающим апдейтом, подтверждая весь бэклог. */
    private suspend fun skipBacklog(): Long = runCatching {
        val resp = client.get("$apiUrl/getUpdates") {
            parameter("offset", -1)
            parameter("timeout", 0)
        }
        resp.body<TgUpdatesResponse>().result.lastOrNull()?.let { it.updateId + 1 } ?: 0L
    }.getOrDefault(0L)

    private suspend fun registerCommands() {
        runCatching {
            client.post("$apiUrl/setMyCommands") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("commands", buildJsonArray {
                        BOT_COMMANDS.forEach { (cmd, desc) ->
                            add(buildJsonObject {
                                put("command", cmd)
                                put("description", desc)
                            })
                        }
                    })
                })
            }
        }
    }

    private suspend fun processUpdate(update: TgUpdate) {
        val chatId = update.message?.chat?.id
            ?: update.callbackQuery?.message?.chat?.id
            ?: return

        // Чужие чаты игнорируем молча — не выдаём существование бота и не даём спамить.
        if (chatId != allowedChatId) return

        update.message?.text?.let { handleTextCommand(chatId, it) }

        update.callbackQuery?.let { cb ->
            // Отвечаем на нажатие сразу: REAL-закрытие идёт до минуты, а Telegram ждёт ответ
            // считанные секунды — иначе кнопка «висит» с часиками и ответ отбрасывается.
            runCatching {
                client.post("$apiUrl/answerCallbackQuery") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("callback_query_id", cb.id) })
                }
            }
            val data = cb.data ?: ""
            when {
                data.startsWith("cmd:") -> handleCmd(chatId, data.removePrefix("cmd:"))
                data.startsWith("close:") -> handleCloseCallback(chatId, data.removePrefix("close:"))
                data.startsWith("strategy:") -> handleStrategyCallback(chatId, data)
                data.startsWith("mode:") -> handleModeCallback(chatId, data)
                data.startsWith("signal:") -> handleSignalCallback(chatId, data)
                data.startsWith("set:") -> handleSettingsCallback(chatId, data.removePrefix("set:"))
                data.startsWith("run:") -> handleRunCallback(chatId, data.removePrefix("run:"))
            }
        }
    }

    // ─── Маршрутизация команд ────────────────────────────────────────────────

    /**
     * Текстовые команды: `/start` и `/menu` открывают панель, остальное — общие действия.
     * Слэш обязателен — иначе обычное сообщение «stop» или «closeall» в чате
     * останавливало движок и закрывало позиции.
     */
    private suspend fun handleTextCommand(chatId: Long, text: String) {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return
        when (val cmd = trimmed.substringBefore(' ').removePrefix("/").substringBefore('@').lowercase()) {
            "start", "menu" -> sendMainMenu(chatId)
            "help" -> sendHelp(chatId)
            "close" -> handleCloseCommand(chatId, text)
            "reset" -> sendResetConfirm(chatId, trimmed.substringAfter(' ', "").trim())
            else -> runAction(chatId, cmd)
        }
    }

    /**
     * Кнопки `cmd:*`: «Старт» запускает движок, «Меню» открывает панель, остальное — общие действия.
     *
     * Итог старта/стопа/паузы/резюме движок сам шлёт в этот же чат (через notifier) — здесь
     * отвечаем только когда движок промолчит. Раньше ответ был безусловным: на «Старт» без
     * активных стратегий приходило «⚠️ Бот не запущен», а следом — «🟢 Бот запущен».
     */
    private suspend fun handleCmd(chatId: Long, action: String) {
        when (action) {
            "start" -> {
                if (engine.getStatus() == BotStatus.RUNNING) send(chatId, "ℹ️ Бот уже запущен")
                else engine.start()
            }
            "menu" -> sendMainMenu(chatId)
            else -> runAction(chatId, action)
        }
    }

    /** Действия, общие для текстовых команд и кнопок — единый источник поведения. */
    private suspend fun runAction(chatId: Long, action: String) {
        when (action) {
            "stop" -> engine.stop()
            "pause" -> engine.pause()
            "resume" -> {
                if (engine.getStatus() != BotStatus.PAUSED) send(chatId, "ℹ️ Бот не на паузе — продолжать нечего")
                else engine.resume()
            }
            "closeall" -> {
                // REAL-продажи идут по очереди и могут занять минуты — подтверждаем приём сразу,
                // итог («закрыто N из M») движок пришлёт сам.
                send(chatId, "🚨 Закрываю все позиции…")
                engine.closeAllPositions()
            }
            "status" -> sendStatus(chatId)
            "balance" -> sendBalance(chatId)
            "positions" -> sendPositions(chatId)
            "stats" -> sendStats(chatId)
            "strategies" -> sendStrategiesMenu(chatId)
            "report" -> sendDailyReport(chatId)
            "scanner" -> sendScanner(chatId)
            "watch" -> sendWatchBoard(chatId)
            "mode" -> sendModeMenu(chatId)
            "signalonly" -> sendSignalOnlyMenu(chatId)
            "settings" -> sendSettingsMenu(chatId)
            "runs" -> sendRuns(chatId)
            "reset" -> sendResetConfirm(chatId, "")
            // Неизвестные команды игнорируем — не засоряем чат ответами об ошибке.
        }
    }

    // ─── Меню ────────────────────────────────────────────────────────────────

    /** Режим для сообщений: DEMO, DEMO по котировкам Jupiter (SHADOW) или REAL. */
    private fun modeText(): String = when (executor.modeLabel()) {
        EXEC_REAL -> "💸 REAL"
        EXEC_SHADOW -> "🎮 DEMO · котировки Jupiter (SHADOW)"
        else -> "🎮 DEMO"
    }

    private suspend fun sendMainMenu(chatId: Long) {
        val status = engine.getStatus()
        val mode = modeText()
        val keyboard = keyboard(
            row("🟢 Старт" to "cmd:start", "🔴 Стоп" to "cmd:stop"),
            row("⏸ Пауза" to "cmd:pause", "▶️ Продолжить" to "cmd:resume"),
            row("📊 Статус" to "cmd:status", "💰 Баланс" to "cmd:balance"),
            row("📈 Позиции" to "cmd:positions", "📉 Статистика" to "cmd:stats"),
            row("⚙️ Стратегии" to "cmd:strategies", "📋 Отчёт" to "cmd:report"),
            row("🔍 Сканер" to "cmd:scanner", "👀 Наблюдение" to "cmd:watch"),
            row("🎮 Режим" to "cmd:mode"),
            row("📣 Только сигнал" to "cmd:signalonly", "🛡 Защита" to "cmd:settings"),
            row("🏁 Забеги" to "cmd:runs", "🚨 Закрыть всё" to "cmd:closeall")
        )
        val signalNote = if (strategyManager.signalOnly.value) "\n📣 Режим «только сигнал»: сделки не открываются" else ""
        send(chatId, "🤖 *DRX Bot* — Панель управления\n\nСтатус: ${status.emoji} ${status.name}\nРежим: $mode$signalNote", keyboard)
    }

    private suspend fun sendHelp(chatId: Long) {
        val lines = BOT_COMMANDS.joinToString("\n") { (cmd, desc) -> "/$cmd — $desc" }
        send(chatId, "ℹ️ *Команды DRX Bot*\n\n$lines")
    }

    private suspend fun sendStatus(chatId: Long) {
        val stats = engine.getStats()
        val positions = engine.getPositions()
        val status = engine.getStatus()
        val todayTotal = stats.todayWins + stats.todayLosses
        val winRate = if (todayTotal > 0) (stats.todayWins * 100 / todayTotal).toInt() else 0
        val posStr = if (positions.isEmpty()) "Нет открытых позиций"
        else positions.joinToString("\n") { "• ${it.symbol.escapeMarkdown()}: PnL ${fmt(it.pnlUsd)} USD (${fmt(it.pnlPercent)}%)" }
        send(chatId, """
            🤖 *DRX Bot Status*

            Статус: ${status.emoji} ${status.name}
            Режим: ${modeText()}
            ⏱ Время работы: ${stats.uptime}

            📊 *Сегодня:*
            💰 P&L: ${fmt(stats.todayPnl)} USD
            ✅ Прибыльных: ${stats.todayWins}
            ❌ Убыточных: ${stats.todayLosses}
            🎯 Winrate: $winRate%

            📈 *Открытые позиции:* ${positions.size}
            $posStr
        """.trimIndent())
    }

    private suspend fun sendBalance(chatId: Long) {
        val balance = engine.getBalance()
        if (balance.isDemo) {
            send(chatId, """
                💰 *Баланс (DEMO)*

                Виртуальный счёт: *${fmt(balance.demoUsd)} USD*

                _Сброс демо-счёта — в настройках приложения._
            """.trimIndent())
        } else {
            send(chatId, """
                💰 *Баланс кошелька (REAL)*

                SOL: *${fmt(balance.sol)}* (≈ ${fmt(balance.solUsd)} USD)
            """.trimIndent())
        }
    }

    private suspend fun sendPositions(chatId: Long) {
        val positions = engine.getPositions()
        if (positions.isEmpty()) {
            send(chatId, "📈 Открытых позиций нет")
            return
        }
        val text = positions.joinToString("\n\n") {
            val exitLine = when {
                it.exitFailures >= EXIT_BLOCKED_AFTER ->
                    "\n🚫 Продажа заблокирована (${it.exitFailures} подряд): ${it.exitNote.escapeMarkdown()}"
                it.exitFailures > 0 -> "\n⚠️ Продажа не прошла (${it.exitFailures}): ${it.exitNote.escapeMarkdown()}"
                else -> ""
            }
            "📌 ${it.symbol.escapeMarkdown()} ${if (it.isDemo) "(${it.execMode.ifEmpty { "DEMO" }})" else ""}\n" +
                "🧠 ${it.strategyName.escapeMarkdown()}\n" +
                "Вход: ${fmt(it.entryPrice)} → Сейчас: ${fmt(it.currentPrice)}\n" +
                "🕒 ${tradeTimeLine(it.openedAt)}\n" +
                "PnL: ${fmt(it.pnlUsd)} USD (${fmt(it.pnlPercent)}%)\n" +
                "SL: ${fmt(it.stopLoss)} | TP: ${fmt(it.takeProfit)}" + exitLine
        }
        // По кнопке на каждую позицию — закрытие вручную; внизу общий «Закрыть всё».
        val closeRows = positions.map { p -> row("🔴 Закрыть ${p.symbol}" to "close:${p.tradeId}") }.toTypedArray()
        val keyboard = keyboard(*closeRows, row("🚨 Закрыть всё" to "cmd:closeall", "↩️ Меню" to "cmd:menu"))
        send(chatId, "📈 *Открытые позиции:*\n\n$text", keyboard)
    }

    /** `/close СИМВОЛ` (или id сделки). Без аргумента — показываем позиции с кнопками. */
    private suspend fun handleCloseCommand(chatId: Long, text: String) {
        val arg = text.trim().substringAfter(' ', "").trim()
        if (arg.isEmpty()) { sendPositions(chatId); return }
        val matches = engine.getPositions().filter {
            it.symbol.equals(arg, ignoreCase = true) || it.tradeId == arg
        }
        when {
            matches.isEmpty() -> send(chatId, "❓ Открытая позиция «${arg.escapeMarkdown()}» не найдена. /positions — список.")
            matches.size > 1 -> send(chatId, "⚠️ Несколько открытых позиций «${arg.escapeMarkdown()}» — закройте кнопкой в /positions.")
            else -> closeOne(chatId, matches.first().tradeId)
        }
    }

    private suspend fun handleCloseCallback(chatId: Long, tradeId: String) {
        closeOne(chatId, tradeId)
        sendPositions(chatId) // обновляем список после закрытия
    }

    private suspend fun closeOne(chatId: Long, tradeId: String) {
        val result = engine.closePosition(tradeId)
        val sym = result.symbol?.escapeMarkdown() ?: "позиция"
        send(chatId, when (result.status) {
            BotEngine.ClosePositionStatus.CLOSED -> "✅ $sym закрыта вручную. PnL ${fmt(result.pnlUsd)} USD"
            BotEngine.ClosePositionStatus.NOT_FOUND -> "❓ Позиция не найдена (возможно, уже закрыта)."
            BotEngine.ClosePositionStatus.NOT_OPEN -> "ℹ️ $sym уже закрыта."
            BotEngine.ClosePositionStatus.FAILED -> "❌ Не удалось закрыть $sym — проверьте вручную."
        })
    }

    private suspend fun sendStats(chatId: Long) {
        val stats = engine.getStats()
        val winRate = if (stats.totalTrades > 0) (stats.wins * 100 / stats.totalTrades).toInt() else 0
        send(chatId, """
            📉 *Статистика DRX*

            📊 Всего сделок: ${stats.totalTrades}
            ✅ Прибыльных: ${stats.wins}
            ❌ Убыточных: ${stats.losses}
            🎯 Winrate: $winRate%
            💰 Общий P&L: ${fmt(stats.totalPnl)} USD
            💸 Комиссии: ${fmt(stats.totalFees)} USD
        """.trimIndent())
    }

    private suspend fun sendStrategiesMenu(chatId: Long) {
        val strategies = db.strategyQueries.getAll().executeAsList()
        if (strategies.isEmpty()) {
            send(chatId, "⚙️ Стратегий нет. Добавьте через приложение.")
            return
        }
        val keyboard = keyboard(
            *strategies.map { s ->
                row("${if (s.is_active != 0L) "✅" else "⭕"} ${s.name}" to "strategy:toggle:${s.id}")
            }.toTypedArray(),
            row("↩️ Назад" to "cmd:menu")
        )
        send(chatId, "⚙️ *Управление стратегиями*\nАктивных: ${strategies.count { it.is_active != 0L }}", keyboard)
    }

    private suspend fun sendDailyReport(chatId: Long) {
        val todayStart = startOfDayMillis()
        val report = db.reportQueries.getDailyReport(todayStart).executeAsOne()
        val winTrades = report.win_trades ?: 0L
        val lossTrades = report.loss_trades ?: 0L
        val total = winTrades + lossTrades
        val winRate = if (total > 0L) (winTrades * 100 / total).toInt() else 0
        val avgWin = report.avg_win
        val avgLoss = report.avg_loss
        val pf = if (avgLoss != 0.0) avgWin / (-avgLoss) else 0.0
        send(chatId, """
            📋 *Ежедневный отчёт DRX*

            💰 *Финансы:*
            P&L: ${fmt(report.total_pnl)} USD
            Комиссии: ${fmt(report.total_fees)} USD

            📊 *Торговля:*
            Всего сделок: ${report.total_trades}
            Прибыльных: ✅ $winTrades
            Убыточных: ❌ $lossTrades
            Winrate: $winRate%
            Avg прибыль: ${fmt(avgWin)} USD
            Avg убыток: ${fmt(avgLoss)} USD
            Profit Factor: ${fmt(pf)}

            🏆 Лучшая сделка: ${if (report.best_trade > 0) "+" else ""}${fmt(report.best_trade)} USD
            💩 Худшая сделка: ${fmt(report.worst_trade)} USD
        """.trimIndent())
    }

    /** 🔍 Текущие кандидаты сканера (DEX-специфика, аналога в MRX нет). */
    private suspend fun sendScanner(chatId: Long) {
        val candidates = tokenCache.all().take(10)
        if (candidates.isEmpty()) {
            send(chatId, "🔍 Кандидатов в кеше нет — сканер ещё не отработал или всё отфильтровано.")
            return
        }
        val text = candidates.joinToString("\n\n") { c ->
            "🪙 *${c.symbol.escapeMarkdown()}* — score ${c.score.toInt()}\n" +
                "💵 ${fmt(c.priceUsd)} | MC ${fmtShort(c.marketCap)} | LIQ ${fmtShort(c.liquidityUsd)}\n" +
                "⏱ возраст ${c.tokenAgeMinutes}м | 1ч: ${fmt(c.priceChangeH1)}%"
        }
        send(chatId, "🔍 *Кандидаты сканера:*\n\n$text")
    }

    /** 👀 Доска наблюдения: монеты, где сетап собран хотя бы наполовину, — что сошлось и чего не хватает. */
    private suspend fun sendWatchBoard(chatId: Long) {
        val items = watchBoard?.items?.value.orEmpty().filter { it.status != WatchStatus.NONE }
        if (items.isEmpty()) {
            val why = if (engine.getStatus() == BotStatus.RUNNING) "сетапы пока не складываются" else "бот не запущен — стратегии не сканируют"
            send(chatId, "👀 На доске наблюдения пусто: $why.")
            return
        }
        val text = items.take(10).joinToString("\n\n") { w ->
            val icon = when (w.status) {
                WatchStatus.READY -> "🟢"
                WatchStatus.NEAR -> "🟡"
                else -> "⚪"
            }
            val distance = w.distancePct?.let { " · до уровня ${if (it > 0) "+" else ""}${fmt(it)}%" } ?: ""
            "$icon *${w.status.label}* ${w.symbol.escapeMarkdown()} — ${w.strategyName.escapeMarkdown()} (${w.timeframe})\n" +
                "Условий ${w.passed}/${w.total}$distance" +
                (w.missing?.let { "\nНе хватает: ${it.escapeMarkdown()}" } ?: "")
        }
        send(chatId, "👀 *Доска наблюдения*\n\n$text")
    }

    // ─── Режим DEMO/REAL ─────────────────────────────────────────────────────

    private suspend fun sendModeMenu(chatId: Long) {
        val demo = executor.isDemo()
        val shadow = executor.isShadow()
        val seedConfigured = settingsStore.getWalletSeed() != null
        val rows = mutableListOf(
            row((if (demo) "💸 Включить REAL" else "🎮 Включить DEMO") to (if (demo) "mode:real" else "mode:demo")),
        )
        if (demo) rows += row((if (shadow) "⭕ Выключить SHADOW" else "🔁 Включить SHADOW") to "mode:shadow")
        rows += row("↩️ Назад" to "cmd:menu")
        send(chatId, """
            🎮 *Режим торговли*

            Текущий: ${modeText()}
            Кошелёк: ${if (seedConfigured) "🔑 настроен" else "❌ seed-фраза не задана"}

            DEMO — сделки виртуальные, баланс $10 000.
            SHADOW — DEMO по реальным котировкам Jupiter: вход и выход с комиссиями пулов и проскальзыванием, транзакции не отправляются.
            REAL — реальные свопы через Jupiter с вашего кошелька. ⚠️ Включайте только когда уверены!
        """.trimIndent(), keyboard(*rows.toTypedArray()))
    }

    private suspend fun toggleShadow(chatId: Long) {
        if (!executor.isDemo()) {
            send(chatId, "ℹ️ SHADOW работает только в DEMO — сначала включите DEMO.")
            return
        }
        val on = !executor.isShadow()
        settingsStore.setShadowQuotes(on)
        send(
            chatId,
            if (on) "🔁 SHADOW включён — DEMO-сделки исполняются по котировкам Jupiter."
            else "🎮 SHADOW выключен — DEMO снова по цене графика."
        )
        sendModeMenu(chatId)
    }

    private suspend fun setMode(chatId: Long, demo: Boolean) {
        if (!demo && settingsStore.getWalletSeed() == null) {
            // `_` в имени переменной без экранирования ломал Markdown — Telegram молча отклонял
            // сообщение, и предупреждение не доходило.
            send(chatId, "⚠️ REAL-режим не включён: не задана seed-фраза кошелька (настройки приложения или ${"SOLANA_WALLET_SEED".escapeMarkdown()}).")
            return
        }
        settingsStore.setDemoMode(demo)
        send(
            chatId,
            if (demo) "🎮 Режим DEMO включён — сделки виртуальные."
            else "💸 *Режим REAL включён!* Следующие сделки — реальные свопы через Jupiter. ⚠️"
        )
    }

    private suspend fun handleModeCallback(chatId: Long, data: String) {
        when (data.removePrefix("mode:")) {
            "demo" -> setMode(chatId, demo = true)
            "real" -> setMode(chatId, demo = false)
            "shadow" -> toggleShadow(chatId)
        }
    }

    // ─── Только сигнал ───────────────────────────────────────────────────────

    private suspend fun sendSignalOnlyMenu(chatId: Long) {
        val on = strategyManager.signalOnly.value
        val keyboard = keyboard(
            row((if (on) "⭕ Выключить" else "✅ Включить") to "signal:toggle"),
            row("↩️ Назад" to "cmd:menu")
        )
        send(chatId, """
            📣 *Режим «только сигнал»*

            Статус: ${if (on) "✅ ВКЛ" else "⭕ ВЫКЛ"}

            При включении бот НЕ открывает сделки, а лишь шлёт сигнал с параметрами входа (цена, SL, TP, mint).
        """.trimIndent(), keyboard)
    }

    private suspend fun handleSignalCallback(chatId: Long, data: String) {
        if (data.removePrefix("signal:") == "toggle") {
            val value = !strategyManager.signalOnly.value
            strategyManager.setSignalOnly(value)
            send(chatId, if (value) "📣 Режим «только сигнал» ВКЛЮЧЁН — сделки не открываются" else "✅ Режим «только сигнал» ВЫКЛЮЧЕН — бот снова торгует")
            sendSignalOnlyMenu(chatId)
        }
    }

    // ─── Защита и автоматика ─────────────────────────────────────────────────

    /**
     * Переключатели защиты и автоматики (общие для всех стратегий). Числа лимитов — только показываем:
     * их удобнее править в настройках приложения, а неверное число из чата легко не заметить.
     */
    private suspend fun sendSettingsMenu(chatId: Long) {
        val p = settingsStore.getProtection()
        val resume = settingsStore.getResumeOnLaunch()
        fun on(v: Boolean) = if (v) "✅" else "⭕"
        fun limit(v: Double, unit: String = "%") = if (v > 0) "${fmt(v)}$unit" else "выкл"
        val keyboard = keyboard(
            row("${on(p.guardEnabled)} Проверка монеты" to "set:guard", "${on(p.portfolioEnabled)} Лимиты портфеля" to "set:portfolio"),
            row("${on(p.autoStopOnLimit)} Стоп бота при лимите убытка" to "set:autostop"),
            row("${on(p.reversalExitEnabled)} Выход по развороту" to "set:revexit"),
            row("${on(resume)} Возобновлять при запуске приложения" to "set:resume"),
            row("↩️ Меню" to "cmd:menu")
        )
        send(chatId, """
            🛡 *Защита и автоматика* (все стратегии)

            ${on(p.guardEnabled)} Проверка монеты перед покупкой — влияние на цену ≤ ${limit(p.maxPriceImpactPct)}, потери туда-обратно ≤ ${limit(p.maxRoundTripLossPct)}
            ${on(p.portfolioEnabled)} Лимиты портфеля — позиций ≤ ${if (p.maxOpenPositions > 0) p.maxOpenPositions else "∞"}, в позициях ≤ ${limit(p.maxExposurePct)}, убыток день/7 дней ${limit(p.maxDailyLossPct)} / ${limit(p.maxWeeklyLossPct)}, просадка от пика ${limit(p.maxDrawdownPct)}
            ${on(p.autoStopOnLimit)} Остановка бота при лимите убытка — вместо паузы входов до завтра
            ${on(p.reversalExitEnabled)} Выход по развороту — продать, не дожидаясь стопа, когда EMA и структура развернулись вниз
            ${on(resume)} Возобновлять торговлю при запуске приложения (сервер возобновляет всегда)

            Числа лимитов — в настройках приложения.
        """.trimIndent(), keyboard)
    }

    private suspend fun handleSettingsCallback(chatId: Long, key: String) {
        val reply = toggleSetting(settingsStore, key) ?: return
        send(chatId, reply)
        sendSettingsMenu(chatId)
    }

    // ─── Забеги («Новый старт») ──────────────────────────────────────────────

    private suspend fun sendRuns(chatId: Long) {
        val demo = executor.isDemo()
        val runs = runSummaries(db, demo).asReversed()
        val mode = if (demo) "DEMO" else "REAL"
        val keyboard = keyboard(row("🏁 Новый старт" to "cmd:reset", "↩️ Меню" to "cmd:menu"))
        if (runs.isEmpty()) {
            send(chatId, "🏁 *Забеги ($mode)*\n\n«Нового старта» ещё не было — статистика и лимиты считаются за всю историю режима.", keyboard)
            return
        }
        val text = runs.take(10).joinToString("\n\n") { r ->
            val strategies = if (r.strategies.isEmpty()) "" else "\n🧠 ${r.strategies.take(3).joinToString(", ").escapeMarkdown()}"
            val note = if (r.note.isBlank()) "" else "\n📝 ${r.note.escapeMarkdown()}"
            "${if (r.current) "▶️" else "🏁"} ${r.periodLabel()}\n${r.resultLabel()}$strategies$note"
        }
        send(chatId, "🏁 *Забеги ($mode)*\n\n$text", keyboard)
    }

    /** Подтверждение «Нового старта»: сбрасывает итоги и лимиты, поэтому — второй кнопкой. [arg] — новый демо-счёт. */
    private suspend fun sendResetConfirm(chatId: Long, arg: String) {
        if (riskManager == null) {
            send(chatId, "ℹ️ «Новый старт» доступен в приложении: Настройки → 🏁 Новый старт.")
            return
        }
        val demo = executor.isDemo()
        val amount = arg.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        if (arg.isNotBlank() && amount == null) {
            send(chatId, "❓ Не понял сумму «${arg.escapeMarkdown()}». Пример: /reset 10000")
            return
        }
        val balanceNote = when {
            amount == null -> ""
            demo -> "\nДемо-счёт будет выставлен в ${fmt(amount)} USD (открытые позиции остаются)."
            else -> "\nСумма не применяется: в REAL баланс — это кошелёк."
        }
        val data = if (demo && amount != null) "run:new:${amount.toLong()}" else "run:new"
        send(chatId, """
            🏁 *Новый старт* (${if (demo) "DEMO" else "REAL"})

            Статистика, отчёты и лимиты убытка (день, неделя, просадка, серия убытков) начнут считаться с этого момента. Сделки не удаляются — итоги прошлых забегов останутся в /runs.$balanceNote
        """.trimIndent(), keyboard(row("✅ Начать" to data, "↩️ Отмена" to "cmd:menu")))
    }

    private suspend fun handleRunCallback(chatId: Long, data: String) {
        val risk = riskManager ?: return
        val parts = data.split(":")
        if (parts.firstOrNull() != "new") return
        val balance = parts.getOrNull(1)?.toDoubleOrNull()
        send(chatId, beginNewRun(db, executor, risk, balance, Clock.System.now().toEpochMilliseconds()))
    }

    // ─── Стратегии ───────────────────────────────────────────────────────────

    private suspend fun handleStrategyCallback(chatId: Long, data: String) {
        val parts = data.split(":")
        if (parts.size != 3 || parts[1] != "toggle") return
        val id = parts[2]
        val s = db.strategyQueries.getById(id).executeAsOneOrNull() ?: return
        val newActive = if (s.is_active == 0L) 1L else 0L
        db.strategyQueries.updateActive(newActive, Clock.System.now().toEpochMilliseconds(), id)
        val toggle = engine.applyStrategyActive(id, newActive == 1L)
        val note = when (toggle) {
            BotEngine.StrategyToggle.STARTED -> "и запущена прямо сейчас"
            BotEngine.StrategyToggle.STOPPED -> "и остановлена прямо сейчас"
            BotEngine.StrategyToggle.ALREADY_RUNNING -> "(уже была запущена)"
            BotEngine.StrategyToggle.PENDING_START -> "— применится при /start"
            BotEngine.StrategyToggle.PENDING_STOP -> ""
            BotEngine.StrategyToggle.LIMIT_REACHED -> "⚠️ но лимит одновременных стратегий достигнут"
        }
        send(chatId, "${if (newActive == 1L) "✅ Включена" else "⭕ Выключена"} «${s.name.escapeMarkdown()}» $note")
        sendStrategiesMenu(chatId)
    }

    // ─── Утилиты ─────────────────────────────────────────────────────────────

    private fun row(vararg buttons: Pair<String, String>): JsonArray = buildJsonArray {
        buttons.forEach { (label, data) ->
            add(buildJsonObject {
                put("text", label)
                put("callback_data", data)
            })
        }
    }

    private fun keyboard(vararg rows: JsonArray) = buildJsonObject {
        put("inline_keyboard", buildJsonArray { rows.forEach { add(it) } })
    }

    private suspend fun send(chatId: Long, text: String, keyboard: JsonObject? = null) {
        runCatching {
            client.post("$apiUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "Markdown")
                    keyboard?.let { put("reply_markup", it) }
                })
            }
        }
    }

    private fun startOfDayMillis(): Long {
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        return today.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    }

    /** Локале-независимое форматирование (точка, без хвостовых нулей, до 4 знаков). */
    private fun fmt(v: Double): String = formatNumber(v, 4)

    /** $1.2K / $3.4M — короткий формат для MC/LIQ. */
    private fun fmtShort(v: Double): String = "$" + formatLargeNumber(v)

    fun close() = client.close()
}
