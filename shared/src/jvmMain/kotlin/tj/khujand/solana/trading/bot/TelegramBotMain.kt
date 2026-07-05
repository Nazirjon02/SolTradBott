package tj.khujand.solana.trading.bot

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotRunner

fun main() = runBlocking {
    val config = TelegramBotConfigLoader.resolve()

    val runner = TelegramBotRunner(config = config)
    runner.start()
    println("Telegram bot is running. Press Ctrl+C to stop.")

    Runtime.getRuntime().addShutdownHook(Thread {
        kotlinx.coroutines.runBlocking { runner.stop() }
        println("Telegram bot shutdown complete.")
    })

    while (true) {
        delay(60_000)
    }
}
