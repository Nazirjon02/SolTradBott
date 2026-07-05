package tj.khujand.solana.trading.bot.bot.domain.security

data class AdminAccessPolicy(
    val adminChatId: Long? = null,
    val adminUserId: Long? = null
) {
    fun isAllowed(chatId: Long?, userId: Long?): Boolean {
        if (adminChatId == null && adminUserId == null) return false
        val chatAllowed = adminChatId == null || adminChatId == chatId
        val userAllowed = adminUserId == null || adminUserId == userId
        return chatAllowed && userAllowed
    }
}
