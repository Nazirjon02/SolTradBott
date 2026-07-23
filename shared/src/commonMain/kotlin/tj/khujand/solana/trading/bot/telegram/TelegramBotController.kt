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
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
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
    "strategies" to "Управление стратегиями",
    "mode" to "Режим DEMO / REAL",
    "signalonly" to "Режим «только сигнал»",
    "pause" to "Пауза",
    "resume" to "Продолжить",
    "stop" to "Остановить бота",
    "closeall" to "Закрыть все позиции",
    "help" to "Помощь по командам",
)

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
                    processUpdate(update)
                    offset = update.updateId + 1
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
            val data = cb.data ?: ""
            when {
                data.startsWith("cmd:") -> handleCmd(chatId, data.removePrefix("cmd:"))
                data.startsWith("close:") -> handleCloseCallback(chatId, data.removePrefix("close:"))
                data.startsWith("strategy:") -> handleStrategyCallback(chatId, data)
                data.startsWith("mode:") -> handleModeCallback(chatId, data)
                data.startsWith("signal:") -> handleSignalCallback(chatId, data)
            }
            runCatching {
                client.post("$apiUrl/answerCallbackQuery") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("callback_query_id", cb.id) })
                }
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
            else -> runAction(chatId, cmd)
        }
    }

    /** Кнопки `cmd:*`: «Старт» запускает движок, «Меню» открывает панель, остальное — общие действия. */
    private suspend fun handleCmd(chatId: Long, action: String) {
        when (action) {
            "start" -> { engine.start(); send(chatId, "🟢 Бот запущен") }
            "menu" -> sendMainMenu(chatId)
            else -> runAction(chatId, action)
        }
    }

    /** Действия, общие для текстовых команд и кнопок — единый источник поведения. */
    private suspend fun runAction(chatId: Long, action: String) {
        when (action) {
            "stop" -> { engine.stop(); send(chatId, "🔴 Бот остановлен") }
            "pause" -> { engine.pause(); send(chatId, "⏸ Бот на паузе") }
            "resume" -> { engine.resume(); send(chatId, "▶️ Бот возобновлён") }
            "closeall" -> { engine.closeAllPositions(); send(chatId, "🚨 Команда на закрытие всех позиций отправлена") }
            "status" -> sendStatus(chatId)
            "balance" -> sendBalance(chatId)
            "positions" -> sendPositions(chatId)
            "stats" -> sendStats(chatId)
            "strategies" -> sendStrategiesMenu(chatId)
            "report" -> sendDailyReport(chatId)
            "scanner" -> sendScanner(chatId)
            "mode" -> sendModeMenu(chatId)
            "signalonly" -> sendSignalOnlyMenu(chatId)
            // Неизвестные команды игнорируем — не засоряем чат ответами об ошибке.
        }
    }

    // ─── Меню ────────────────────────────────────────────────────────────────

    private suspend fun sendMainMenu(chatId: Long) {
        val status = engine.getStatus()
        val mode = if (executor.isDemo()) "🎮 DEMO" else "💸 REAL"
        val keyboard = keyboard(
            row("🟢 Старт" to "cmd:start", "🔴 Стоп" to "cmd:stop"),
            row("⏸ Пауза" to "cmd:pause", "▶️ Продолжить" to "cmd:resume"),
            row("📊 Статус" to "cmd:status", "💰 Баланс" to "cmd:balance"),
            row("📈 Позиции" to "cmd:positions", "📉 Статистика" to "cmd:stats"),
            row("⚙️ Стратегии" to "cmd:strategies", "📋 Отчёт" to "cmd:report"),
            row("🔍 Сканер" to "cmd:scanner", "🎮 Режим" to "cmd:mode"),
            row("📣 Только сигнал" to "cmd:signalonly", "🚨 Закрыть всё" to "cmd:closeall")
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
            Режим: ${if (executor.isDemo()) "🎮 DEMO" else "💸 REAL"}
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
            "📌 ${it.symbol.escapeMarkdown()} ${if (it.isDemo) "(DEMO)" else ""}\n" +
                "🧠 ${it.strategyName.escapeMarkdown()}\n" +
                "Вход: ${fmt(it.entryPrice)} → Сейчас: ${fmt(it.currentPrice)}\n" +
                "🕒 ${tradeTimeLine(it.openedAt)}\n" +
                "PnL: ${fmt(it.pnlUsd)} USD (${fmt(it.pnlPercent)}%)\n" +
                "SL: ${fmt(it.stopLoss)} | TP: ${fmt(it.takeProfit)}"
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
        val avgWin = report.avg_win ?: 0.0
        val avgLoss = report.avg_loss ?: 0.0
        val pf = if (avgLoss != 0.0) avgWin / (-avgLoss) else 0.0
        send(chatId, """
            📋 *Ежедневный отчёт DRX*

            💰 *Финансы:*
            P&L: ${fmt(report.total_pnl ?: 0.0)} USD
            Комиссии: ${fmt(report.total_fees ?: 0.0)} USD

            📊 *Торговля:*
            Всего сделок: ${report.total_trades}
            Прибыльных: ✅ $winTrades
            Убыточных: ❌ $lossTrades
            Winrate: $winRate%
            Avg прибыль: ${fmt(avgWin)} USD
            Avg убыток: ${fmt(avgLoss)} USD
            Profit Factor: ${fmt(pf)}

            🏆 Лучшая сделка: +${fmt(report.best_trade ?: 0.0)} USD
            💩 Худшая сделка: ${fmt(report.worst_trade ?: 0.0)} USD
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

    // ─── Режим DEMO/REAL ─────────────────────────────────────────────────────

    private suspend fun sendModeMenu(chatId: Long) {
        val demo = executor.isDemo()
        val seedConfigured = settingsStore.getWalletSeed() != null
        val keyboard = keyboard(
            row((if (demo) "💸 Включить REAL" else "🎮 Включить DEMO") to (if (demo) "mode:real" else "mode:demo")),
            row("↩️ Назад" to "cmd:menu")
        )
        send(chatId, """
            🎮 *Режим торговли*

            Текущий: ${if (demo) "🎮 DEMO (виртуальный счёт)" else "💸 REAL (Jupiter-свопы)"}
            Кошелёк: ${if (seedConfigured) "🔑 настроен" else "❌ seed-фраза не задана"}

            DEMO — сделки виртуальные, баланс $10 000.
            REAL — реальные свопы через Jupiter с вашего кошелька. ⚠️ Включайте только когда уверены!
        """.trimIndent(), keyboard)
    }

    private suspend fun setMode(chatId: Long, demo: Boolean) {
        if (!demo && settingsStore.getWalletSeed() == null) {
            send(chatId, "⚠️ REAL-режим не включён: не задана seed-фраза кошелька (настройки приложения или SOLANA_WALLET_SEED).")
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
