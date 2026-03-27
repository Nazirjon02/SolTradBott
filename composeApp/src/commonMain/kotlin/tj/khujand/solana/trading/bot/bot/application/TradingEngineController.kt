package tj.khujand.solana.trading.bot.bot.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.bot.domain.model.ActionResult
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.util.AppSettings

class TradingEngineController(
    private val tokenMonitor: TokenMonitor = TokenMonitor(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val tokenFoundListeners = mutableMapOf<Long, (MonitoredToken) -> Unit>()
    private var nextTokenFoundListenerId: Long = 1L

    fun tokenMonitor(): TokenMonitor = tokenMonitor

    @Synchronized
    fun subscribeOnTokenFound(listener: (MonitoredToken) -> Unit): Long {
        val id = nextTokenFoundListenerId++
        tokenFoundListeners[id] = listener
        return id
    }

    @Synchronized
    fun unsubscribeOnTokenFound(id: Long) {
        tokenFoundListeners.remove(id)
    }

    private fun notifyTokenFound(token: MonitoredToken) {
        val listeners = synchronized(this) { tokenFoundListeners.values.toList() }
        listeners.forEach { listener ->
            runCatching { listener(token) }
        }
    }

    fun isMonitoring(): Boolean {
        return tokenMonitor.isMonitoringActive() ||
            AppSettings.getBooleanSafe(AppSettings.KEY_MONITORING_ACTIVE, false)
    }

    fun startMonitoring(intervalSeconds: Int = 10): ActionResult {
        if (tokenMonitor.isMonitoringActive()) {
            return ActionResult(
                success = true,
                message = "Мониторинг уже запущен"
            )
        }
        val settings = FilterSettingsManager.loadSettings()
        tokenMonitor.filterSettings = settings
        tokenMonitor.restoreFromCache()
        tokenMonitor.startMonitoring(
            intervalSeconds = intervalSeconds,
            onNewTokenFound = { token ->
                notifyTokenFound(token)
            },
            onRequestStateChanged = { inProgress ->
                AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, inProgress)
            },
            onError = { println("Bot monitor error: $it") }
        )
        AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, true)
        return ActionResult(
            success = true,
            message = "Мониторинг запущен"
        )
    }

    fun startMonitoringAsync(intervalSeconds: Int = 10) {
        scope.launch {
            startMonitoring(intervalSeconds)
        }
    }

    fun stopMonitoring(): ActionResult {
        if (!tokenMonitor.isMonitoringActive()) {
            AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, false)
            AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
            return ActionResult(
                success = true,
                message = "Мониторинг уже остановлен"
            )
        }
        tokenMonitor.stopMonitoring()
        AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, false)
        AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
        return ActionResult(
            success = true,
            message = "Мониторинг остановлен"
        )
    }
}
