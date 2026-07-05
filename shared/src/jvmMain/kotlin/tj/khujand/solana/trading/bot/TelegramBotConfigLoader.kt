package tj.khujand.solana.trading.bot

import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotConfig
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotSettings
import java.io.File
import java.util.Properties

internal object TelegramBotConfigLoader {

    private const val PROP_TOKEN = "telegram.bot.token"
    private const val PROP_ADMIN_CHAT = "telegram.admin.chat.id"
    private const val PROP_ADMIN_USER = "telegram.admin.user.id"

    fun resolve(): TelegramBotConfig {
        loadFromEnv()?.let { return it }
        loadFromPropertiesFile()?.let { return it }
        TelegramBotSettings.loadForStandaloneBot()?.let { return it }
        error(
            """
            Telegram bot token is missing. Use one of:
            1) Environment: TELEGRAM_BOT_TOKEN (optional TELEGRAM_ADMIN_CHAT_ID, TELEGRAM_ADMIN_USER_ID)
            2) File: telegram-bot.properties in the app folder, or ${userConfigPathHint()} (see telegram-bot.properties.example)
            3) Desktop app: open Filters screen, enter Telegram token and IDs, save — then run this launcher again
            """.trimIndent()
        )
    }

    private fun loadFromEnv(): TelegramBotConfig? {
        val token = System.getenv("TELEGRAM_BOT_TOKEN").orEmpty().trim()
        if (token.isBlank()) return null
        return TelegramBotConfig(
            token = token,
            adminChatId = System.getenv("TELEGRAM_ADMIN_CHAT_ID")?.toLongOrNull(),
            adminUserId = System.getenv("TELEGRAM_ADMIN_USER_ID")?.toLongOrNull()
        )
    }

    private fun userConfigPathHint(): String {
        val home = System.getProperty("user.home") ?: return "%USERPROFILE%\\.soltradbot\\telegram-bot.properties"
        return File(home, ".soltradbot/telegram-bot.properties").path
    }

    private fun loadFromPropertiesFile(): TelegramBotConfig? {
        val explicit = System.getProperty("soltradbot.telegram.config")?.trim().orEmpty()
        val cwd = System.getProperty("user.dir") ?: "."
        val home = System.getProperty("user.home")
        val candidates = buildList {
            if (explicit.isNotEmpty()) add(File(explicit))
            add(File(cwd, "telegram-bot.properties"))
            if (home != null) add(File(File(home, ".soltradbot"), "telegram-bot.properties"))
        }
        for (file in candidates) {
            if (!file.isFile) continue
            val p = Properties()
            file.inputStream().buffered().use { p.load(it) }
            val token = p.getProperty(PROP_TOKEN).orEmpty().trim()
            if (token.isBlank()) continue
            return TelegramBotConfig(
                token = token,
                adminChatId = p.getProperty(PROP_ADMIN_CHAT)?.toLongOrNull(),
                adminUserId = p.getProperty(PROP_ADMIN_USER)?.toLongOrNull()
            )
        }
        return null
    }
}
