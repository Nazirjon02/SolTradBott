package tj.khujand.solana.trading.bot.bot.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tj.khujand.solana.trading.bot.domain.TokenMonitor

/**
 * Единый runtime-инстанс для UI/Service/Telegram.
 * StateFlow гарантирует, что состояние мониторинга не теряется при смене табов —
 * оно живёт в процессе, а не в Compose composable.
 */
object TradingRuntime {
    private val sharedTokenMonitor: TokenMonitor by lazy { TokenMonitor() }
    private val sharedEngineController: TradingEngineController by lazy {
        TradingEngineController(tokenMonitor = sharedTokenMonitor)
    }
    private val sharedTradingBotService: TradingBotService by lazy {
        TradingBotService(engineController = sharedEngineController)
    }

    // Персистентное состояние мониторинга — не сбрасывается при смене табов
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoringFlow: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    fun tokenMonitor(): TokenMonitor = sharedTokenMonitor
    fun engineController(): TradingEngineController = sharedEngineController
    fun tradingBotService(): TradingBotService = sharedTradingBotService

    /** Вызывается из TradingEngineController при старте/стопе мониторинга. */
    internal fun setMonitoringActive(active: Boolean) {
        _isMonitoring.value = active
    }
}
