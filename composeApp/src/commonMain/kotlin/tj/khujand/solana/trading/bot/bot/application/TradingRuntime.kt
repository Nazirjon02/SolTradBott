package tj.khujand.solana.trading.bot.bot.application

import tj.khujand.solana.trading.bot.domain.TokenMonitor

/**
 * Единый runtime-инстанс для процессов, где одновременно работают UI/Service/Telegram.
 * Это устраняет рассинхронизацию между разными TokenMonitor экземплярами.
 */
object TradingRuntime {
    private val sharedTokenMonitor: TokenMonitor by lazy { TokenMonitor() }
    private val sharedEngineController: TradingEngineController by lazy {
        TradingEngineController(tokenMonitor = sharedTokenMonitor)
    }
    private val sharedTradingBotService: TradingBotService by lazy {
        TradingBotService(engineController = sharedEngineController)
    }

    fun tokenMonitor(): TokenMonitor = sharedTokenMonitor

    fun engineController(): TradingEngineController = sharedEngineController

    fun tradingBotService(): TradingBotService = sharedTradingBotService
}
