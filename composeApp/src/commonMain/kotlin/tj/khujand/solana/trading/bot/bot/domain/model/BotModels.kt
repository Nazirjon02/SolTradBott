package tj.khujand.solana.trading.bot.bot.domain.model

import tj.khujand.solana.trading.bot.network.FilterSettings

enum class TradingMode {
    DEMO,
    REAL
}

data class ActionResult(
    val success: Boolean,
    val message: String
)

data class DealsSummary(
    val totalTrades: Int,
    val profitableTrades: Int,
    val tpTriggerHits: Int,
    val losingTrades: Int,
    val netProfitUsd: Double,
    val winRatePct: Double
)

data class SystemSnapshot(
    val isMonitoring: Boolean,
    val mode: TradingMode,
    val demoBalanceUsd: Double,
    val dealsSummary: DealsSummary,
    val keyParameters: Map<String, String>
)

data class FilterFieldSpec(
    val key: String,
    val title: String,
    val min: Double,
    val max: Double,
    val step: Double
)

data class FilterSettingsView(
    val settings: FilterSettings,
    val editableFields: List<FilterFieldSpec>
)

data class ExitStrategyView(
    val settings: FilterSettings,
    val editableFields: List<FilterFieldSpec>
)

data class MonitoredTokenView(
    val name: String,
    val tokenAddress: String,
    val profitUsd: Double = 0.0,
    val priceChangePercent: Double = 0.0,
    val investedUsd: Double = 0.0
)
