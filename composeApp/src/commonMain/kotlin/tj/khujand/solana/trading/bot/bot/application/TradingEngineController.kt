package tj.khujand.solana.trading.bot.bot.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.bot.domain.model.ActionResult
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.util.AppSettings

class TradingEngineController(
    private val tokenMonitor: TokenMonitor = TokenMonitor(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    fun tokenMonitor(): TokenMonitor = tokenMonitor

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
