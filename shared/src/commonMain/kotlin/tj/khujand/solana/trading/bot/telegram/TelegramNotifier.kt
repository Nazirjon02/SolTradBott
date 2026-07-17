package tj.khujand.solana.trading.bot.telegram

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.TradeNotifier
import tj.khujand.solana.trading.bot.util.formatNumber
import tj.khujand.solana.trading.bot.util.tradeTimeLine

/**
 * Отправка алертов о сделках в Telegram (порт из MRX).
 * Реализует TradeNotifier — ядро не знает про Telegram напрямую.
 */
class TelegramNotifier(
    botToken: String,
    chatId: Long
) : TradeNotifier {
    // Мутабельные, чтобы смена токена/чата в настройках применялась без пересоздания движка.
    private var botToken: String = botToken
    private var chatId: Long = chatId

    private val apiUrl get() = "https://api.telegram.org/bot$botToken"
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    /** Применяет новые токен/чат на лету (вызывается при сохранении настроек). */
    fun updateCredentials(token: String, chat: Long) {
        botToken = token
        chatId = chat
    }

    /**
     * Обычные уведомления (статус движка, сигналы, ошибки) шлём БЕЗ Markdown:
     * в них попадают сырые символы монет и тексты причин, а спецсимвол вроде `_`
     * сломал бы Markdown-парсер, и Telegram молча отклонил бы сообщение.
     * Форматированные алерты о сделках идут через [sendMarkdown] с экранированием.
     */
    override suspend fun send(text: String) = post(text, markdown = false)

    private suspend fun sendMarkdown(text: String) = post(text, markdown = true)

    private suspend fun post(text: String, markdown: Boolean) {
        if (botToken.isBlank() || chatId == 0L) return
        runCatching {
            client.post("$apiUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text)
                    if (markdown) put("parse_mode", "Markdown")
                })
            }
        }
    }

    override suspend fun sendOpenAlert(
        symbol: String,
        strategyName: String,
        entryPrice: Double,
        sizeUsd: Double,
        stopLoss: Double,
        takeProfit: Double,
        isDemo: Boolean,
        reason: String,
    ) {
        val mode = if (isDemo) " _(DEMO)_" else ""
        val risk = abs(entryPrice - stopLoss)
        val reward = abs(takeProfit - entryPrice)
        val rr = if (risk > 0) reward / risk else 0.0
        sendMarkdown(
            """
            🟢 *Вход LONG — ${symbol.escapeMarkdown()}*$mode

            🧠 Стратегия: ${strategyName.escapeMarkdown()}
            💵 Цена входа: ${fmt(entryPrice)}
            💰 Размер: ${fmt(sizeUsd)} USD
            🛑 Stop Loss: ${fmt(stopLoss)}
            🎯 Take Profit: ${fmt(takeProfit)}
            ⚖️ R:R ≈ ${fmt(rr)}
            🕒 ${tradeTimeLine(nowMs())}
            💡 ${reason.escapeMarkdown()}
            """.trimIndent()
        )
    }

    override suspend fun sendCloseAlert(
        symbol: String,
        strategyName: String,
        entryPrice: Double,
        exitPrice: Double,
        pnlUsd: Double,
        pnlPercent: Double,
        reason: String,
        isDemo: Boolean,
    ) {
        val emoji = if (pnlUsd > 0) "✅" else "❌"
        val pnlEmoji = if (pnlUsd > 0) "💰" else "💸"
        val mode = if (isDemo) " _(DEMO)_" else ""
        sendMarkdown(
            """
            $emoji *Закрыта позиция*$mode

            📌 ${symbol.escapeMarkdown()} | ${strategyName.escapeMarkdown()}
            📥 Вход: ${fmt(entryPrice)}
            📤 Выход: ${fmt(exitPrice)}
            $pnlEmoji P&L: ${fmt(pnlUsd)} USD (${fmt(pnlPercent)}%)
            🕒 ${tradeTimeLine(nowMs())}
            📝 Причина: ${reason.escapeMarkdown()}
            """.trimIndent()
        )
    }

    fun close() = client.close()

    /** Локале-независимое форматирование (точка, без хвостовых нулей, до 6 знаков). */
    private fun fmt(v: Double): String = formatNumber(v, 6)

    /** Момент отправки алерта = момент входа/выхода (пишется той же now(), что и в БД). */
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
