package tj.khujand.solana.trading.bot.bot.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.data.StrategySlotsManager
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings

/**
 * Единый runtime для UI/Service/Telegram.
 *
 * Реестр стратегий: каждая стратегия получает СВОЙ [TokenMonitor] и [TradingEngineController],
 * что позволяет нескольким стратегиям работать параллельно, не затирая состояние друг друга.
 * Инстанс с id "default" — обратная совместимость со старым однопоточным кодом (jvm main, Telegram).
 */
object TradingRuntime {
    private const val DEFAULT_ID = "default"

    private val monitors = mutableMapOf<String, TokenMonitor>()
    private val controllers = mutableMapOf<String, TradingEngineController>()

    private val sharedTradingBotService: TradingBotService by lazy {
        TradingBotService(engineController = controllerFor(DEFAULT_ID))
    }

    // Множество id всех запущенных в данный момент стратегий.
    private val _runningStrategies = MutableStateFlow<Set<String>>(emptySet())
    val runningStrategiesFlow: StateFlow<Set<String>> = _runningStrategies.asStateFlow()

    // Производное "работает ли хоть одна стратегия" — для глобальных индикаторов и легаси-кода.
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoringFlow: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    // ─── Реестр ───────────────────────────────────────────────────────────────

    fun monitorFor(id: String): TokenMonitor = synchronized(this) {
        monitors.getOrPut(id) { TokenMonitor(id) }
    }

    fun controllerFor(id: String): TradingEngineController = synchronized(this) {
        controllers.getOrPut(id) {
            TradingEngineController(
                instanceId = id,
                tokenMonitor = monitorFor(id),
                settingsProvider = { resolveSettings(id) },
            )
        }
    }

    /** Настройки конкретной стратегии: слот + глобальные секреты; для "default" — глобальные настройки. */
    private fun resolveSettings(id: String): FilterSettings {
        val global = FilterSettingsManager.loadSettings()
        if (id == DEFAULT_ID) return global
        val slot = StrategySlotsManager.getSlotById(id) ?: return global
        return StrategySlotsManager.applySlot(slot, global)
    }

    /** Мониторы всех запущенных стратегий — для агрегированных представлений (портфель, паника). */
    fun activeMonitors(): List<TokenMonitor> =
        _runningStrategies.value.map { monitorFor(it) }

    // ─── Обратная совместимость (одиночная "default" стратегия) ─────────────────

    fun tokenMonitor(): TokenMonitor = monitorFor(DEFAULT_ID)
    fun engineController(): TradingEngineController = controllerFor(DEFAULT_ID)
    fun tradingBotService(): TradingBotService = sharedTradingBotService

    // ─── Состояние запуска ──────────────────────────────────────────────────────

    /** Вызывается из TradingEngineController при старте/стопе конкретной стратегии. */
    fun setStrategyRunning(id: String, active: Boolean) {
        val current = _runningStrategies.value
        val updated = if (active) current + id else current - id
        if (updated != current) _runningStrategies.value = updated
        _isMonitoring.value = updated.isNotEmpty()
    }

    /** Восстанавливает множество запущенных стратегий после перезапуска процесса. */
    fun restoreRunningState(ids: Set<String>) {
        _runningStrategies.value = ids
        _isMonitoring.value = ids.isNotEmpty()
    }

    /** Легаси: старый однопоточный код переключает инстанс "default". */
    internal fun setMonitoringActive(active: Boolean) {
        setStrategyRunning(DEFAULT_ID, active)
    }
}
