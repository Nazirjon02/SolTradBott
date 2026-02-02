package tj.khujand.solana.trading.bot.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import tj.khujand.solana.trading.bot.network.TokenPair

/**
 * История токена с результатом торговли
 */
@Serializable
data class TokenHistory(
    val tokenPair: TokenPair,
    val entryPrice: Double,
    val exitPrice: Double,
    val entryTime: Long,
    val exitTime: Long,
    val priceChangePercent: Double,
    val profitUsd: Double,
    val status: TokenStatus,
    val symbol: String,
    val entryDate: String,
    val exitDate: String,
    val note: String = "",
    val isPartialExit: Boolean = false,
    val partialExitPct: Double = 0.0
)

/**
 * Менеджер для сохранения и загрузки истории токенов
 */
object TokenHistoryManager {
    private const val KEY_HISTORY = "token_history_v1"
    private const val DEFAULT_INVESTMENT_USD = 100.0
    
    /**
     * Сохранить токен в историю
     */
    fun saveToHistory(token: MonitoredToken) {
        if (token.status == TokenStatus.MONITORING) return // Сохраняем только завершенные
        
        val history = TokenHistory(
            tokenPair = token.tokenPair,
            entryPrice = token.entryPrice,
            exitPrice = token.currentPrice.toDoubleOrNull() ?: token.entryPrice,
            entryTime = token.foundTime,
            exitTime = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            priceChangePercent = token.priceChangePercent,
            profitUsd = token.profitUsd,
            status = token.status,
            symbol = token.tokenPair.baseToken?.symbol ?: "Unknown",
            entryDate = formatDate(token.foundTime),
            exitDate = formatDate(kotlin.time.Clock.System.now().toEpochMilliseconds())
        )
        
        val existingHistory = loadHistory()
        val updatedHistory = existingHistory + history
        
        tj.khujand.solana.trading.bot.util.AppSettings.putObject(KEY_HISTORY, updatedHistory)
        println("💾 Токен ${token.tokenPair.baseToken?.symbol} сохранен в историю: ${token.status}")
    }
    
    /**
     * Загрузить всю историю
     */
    fun loadHistory(): List<TokenHistory> {
        return tj.khujand.solana.trading.bot.util.AppSettings.getObjectSafe(
            KEY_HISTORY,
            emptyList<TokenHistory>()
        )
    }
    
    /**
     * Очистить историю
     */
    fun clearHistory() {
        tj.khujand.solana.trading.bot.util.AppSettings.remove(KEY_HISTORY)
    }
    
    /**
     * Получить статистику
     */
    fun getStatistics(): ProfitLossStatistics {
        val history = loadHistory()
        val tpTokens = history.filter { it.status == TokenStatus.STOPPED_TP }
        val slTokens = history.filter { it.status == TokenStatus.STOPPED_SL }
        
        val totalProfit = history.filter { it.profitUsd > 0 }.sumOf { it.profitUsd }
        val totalLoss = history.filter { it.profitUsd < 0 }.sumOf { it.profitUsd }
        val netProfit = totalProfit + totalLoss // loss отрицательный
        
        return ProfitLossStatistics(
            totalTrades = history.size,
            tpCount = tpTokens.size,
            slCount = slTokens.size,
            totalProfit = totalProfit,
            totalLoss = totalLoss,
            netProfit = netProfit,
            winRate = if (history.isNotEmpty()) (tpTokens.size.toDouble() / history.size) * 100 else 0.0
        )
    }
    
    private fun formatDate(timestamp: Long): String {
        // Используем kotlinx.datetime для кросс-платформенности
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        
        // Форматируем без String.format (кросс-платформенный способ)
        val day = dateTime.dayOfMonth.toString().padStart(2, '0')
        val month = dateTime.monthNumber.toString().padStart(2, '0')
        val year = dateTime.year.toString()
        val hour = dateTime.hour.toString().padStart(2, '0')
        val minute = dateTime.minute.toString().padStart(2, '0')
        
        return "$day.$month.$year $hour:$minute"
    }

    fun savePartialExit(
        token: MonitoredToken,
        stageLabel: String,
        percent: Double,
        marketCap: Double,
        exitPrice: Double
    ) {
        if (percent <= 0) return

        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val priceChangePercent =
            if (token.entryPrice > 0) ((exitPrice - token.entryPrice) / token.entryPrice) * 100 else 0.0
        val profitUsd = DEFAULT_INVESTMENT_USD * (percent / 100.0) * (priceChangePercent / 100.0)

        val history = TokenHistory(
            tokenPair = token.tokenPair,
            entryPrice = token.entryPrice,
            exitPrice = exitPrice,
            entryTime = token.foundTime,
            exitTime = now,
            priceChangePercent = priceChangePercent,
            profitUsd = profitUsd,
            status = TokenStatus.STOPPED_TP,
            symbol = token.tokenPair.baseToken?.symbol ?: "Unknown",
            entryDate = formatDate(token.foundTime),
            exitDate = formatDate(now),
            note = "$stageLabel • ${percent.toInt()}% @ MC ${marketCap.toInt()}",
            isPartialExit = true,
            partialExitPct = percent
        )

        val existingHistory = loadHistory()
        val updatedHistory = existingHistory + history
        tj.khujand.solana.trading.bot.util.AppSettings.putObject(KEY_HISTORY, updatedHistory)
        println("💾 Частичный выход сохранен: $stageLabel (${percent.toInt()}%)")
    }
}

/**
 * Статистика доходов и убытков
 */
data class ProfitLossStatistics(
    val totalTrades: Int,
    val tpCount: Int,
    val slCount: Int,
    val totalProfit: Double,
    val totalLoss: Double,
    val netProfit: Double,
    val winRate: Double
)
