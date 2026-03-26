package tj.khujand.solana.trading.bot

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotConfig
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotRunner

fun main() = runBlocking {
    val token = System.getenv("TELEGRAM_BOT_TOKEN").orEmpty().trim()
    if (token.isBlank()) {
        error("TELEGRAM_BOT_TOKEN is required")
    }

    val adminChatId = System.getenv("TELEGRAM_ADMIN_CHAT_ID")?.toLongOrNull()
    val adminUserId = System.getenv("TELEGRAM_ADMIN_USER_ID")?.toLongOrNull()

    val runner = TelegramBotRunner(
        config = TelegramBotConfig(
            token = token,
            adminChatId = adminChatId,
            adminUserId = adminUserId
        )
    )
    runner.start()
    println("Telegram bot is running")

    while (true) {
        delay(60_000)
    }
}
