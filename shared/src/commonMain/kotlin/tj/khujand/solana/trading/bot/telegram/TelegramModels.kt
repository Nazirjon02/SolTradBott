package tj.khujand.solana.trading.bot.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val from: TgUser? = null,
    val text: String? = null
)

@Serializable
data class TgChat(val id: Long, val type: String = "private")

@Serializable
data class TgUser(val id: Long, val username: String? = null)

@Serializable
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage? = null,
    val data: String? = null
)

@Serializable
data class TgUpdatesResponse(
    val ok: Boolean,
    val result: List<TgUpdate>
)

/**
 * Экранирование спецсимволов легаси-Markdown Telegram (`_ * ` `[`) в динамических
 * строках — символах монет, названиях стратегий, причинах. Без этого символ вроде
 * `PEPE_2` ломает парсер, и Telegram молча отклоняет сообщение (алерт теряется).
 */
internal fun String.escapeMarkdown(): String = buildString {
    for (c in this@escapeMarkdown) {
        if (c == '_' || c == '*' || c == '`' || c == '[') append('\\')
        append(c)
    }
}
