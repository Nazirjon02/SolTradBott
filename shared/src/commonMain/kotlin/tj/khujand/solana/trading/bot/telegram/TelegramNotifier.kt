package tj.khujand.solana.trading.bot.telegram

import io.ktor.client.HttpClient
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
import kotlin.math.roundToLong
import tj.khujand.solana.trading.bot.core.TradeNotifier

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
    }

    /** Применяет новые токен/чат на лету (вызывается при сохранении настроек). */
    fun updateCredentials(token: String, chat: Long) {
        botToken = token
        chatId = chat
    }

    override suspend fun send(text: String) {
        if (botToken.isBlank() || chatId == 0L) return
        runCatching {
            client.post("$apiUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "Markdown")
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
        send(
            """
            🟢 *Вход LONG — $symbol*$mode

            🧠 Стратегия: $strategyName
            💵 Цена входа: ${fmt(entryPrice)}
            💰 Размер: ${fmt(sizeUsd)} USD
            🛑 Stop Loss: ${fmt(stopLoss)}
            🎯 Take Profit: ${fmt(takeProfit)}
            ⚖️ R:R ≈ ${fmt(rr)}
            💡 $reason
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
        send(
            """
            $emoji *Закрыта позиция*$mode

            📌 $symbol | $strategyName
            📥 Вход: ${fmt(entryPrice)}
            📤 Выход: ${fmt(exitPrice)}
            $pnlEmoji P&L: ${fmt(pnlUsd)} USD (${fmt(pnlPercent)}%)
            📝 Причина: $reason
            """.trimIndent()
        )
    }

    fun close() = client.close()

    /** Локале-независимое форматирование числа (точка, без хвостовых нулей). */
    private fun fmt(v: Double): String {
        var factor = 1L
        repeat(6) { factor *= 10 }
        val scaled = (v * factor).roundToLong()
        val intPart = scaled / factor
        val frac = (if (scaled < 0) -scaled else scaled) % factor
        if (frac == 0L) return intPart.toString()
        val fracStr = frac.toString().padStart(6, '0').trimEnd('0')
        return "$intPart.$fracStr"
    }
}
