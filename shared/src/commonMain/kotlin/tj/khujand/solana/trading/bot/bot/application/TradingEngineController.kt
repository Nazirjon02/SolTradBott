package tj.khujand.solana.trading.bot.bot.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.bot.domain.model.ActionResult
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import tj.khujand.solana.trading.bot.util.AppSettings

class TradingEngineController(
    private val instanceId: String = "default",
    private val tokenMonitor: TokenMonitor = TokenMonitor(instanceId),
    private val settingsProvider: () -> FilterSettings = { FilterSettingsManager.loadSettings() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    // Persisted run/health flags are namespaced so parallel strategies don't share one flag.
    private val keyMonitoringActive =
        if (instanceId == "default") AppSettings.KEY_MONITORING_ACTIVE
        else "${AppSettings.KEY_MONITORING_ACTIVE}_$instanceId"
    private val keyRequestInProgress =
        if (instanceId == "default") AppSettings.KEY_REQUEST_IN_PROGRESS
        else "${AppSettings.KEY_REQUEST_IN_PROGRESS}_$instanceId"

    private val tokenFoundListeners  = mutableMapOf<Long, (MonitoredToken) -> Unit>()
    private val tokenClosedListeners = mutableMapOf<Long, (MonitoredToken) -> Unit>()
    private var nextListenerId: Long = 1L
    private var watchdogJob: Job? = null
    private val WATCHDOG_CHECK_INTERVAL_MS = 60_000L

    fun tokenMonitor(): TokenMonitor = tokenMonitor

    @Synchronized fun subscribeOnTokenFound(listener: (MonitoredToken) -> Unit): Long {
        val id = nextListenerId++; tokenFoundListeners[id] = listener; return id
    }
    @Synchronized fun unsubscribeOnTokenFound(id: Long) { tokenFoundListeners.remove(id) }

    @Synchronized fun subscribeOnTokenClosed(listener: (MonitoredToken) -> Unit): Long {
        val id = nextListenerId++; tokenClosedListeners[id] = listener; return id
    }
    @Synchronized fun unsubscribeOnTokenClosed(id: Long) { tokenClosedListeners.remove(id) }

    private fun notifyTokenFound(token: MonitoredToken) {
        val listeners = synchronized(this) { tokenFoundListeners.values.toList() }
        listeners.forEach { runCatching { it(token) } }
        TradingNotifications.emitFound(token)
    }

    private fun notifyTokenClosed(token: MonitoredToken) {
        val listeners = synchronized(this) { tokenClosedListeners.values.toList() }
        listeners.forEach { runCatching { it(token) } }
        TradingNotifications.emitClosed(token)
    }

    fun isMonitoring(): Boolean {
        return tokenMonitor.isMonitoringActive() ||
            AppSettings.getBooleanSafe(keyMonitoringActive, false)
    }

    fun startMonitoring(intervalSeconds: Int = 10): ActionResult {
        if (tokenMonitor.isMonitoringActive()) {
            return ActionResult(
                success = true,
                message = "Мониторинг уже запущен"
            )
        }
        val settings = settingsProvider()
        tokenMonitor.filterSettings = settings
        tokenMonitor.restoreFromCache()
        tokenMonitor.startMonitoring(
            intervalSeconds = intervalSeconds,
            onNewTokenFound = { token -> notifyTokenFound(token) },
            onTokenClosed   = { token -> notifyTokenClosed(token) },
            onRequestStateChanged = { inProgress ->
                AppSettings.putBoolean(keyRequestInProgress, inProgress)
            },
            onError = { println("Bot monitor error: $it") }
        )
        AppSettings.putBoolean(keyMonitoringActive, true)
        TradingRuntime.setStrategyRunning(instanceId, true)
        startWatchdog(intervalSeconds)
        return ActionResult(
            success = true,
            message = "Мониторинг запущен"
        )
    }

    fun startMonitoringAsync(intervalSeconds: Int = 10) {
        scope.launch { startMonitoring(intervalSeconds) }
    }

    private fun startWatchdog(intervalSeconds: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_CHECK_INTERVAL_MS)
                val shouldBeRunning = AppSettings.getBooleanSafe(keyMonitoringActive, false)
                if (shouldBeRunning && !tokenMonitor.isMonitoringActive()) {
                    println("🐕 Watchdog: мониторинг завис или упал — перезапускаем")
                    startMonitoring(intervalSeconds)
                }
            }
        }
    }

    fun stopMonitoring(): ActionResult {
        watchdogJob?.cancel()
        watchdogJob = null
        AppSettings.putBoolean(keyMonitoringActive, false)
        AppSettings.putBoolean(keyRequestInProgress, false)
        TradingRuntime.setStrategyRunning(instanceId, false)
        if (!tokenMonitor.isMonitoringActive()) {
            return ActionResult(success = true, message = "Мониторинг уже остановлен")
        }
        tokenMonitor.stopMonitoring()
        return ActionResult(success = true, message = "Мониторинг остановлен")
    }
}
