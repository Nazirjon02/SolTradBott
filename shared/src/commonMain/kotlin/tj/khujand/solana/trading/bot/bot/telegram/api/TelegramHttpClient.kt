package tj.khujand.solana.trading.bot.bot.telegram.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotConfig
import tj.khujand.solana.trading.bot.bot.telegram.model.TelegramApiResponse
import tj.khujand.solana.trading.bot.bot.telegram.model.TelegramUpdate

class TelegramHttpClient(
    private val config: TelegramBotConfig
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = (config.pollingTimeoutSeconds + 15) * 1000L
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = (config.pollingTimeoutSeconds + 15) * 1000L
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    private fun apiUrl(method: String): String {
        return "https://api.telegram.org/bot${config.token}/$method"
    }

    suspend fun getUpdates(offset: Long?): List<TelegramUpdate> {
        val response = httpClient.get(apiUrl("getUpdates")) {
            url {
                parameters.append("timeout", config.pollingTimeoutSeconds.toString())
                parameters.append("allowed_updates", "[\"message\",\"callback_query\"]")
                if (offset != null) {
                    parameters.append("offset", offset.toString())
                }
            }
        }.body<TelegramApiResponse<List<TelegramUpdate>>>()
        return response.result ?: emptyList()
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyMarkup: TelegramInlineKeyboard? = null
    ) {
        val payload = buildJsonObject {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "HTML")
            if (replyMarkup != null) {
                put("reply_markup", encodeKeyboard(replyMarkup))
            }
        }
        httpClient.post(apiUrl("sendMessage")) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        replyMarkup: TelegramInlineKeyboard? = null
    ) {
        val payload = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            put("parse_mode", "HTML")
            if (replyMarkup != null) {
                put("reply_markup", encodeKeyboard(replyMarkup))
            }
        }
        httpClient.post(apiUrl("editMessageText")) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    suspend fun answerCallbackQuery(callbackQueryId: String, text: String? = null) {
        val payload = buildJsonObject {
            put("callback_query_id", callbackQueryId)
            if (!text.isNullOrBlank()) {
                put("text", text)
                put("show_alert", false)
            }
        }
        httpClient.post(apiUrl("answerCallbackQuery")) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    fun close() {
        httpClient.close()
    }

    private fun encodeKeyboard(keyboard: TelegramInlineKeyboard) = buildJsonObject {
        putJsonArray("inline_keyboard") {
            keyboard.rows.forEach { row ->
                add(buildJsonArray {
                    row.forEach { button ->
                        add(buildJsonObject {
                            put("text", button.text)
                            put("callback_data", button.callbackData)
                        })
                    }
                })
            }
        }
    }
}

@Serializable
data class TelegramInlineKeyboardButton(
    val text: String,
    val callbackData: String
)

@Serializable
data class TelegramInlineKeyboard(
    val rows: List<List<TelegramInlineKeyboardButton>>
)
