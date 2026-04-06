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
    val partialExitPct: Double = 0.0,
    val isRealTrade: Boolean = false,
    val isSwapSuccess: Boolean = true,
    val investedUsd: Double = 0.0,
    val exitAmountUsd: Double = 0.0
)

/**
 * Менеджер для сохранения и загрузки истории токенов
 */
object TokenHistoryManager {
    private const val KEY_HISTORY = "token_history_v1"
    
    /**
     * Сохранить токен в историю
     */
    fun saveToHistory(token: MonitoredToken) {
        if (token.status == TokenStatus.MONITORING) return // Сохраняем только завершенные
        val isRealTrade = !token.demoBuyApplied
        val isSwapSuccess = if (isRealTrade) token.buyTxId.isNotBlank() else true
        val investedUsd = token.investedUsd
        val exitAmountUsd = investedUsd + token.profitUsd
        
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
            exitDate = formatDate(kotlin.time.Clock.System.now().toEpochMilliseconds()),
            isRealTrade = isRealTrade,
            isSwapSuccess = isSwapSuccess,
            investedUsd = investedUsd,
            exitAmountUsd = exitAmountUsd
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
        val completedTrades = history.filter { !it.isPartialExit }
        val partialExits = history.filter { it.isPartialExit }
        val slTokens = completedTrades.filter { it.status == TokenStatus.STOPPED_SL }
        val completedTradeKeys = completedTrades
            .map { it.tokenPair.baseToken?.address ?: it.tokenPair.pairAddress ?: "${it.symbol}_${it.entryTime}" }
            .toSet()
        val partialGroups = partialExits
            .groupBy { it.tokenPair.baseToken?.address ?: it.tokenPair.pairAddress ?: "${it.symbol}_${it.entryTime}" }
        val fullyClosedByPartials = partialGroups
            .filter { (tradeKey, exits) ->
                tradeKey !in completedTradeKeys && exits.sumOf { it.partialExitPct.coerceAtLeast(0.0) } >= 99.0
            }
            .values
        val fullyClosedByPartialsCount = fullyClosedByPartials.size
        val profitablePartialsCloseCount = fullyClosedByPartials.count { exits ->
            exits.sumOf { it.profitUsd } >= 0.0
        }
        val totalTradesForStats = completedTrades.size + fullyClosedByPartialsCount
        val profitableCompletedCloseCount = completedTrades.count { it.profitUsd >= 0.0 }
        val profitableCloseCountForStats = profitableCompletedCloseCount + profitablePartialsCloseCount
        val tpTriggerCountForStats = fullyClosedByPartialsCount

        // Ключевые метрики считаем только по завершенным сделкам.
        val totalProfit = completedTrades.filter { it.profitUsd > 0 }.sumOf { it.profitUsd }
        val totalLoss = completedTrades.filter { it.profitUsd < 0 }.sumOf { it.profitUsd }
        val netProfit = totalProfit + totalLoss // loss отрицательный
        val totalInvested = completedTrades.sumOf { it.investedUsd }
        val totalReturn = completedTrades.sumOf { it.exitAmountUsd }
        val returnPct = if (totalInvested > 0) (netProfit / totalInvested) * 100.0 else 0.0
        val partialNetProfit = partialExits.sumOf { it.profitUsd }
        val overallNetProfit = netProfit + partialNetProfit

        val demoHistory = completedTrades.filter { !it.isRealTrade }
        val realHistory = completedTrades.filter { it.isRealTrade }

        val demoNet = demoHistory.sumOf { it.profitUsd }
        val demoInvested = demoHistory.filter { !it.isPartialExit }.sumOf { it.investedUsd }
        val demoReturn = demoHistory.filter { !it.isPartialExit }.sumOf { it.exitAmountUsd }
        val demoReturnPct = if (demoInvested > 0) (demoNet / demoInvested) * 100.0 else 0.0

        val realNet = realHistory.sumOf { it.profitUsd }
        val realInvested = realHistory.filter { !it.isPartialExit }.sumOf { it.investedUsd }
        val realReturn = realHistory.filter { !it.isPartialExit }.sumOf { it.exitAmountUsd }
        val realReturnPct = if (realInvested > 0) (realNet / realInvested) * 100.0 else 0.0
        val realTrades = realHistory.count { !it.isPartialExit }
        val realSuccessCount = realHistory.count { !it.isPartialExit && it.isSwapSuccess }
        
        return ProfitLossStatistics(
            totalTrades = totalTradesForStats,
            tpCount = profitableCloseCountForStats,
            tpTriggerCount = tpTriggerCountForStats,
            profitableCloseCount = profitableCloseCountForStats,
            slCount = slTokens.size,
            totalProfit = totalProfit,
            totalLoss = totalLoss,
            netProfit = netProfit,
            winRate = if (totalTradesForStats > 0) (profitableCloseCountForStats.toDouble() / totalTradesForStats) * 100 else 0.0,
            totalInvested = totalInvested,
            totalReturn = totalReturn,
            returnPct = returnPct,
            demoNetProfit = demoNet,
            demoInvested = demoInvested,
            demoReturn = demoReturn,
            demoReturnPct = demoReturnPct,
            realNetProfit = realNet,
            realInvested = realInvested,
            realReturn = realReturn,
            realReturnPct = realReturnPct,
            realTrades = realTrades,
            realSuccessCount = realSuccessCount,
            partialExitsCount = partialExits.size,
            partialNetProfit = partialNetProfit,
            overallNetProfit = overallNetProfit
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
        exitPrice: Double,
        profitUsd: Double,
        isRealTrade: Boolean = false,
        isSwapSuccess: Boolean = true
    ) {
        if (percent <= 0) return

        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val priceChangePercent =
            if (token.entryPrice > 0) ((exitPrice - token.entryPrice) / token.entryPrice) * 100 else 0.0
        val investedUsd = token.investedUsd * (percent / 100.0)
        val exitAmountUsd = investedUsd + profitUsd

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
            partialExitPct = percent,
            isRealTrade = isRealTrade,
            isSwapSuccess = isSwapSuccess,
            investedUsd = investedUsd,
            exitAmountUsd = exitAmountUsd
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
    val tpTriggerCount: Int,
    val profitableCloseCount: Int,
    val slCount: Int,
    val totalProfit: Double,
    val totalLoss: Double,
    val netProfit: Double,
    val winRate: Double,
    val totalInvested: Double,
    val totalReturn: Double,
    val returnPct: Double,
    val demoNetProfit: Double,
    val demoInvested: Double,
    val demoReturn: Double,
    val demoReturnPct: Double,
    val realNetProfit: Double,
    val realInvested: Double,
    val realReturn: Double,
    val realReturnPct: Double,
    val realTrades: Int,
    val realSuccessCount: Int,
    val partialExitsCount: Int,
    val partialNetProfit: Double,
    val overallNetProfit: Double
)
