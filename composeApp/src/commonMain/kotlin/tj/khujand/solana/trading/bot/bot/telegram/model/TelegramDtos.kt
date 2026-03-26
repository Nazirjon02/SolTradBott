package tj.khujand.solana.trading.bot.bot.telegram.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TelegramApiResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null
)

@Serializable
data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
    @SerialName("callback_query")
    val callbackQuery: TelegramCallbackQuery? = null
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id")
    val messageId: Long,
    val text: String? = null,
    val chat: TelegramChat,
    val from: TelegramUser? = null
)

@Serializable
data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser,
    val data: String? = null,
    val message: TelegramMessage? = null
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String
)

@Serializable
data class TelegramUser(
    val id: Long,
    @SerialName("first_name")
    val firstName: String? = null,
    val username: String? = null
)
