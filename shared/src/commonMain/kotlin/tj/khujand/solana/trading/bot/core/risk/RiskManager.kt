package tj.khujand.solana.trading.bot.core.risk

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache

/**
 * Риск-менеджер (порт из MRX): перед каждым входом проверяет
 * дневной убыток, просадку и лимит открытых позиций стратегии.
 *
 * @param isDemo база для расчётов зависит от режима: DEMO — виртуальный счёт,
 *   REAL — реальный баланс кошелька (демо-счёт исключается). По умолчанию DEMO.
 */
class RiskManager(
    private val db: DrxDatabase,
    private val accountCache: AccountCache,
    private val isDemo: () -> Boolean = { true },
) {

    /** Торговая база в USD: демо-счёт в DEMO-режиме, реальный кошелёк — в REAL. */
    private fun baseBalanceUsd(): Double =
        if (isDemo()) accountCache.demoUsd() else accountCache.realUsd()

    fun canTrade(config: StrategyConfig): Boolean =
        checkDailyLoss(config) && checkDrawdown(config) && checkMaxPositions(config)

    /** Причина блокировки для логов/Telegram, null = торговать можно. */
    fun blockReason(config: StrategyConfig): String? = when {
        !checkDailyLoss(config) -> "дневной лимит убытка ${config.maxDailyLoss}% достигнут"
        !checkDrawdown(config) -> "просадка превысила ${config.maxDrawdown}%"
        !checkMaxPositions(config) -> "лимит позиций (${config.maxPositions}) достигнут"
        else -> null
    }

    private fun checkDailyLoss(config: StrategyConfig): Boolean {
        val todayStart = startOfDayMillis()
        val todayPnl = db.tradeQueries.getTodayPnl(config.id, todayStart).executeAsOne()
        val balance = baseBalanceUsd().takeIf { it > 0 } ?: 1000.0
        val maxLossAmount = balance * config.maxDailyLoss / 100
        return todayPnl > -maxLossAmount
    }

    private fun checkDrawdown(config: StrategyConfig): Boolean {
        val maxDrawdown = db.tradeQueries.getMaxDrawdown(config.id).executeAsOneOrNull() ?: 0.0
        return maxDrawdown > -config.maxDrawdown
    }

    private fun checkMaxPositions(config: StrategyConfig): Boolean {
        val openCount = db.tradeQueries.getOpenCount(config.id).executeAsOne()
        return openCount < config.maxPositions
    }

    /** Размер позиции в USD: % от торговой базы (спот, без плеча). */
    fun calculatePositionSizeUsd(config: StrategyConfig): Double {
        val balance = baseBalanceUsd().takeIf { it > 0 } ?: return 0.0
        return balance * config.positionSize / 100
    }

    private fun startOfDayMillis(): Long {
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        return today.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    }
}
