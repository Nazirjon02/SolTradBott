package tj.khujand.solana.trading.bot.bot.telegram

import tj.khujand.solana.trading.bot.util.AppSettings

object TelegramBotSettings {
    const val KEY_ENABLED = "telegram_bot_enabled"
    const val KEY_TOKEN = "telegram_bot_token"
    const val KEY_ADMIN_CHAT_ID = "telegram_bot_admin_chat_id"
    const val KEY_ADMIN_USER_ID = "telegram_bot_admin_user_id"

    fun loadFromAppSettings(): TelegramBotConfig? {
        val enabled = AppSettings.getBooleanSafe(KEY_ENABLED, false)
        if (!enabled) return null

        val token = AppSettings.getStringSafe(KEY_TOKEN, "").trim()
        if (token.isBlank()) return null

        val chatId = AppSettings.getStringSafe(KEY_ADMIN_CHAT_ID, "").toLongOrNull()
        val userId = AppSettings.getStringSafe(KEY_ADMIN_USER_ID, "").toLongOrNull()

        return TelegramBotConfig(
            token = token,
            adminChatId = chatId,
            adminUserId = userId
        )
    }
}
