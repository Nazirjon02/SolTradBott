package tj.khujand.solana.trading.bot.bot.telegram

data class TelegramBotConfig(
    val token: String,
    val adminChatId: Long? = null,
    val adminUserId: Long? = null,
    val pollingTimeoutSeconds: Int = 30
)
