package tj.khujand.solana.trading.bot.bot.telegram.routing

data class RouterContext(
    val chatId: Long,
    val userId: Long?,
    val messageId: Long? = null,
    val callbackQueryId: String? = null
)
