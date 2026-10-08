package tj.khujand.solana.trading.bot.core.risk

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache

/** Скользящее окно, в котором ищется худшая сделка для проверки просадки. */
private const val DRAWDOWN_WINDOW_MS = 24 * 60 * 60_000L

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
        !checkDrawdown(config) -> "просадка за сутки превысила ${config.maxDrawdown}%"
        !checkMaxPositions(config) -> "лимит позиций (${config.maxPositions}) достигнут"
        else -> null
    }

    private fun checkDailyLoss(config: StrategyConfig): Boolean {
        if (!config.maxDailyLossEnabled) return true // лимит отключён пользователем
        val todayStart = startOfDayMillis()
        val todayPnl = db.tradeQueries.getTodayPnl(config.id, todayStart).executeAsOne()
        val balance = baseBalanceUsd().takeIf { it > 0 } ?: 1000.0
        val maxLossAmount = balance * config.maxDailyLoss / 100
        return todayPnl > -maxLossAmount
    }

    /**
     * Худшая закрытая сделка за последние сутки хуже -maxDrawdown% → вход блокируется.
     * Окно обязательно: запрос без него смотрел на всю историю, поэтому один стоп-лосс
     * (а SL по умолчанию равен maxDrawdown) выключал стратегию НАВСЕГДА.
     */
    private fun checkDrawdown(config: StrategyConfig): Boolean {
        if (!config.maxDrawdownEnabled) return true // лимит отключён пользователем
        val windowStart = Clock.System.now().toEpochMilliseconds() - DRAWDOWN_WINDOW_MS
        val maxDrawdown = db.tradeQueries.getMaxDrawdown(config.id, windowStart).executeAsOneOrNull() ?: 0.0
        return maxDrawdown > -config.maxDrawdown
    }

    private fun checkMaxPositions(config: StrategyConfig): Boolean {
        val openCount = db.tradeQueries.getOpenCount(config.id).executeAsOne()
        return openCount < config.maxPositions
    }

    /**
     * Размер позиции в USD (спот, без плеча): % от торговой базы, а при
     * [StrategyConfig.riskSizingEnabled] — от риска сделки (см. [positionSizeUsd]).
     * @param entry цена входа, @param stop цена стопа, @param liquidityUsd ликвидность пула —
     *   нужны только для размера от риска; без них (0) берётся обычный % баланса.
     */
    fun calculatePositionSizeUsd(
        config: StrategyConfig,
        entry: Double = 0.0,
        stop: Double = 0.0,
        liquidityUsd: Double = 0.0,
    ): Double {
        val balance = baseBalanceUsd().takeIf { it > 0 } ?: return 0.0
        return positionSizeUsd(balance, config, entry, stop, liquidityUsd)
    }

    private fun startOfDayMillis(): Long {
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        return today.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    }
}

/**
 * Размер позиции в USD от баланса.
 *
 * - Выключен [StrategyConfig.riskSizingEnabled] → `баланс × positionSize%` (прежнее поведение).
 * - Включён → модель percent-risk (Van Tharp): `баланс × riskPerTradePercent% / расстояние до стопа`,
 *   чтобы стоп-аут стоил одинаковый % баланса при любой ширине стопа. Ограничения сверху:
 *   `positionSize%` баланса и `maxPoolSharePercent%` ликвидности пула — в CPMM-пуле средний
 *   слиппедж ≈ 2 × размер / ликвидность, поэтому крупный вход в тонкий пул съедает прибыль.
 *   Без корректного стопа (stop ≥ entry или нули) — просто `positionSize%`.
 */
internal fun positionSizeUsd(
    balance: Double,
    config: StrategyConfig,
    entry: Double,
    stop: Double,
    liquidityUsd: Double,
): Double {
    if (balance <= 0.0) return 0.0
    val cap = balance * config.positionSize / 100
    if (!config.riskSizingEnabled) return cap

    val stopFrac = if (entry > 0.0 && stop > 0.0 && stop < entry) (entry - stop) / entry else 0.0
    var size = if (stopFrac > 0.0) balance * config.riskPerTradePercent / 100 / stopFrac else cap
    size = minOf(size, cap)
    if (liquidityUsd > 0.0 && config.maxPoolSharePercent > 0.0) {
        size = minOf(size, liquidityUsd * config.maxPoolSharePercent / 100)
    }
    return size
}
