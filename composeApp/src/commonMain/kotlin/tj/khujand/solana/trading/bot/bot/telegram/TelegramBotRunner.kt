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
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMessageFormatter
import tj.khujand.solana.trading.bot.bot.telegram.routing.UpdateRouter

class TelegramBotRunner(
    config: TelegramBotConfig,
    service: TradingBotService = TradingRuntime.tradingBotService()
) {
    private val adminChatId = config.adminChatId
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    // Health check sends a ping every 5 minutes; only alerts if state changed or error
    private val HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1_000L
    private var lastMonitoringState: Boolean? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        subscribeTokenFoundNotifications()
        subscribeTokenClosedNotifications()
        startHealthCheck()
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
        tokenFoundSubscriptionId?.let { tradingService.unsubscribeOnTokenFound(it) }
        tokenFoundSubscriptionId = null
        tokenClosedSubscriptionId?.let { tradingService.unsubscribeOnTokenClosed(it) }
        tokenClosedSubscriptionId = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        pollingJob?.cancel()
        pollingJob = null
        telegram.close()
        println("Telegram bot stopped")
    }

    private fun startHealthCheck() {
        if (adminChatId == null) return
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                try {
                    val snapshot = tradingService.getSystemSnapshot()
                    val isMonitoring = snapshot.isMonitoring
                    val stateChanged = lastMonitoringState != null && lastMonitoringState != isMonitoring
                    lastMonitoringState = isMonitoring

                    // Send alert only when state changes (started/stopped unexpectedly)
                    if (stateChanged) {
                        val icon = if (isMonitoring) "🟢" else "🔴"
                        val msg = "$icon Health: мониторинг ${if (isMonitoring) "запущен" else "остановлен"}\n" +
                                "Баланс: $${snapshot.demoBalanceUsd}\n" +
                                "Сделок: ${snapshot.dealsSummary.totalTrades} | Win: ${snapshot.dealsSummary.winRatePct.toInt()}%"
                        runCatching { telegram.sendMessage(adminChatId, msg) }
                    } else {
                        // Periodic silent check — log only
                        println("💚 Health OK | monitoring=$isMonitoring | balance=$${snapshot.demoBalanceUsd}")
                    }
                } catch (e: Exception) {
                    println("⚠️ Health check error: ${e.message}")
                    // Alert on repeated failures
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
            scope.launch {
                val text = buildString {
                    appendLine("<b>🎯 Вход: ${TelegramMessageFormatter.escapeHtml(token.name)}</b>")
                    appendLine("Вложено: <b>$${token.investedUsd}</b>")
                    append("<code>${TelegramMessageFormatter.escapeHtml(token.tokenAddress)}</code>")
                }
                runCatching { telegram.sendMessage(adminChatId, text) }
                    .onFailure { println("Telegram notify error: ${it.message}") }
            }
        }
    }

    private fun subscribeTokenClosedNotifications() {
        if (tokenClosedSubscriptionId != null || adminChatId == null) return
        tokenClosedSubscriptionId = tradingService.subscribeOnTokenClosed { token ->
            scope.launch {
                val isProfit = token.profitUsd >= 0
                val icon     = if (isProfit) "✅" else "❌"
                val pnlSign  = if (isProfit) "+" else ""
                val pnlStr   = "$pnlSign${"%.2f".format(token.profitUsd)}"
                val pctStr   = "$pnlSign${"%.1f".format(token.priceChangePercent)}%"
                val text = buildString {
                    appendLine("<b>$icon Выход: ${TelegramMessageFormatter.escapeHtml(token.name)}</b>")
                    appendLine("PnL: <b>$${pnlStr}</b>  ($pctStr)")
                    appendLine("Вложено: \$${token.investedUsd}")
                    if (token.jupiterSellLastError.isNotBlank()) {
                        appendLine("⚠️ ${TelegramMessageFormatter.escapeHtml(token.jupiterSellLastError.take(100))}")
                    }
                    append("<code>${TelegramMessageFormatter.escapeHtml(token.tokenAddress)}</code>")
                }
                runCatching { telegram.sendMessage(adminChatId, text) }
                    .onFailure { println("Telegram close notify error: ${it.message}") }
            }
        }
    }
}
