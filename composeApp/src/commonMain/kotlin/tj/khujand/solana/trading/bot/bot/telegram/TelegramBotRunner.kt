package tj.khujand.solana.trading.bot.bot.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.bot.application.TradingBotService
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.bot.domain.security.AdminAccessPolicy
import tj.khujand.solana.trading.bot.bot.telegram.api.TelegramHttpClient
import tj.khujand.solana.trading.bot.bot.telegram.routing.UpdateRouter

class TelegramBotRunner(
    config: TelegramBotConfig,
    service: TradingBotService = TradingRuntime.tradingBotService()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val telegram = TelegramHttpClient(config)
    private val router = UpdateRouter(
        service = service,
        telegram = telegram,
        accessPolicy = AdminAccessPolicy(
            adminChatId = config.adminChatId,
            adminUserId = config.adminUserId
        )
    )

    private var pollingJob: Job? = null
    private var offset: Long? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            println("Telegram bot polling started")
            while (isActive) {
                try {
                    val updates = telegram.getUpdates(offset)
                    updates.forEach { update ->
                        offset = update.updateId + 1
                        router.route(update)
                    }
                } catch (e: Exception) {
                    println("Telegram polling error: ${e.message}")
                    delay(2_000)
                }
            }
        }
    }

    suspend fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        telegram.close()
        println("Telegram bot stopped")
    }
}
