package tj.khujand.solana.trading.bot.data

import kotlinx.serialization.Serializable

enum class BotStatus { RUNNING, PAUSED, STOPPED }

@Serializable
data class BotStats(
    val totalTrades: Long = 0,
    val wins: Long = 0,
    val losses: Long = 0,
    val totalPnl: Double = 0.0,
    val totalFees: Double = 0.0,
    val todayPnl: Double = 0.0,
    val todayWins: Long = 0,
    val todayLosses: Long = 0,
    val uptime: String = "—",
)

/** Открытая позиция для UI/REST/Telegram: строка trade + текущая цена. */
@Serializable
data class OpenPosition(
    val tradeId: String,
    val mint: String,
    val symbol: String,
    val strategyName: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val qtyRemaining: Double,
    val sizeUsd: Double,
    /** Текущая рыночная стоимость оставшихся токенов (обновляется вместе с ценой). */
    val valueUsd: Double = 0.0,
    val pnlUsd: Double,
    val pnlPercent: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val isDemo: Boolean,
    val openedAt: Long,
)

@Serializable
data class AccountBalance(
    val totalUsd: Double = 0.0,
    val sol: Double = 0.0,
    val solUsd: Double = 0.0,
    val demoUsd: Double = 0.0,
    val isDemo: Boolean = true,
)
