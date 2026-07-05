package tj.khujand.solana.trading.bot.bot.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.bot.application.TradingBotService
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.bot.domain.security.AdminAccessPolicy
import tj.khujand.solana.trading.bot.bot.telegram.api.TelegramHttpClient
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMessageFormatter
import tj.khujand.solana.trading.bot.bot.telegram.routing.UpdateRouter

class TelegramBotRunner(
    config: TelegramBotConfig,
    service: TradingBotService = TradingRuntime.tradingBotService()
) {
    private val adminChatId = config.adminChatId

    // Отдельный scope для polling — закрывается при stop()
    private var pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Отдельный scope для уведомлений о сделках — живёт до явного cancel
    private var notifyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val telegram = TelegramHttpClient(config)
    private val tradingService = service
    private val router = UpdateRouter(
        service = service,
        telegram = telegram,
        accessPolicy = AdminAccessPolicy(
            adminChatId = config.adminChatId,
            adminUserId = config.adminUserId
        )
    )

    private var pollingJob: Job? = null
    private var healthCheckJob: Job? = null
    private var tokenFoundSubscriptionId: Long? = null
    private var tokenClosedSubscriptionId: Long? = null
    private var offset: Long? = null

    private val HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1_000L
    private var lastMonitoringState: Boolean? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        subscribeTokenFoundNotifications()
        subscribeTokenClosedNotifications()
        startHealthCheck()
        pollingJob = pollingScope.launch {
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
        // Сначала отписываемся от событий — новые уведомления не будут ставиться в очередь
        tokenFoundSubscriptionId?.let { tradingService.unsubscribeOnTokenFound(it) }
        tokenFoundSubscriptionId = null
        tokenClosedSubscriptionId?.let { tradingService.unsubscribeOnTokenClosed(it) }
        tokenClosedSubscriptionId = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        pollingJob?.cancel()
        pollingJob = null
        // Даём время завершить уведомления, уже стоящие в очереди (макс 3 сек)
        delay(300)
        pollingScope.cancel()
        notifyScope.cancel()
        // Пересоздаём scope для возможного перезапуска без создания нового Runner
        pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        notifyScope  = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        telegram.close()
        println("Telegram bot stopped")
    }

    private fun startHealthCheck() {
        if (adminChatId == null) return
        healthCheckJob?.cancel()
        healthCheckJob = pollingScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                try {
                    val snapshot = tradingService.getSystemSnapshot()
                    val isMonitoring = snapshot.isMonitoring
                    val stateChanged = lastMonitoringState != null && lastMonitoringState != isMonitoring
                    lastMonitoringState = isMonitoring
                    if (stateChanged) {
                        val icon = if (isMonitoring) "🟢" else "🔴"
                        val msg = "$icon Health: мониторинг ${if (isMonitoring) "запущен" else "остановлен"}\n" +
                                "Баланс: $${snapshot.demoBalanceUsd}\n" +
                                "Сделок: ${snapshot.dealsSummary.totalTrades} | Win: ${snapshot.dealsSummary.winRatePct.toInt()}%"
                        sendWithRetry(adminChatId, msg)
                    } else {
                        println("💚 Health OK | monitoring=$isMonitoring | balance=$${snapshot.demoBalanceUsd}")
                    }
                } catch (e: Exception) {
                    println("⚠️ Health check error: ${e.message}")
                    runCatching {
                        telegram.sendMessage(adminChatId, "⚠️ Health check ошибка: ${e.message?.take(200)}")
                    }
                }
            }
        }
    }

    private fun subscribeTokenFoundNotifications() {
        if (tokenFoundSubscriptionId != null || adminChatId == null) return
        tokenFoundSubscriptionId = tradingService.subscribeOnTokenFound { token ->
            notifyScope.launch {
                val text = buildString {
                    appendLine("<b>🎯 Вход: ${TelegramMessageFormatter.escapeHtml(token.name)}</b>")
                    appendLine("Вложено: <b>$${token.investedUsd}</b>")
                    append("<code>${TelegramMessageFormatter.escapeHtml(token.tokenAddress)}</code>")
                }
                sendWithRetry(adminChatId, text)
            }
        }
    }

    private fun subscribeTokenClosedNotifications() {
        if (tokenClosedSubscriptionId != null || adminChatId == null) return
        tokenClosedSubscriptionId = tradingService.subscribeOnTokenClosed { token ->
            notifyScope.launch {
                val isProfit = token.profitUsd >= 0
                val icon    = if (isProfit) "✅" else "❌"
                val sign    = if (isProfit) "+" else ""
                val pnlStr  = "$sign${"%.2f".format(token.profitUsd)}"
                val pctStr  = "$sign${"%.1f".format(token.priceChangePercent)}%"
                val text = buildString {
                    appendLine("<b>$icon Выход: ${TelegramMessageFormatter.escapeHtml(token.name)}</b>")
                    appendLine("PnL: <b>$$pnlStr</b>  ($pctStr)")
                    appendLine("Вложено: \$${token.investedUsd}")
                    if (token.jupiterSellLastError.isNotBlank()) {
                        appendLine("⚠️ ${TelegramMessageFormatter.escapeHtml(token.jupiterSellLastError.take(100))}")
                    }
                    append("<code>${TelegramMessageFormatter.escapeHtml(token.tokenAddress)}</code>")
                }
                sendWithRetry(adminChatId, text)
            }
        }
    }

    /** Отправка с повтором до 3 раз при сетевых ошибках. */
    private suspend fun sendWithRetry(chatId: Long, text: String, maxAttempts: Int = 3) {
        repeat(maxAttempts) { attempt ->
            val result = runCatching { telegram.sendMessage(chatId, text) }
            if (result.isSuccess) return
            val err = result.exceptionOrNull()
            println("Telegram send attempt ${attempt + 1}/$maxAttempts failed: ${err?.message}")
            if (attempt < maxAttempts - 1) delay(2_000L * (attempt + 1))
        }
    }
}
