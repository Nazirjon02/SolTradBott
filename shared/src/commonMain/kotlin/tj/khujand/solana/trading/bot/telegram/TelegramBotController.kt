package tj.khujand.solana.trading.bot.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tj.khujand.solana.trading.bot.core.engine.BotEngine
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache

private val BotStatus.emoji: String
    get() = when (this) {
        BotStatus.RUNNING -> "🟢"
        BotStatus.PAUSED -> "⏸"
        BotStatus.STOPPED -> "🔴"
    }

/**
 * Telegram-бот управления (порт TelegramBotController из MRX):
 * long-polling, inline-меню, доступ только с allowedChatId.
 * DEX-специфика: кнопки «🔍 Сканер» и «🎮 Режим» (DEMO/REAL).
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
    }

    suspend fun startPolling() {
        var offset = 0L
        while (true) {
            try {
                val response = client.get("$apiUrl/getUpdates") {
                    parameter("offset", offset)
                    parameter("timeout", 30)
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

    private suspend fun processUpdate(update: TgUpdate) {
        val chatId = update.message?.chat?.id
            ?: update.callbackQuery?.message?.chat?.id
            ?: return

        if (chatId != allowedChatId) {
            send(chatId, "⛔ Доступ запрещён")
            return
        }

        val text = update.message?.text ?: ""
        val callbackData = update.callbackQuery?.data ?: ""

        when {
            text == "/start" || text == "/menu" -> sendMainMenu(chatId)
            text == "/status" -> sendStatus(chatId)
            text == "/stop" -> { engine.stop(); send(chatId, "🔴 Бот остановлен") }
            text == "/pause" -> { engine.pause(); send(chatId, "⏸ Бот на паузе") }
            text == "/resume" -> { engine.resume(); send(chatId, "▶️ Бот возобновлён") }
            text == "/stats" -> sendStats(chatId)
            text == "/positions" -> sendPositions(chatId)
            text == "/balance" -> sendBalance(chatId)
            text == "/closeall" -> { engine.closeAllPositions(); send(chatId, "🚨 Команда на закрытие всех позиций отправлена") }
            text == "/strategies" -> sendStrategiesMenu(chatId)
            text == "/report" -> sendDailyReport(chatId)
            text == "/scanner" -> sendScanner(chatId)
            text == "/mode" -> sendModeMenu(chatId)
            text == "/demo_on" -> setMode(chatId, demo = true)
            text == "/real_on" -> setMode(chatId, demo = false)
            text == "/signalonly" -> sendSignalOnlyMenu(chatId)
            text == "/signalonly_on" -> setSignalOnly(chatId, true)
            text == "/signalonly_off" -> setSignalOnly(chatId, false)
            callbackData.startsWith("cmd:") -> handleCallback(chatId, callbackData)
            callbackData.startsWith("strategy:") -> handleStrategyCallback(chatId, callbackData)
            callbackData.startsWith("mode:") -> handleModeCallback(chatId, callbackData)
            callbackData.startsWith("signal:") -> handleSignalCallback(chatId, callbackData)
        }

        if (callbackData.isNotEmpty()) {
            runCatching {
                client.post("$apiUrl/answerCallbackQuery") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("callback_query_id", update.callbackQuery!!.id) })
                }
            }
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

    private suspend fun sendStatus(chatId: Long) {
        val stats = engine.getStats()
        val positions = engine.getPositions()
        val status = engine.getStatus()
        val todayTotal = stats.todayWins + stats.todayLosses
        val winRate = if (todayTotal > 0) (stats.todayWins * 100 / todayTotal).toInt() else 0
        val posStr = if (positions.isEmpty()) "Нет открытых позиций"
        else positions.joinToString("\n") { "• ${it.symbol}: PnL ${fmt(it.pnlUsd)} USD (${fmt(it.pnlPercent)}%)" }
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
            "📌 ${it.symbol} ${if (it.isDemo) "(DEMO)" else ""}\n" +
                "🧠 ${it.strategyName}\n" +
                "Вход: ${fmt(it.entryPrice)} → Сейчас: ${fmt(it.currentPrice)}\n" +
                "PnL: ${fmt(it.pnlUsd)} USD (${fmt(it.pnlPercent)}%)\n" +
                "SL: ${fmt(it.stopLoss)} | TP: ${fmt(it.takeProfit)}"
        }
        send(chatId, "📈 *Открытые позиции:*\n\n$text")
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
            "🪙 *${c.symbol}* — score ${c.score.toInt()}\n" +
                "💵 ${fmt(c.priceUsd)} | MC ${fmtShort(c.marketCap)} | LIQ ${fmtShort(c.liquidityUsd)}\n" +
                "⏱ возраст ${c.tokenAgeMinutes}м | 1ч: ${fmt(c.priceChangeH1)}%" +
                (c.rugScore?.let { "\n🛡 RugCheck: $it" } ?: "")
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

    private suspend fun setSignalOnly(chatId: Long, value: Boolean) {
        strategyManager.setSignalOnly(value)
        send(chatId, if (value) "📣 Режим «только сигнал» ВКЛЮЧЁН — сделки не открываются" else "✅ Режим «только сигнал» ВЫКЛЮЧЕН — бот снова торгует")
    }

    private suspend fun handleSignalCallback(chatId: Long, data: String) {
        if (data.removePrefix("signal:") == "toggle") {
            setSignalOnly(chatId, !strategyManager.signalOnly.value)
        }
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

    private suspend fun handleCallback(chatId: Long, data: String) {
        when (data.removePrefix("cmd:")) {
            "start" -> { engine.start(); send(chatId, "🟢 Бот запущен") }
            "stop" -> { engine.stop(); send(chatId, "🔴 Бот остановлен") }
            "pause" -> { engine.pause(); send(chatId, "⏸ Бот на паузе") }
            "resume" -> { engine.resume() }
            "status" -> sendStatus(chatId)
            "balance" -> sendBalance(chatId)
            "positions" -> sendPositions(chatId)
            "stats" -> sendStats(chatId)
            "strategies" -> sendStrategiesMenu(chatId)
            "report" -> sendDailyReport(chatId)
            "scanner" -> sendScanner(chatId)
            "mode" -> sendModeMenu(chatId)
            "signalonly" -> sendSignalOnlyMenu(chatId)
            "closeall" -> { engine.closeAllPositions() }
            "menu" -> sendMainMenu(chatId)
        }
    }

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
        send(chatId, "${if (newActive == 1L) "✅ Включена" else "⭕ Выключена"} «${s.name}» $note")
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

    private suspend fun send(chatId: Long, text: String, keyboard: kotlinx.serialization.json.JsonObject? = null) {
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

    private fun fmt(v: Double): String {
        var factor = 1L
        repeat(4) { factor *= 10 }
        val scaled = (v * factor).roundToLong()
        val intPart = scaled / factor
        val frac = (if (scaled < 0) -scaled else scaled) % factor
        if (frac == 0L) return intPart.toString()
        val fracStr = frac.toString().padStart(4, '0').trimEnd('0')
        return "$intPart.$fracStr"
    }

    /** $1.2K / $3.4M — короткий формат для MC/LIQ. */
    private fun fmtShort(v: Double): String = when {
        v >= 1_000_000 -> "$${fmt(v / 1_000_000)}M"
        v >= 1_000 -> "$${fmt(v / 1_000)}K"
        else -> "$${fmt(v)}"
    }

    fun close() = client.close()
}
