package tj.khujand.solana.trading.bot.core.risk

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.engine.fmtNum
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.util.formatLocalTime

/** Скользящее окно, в котором ищется худшая сделка для проверки просадки. */
private const val DRAWDOWN_WINDOW_MS = 24 * 60 * 60_000L

/** Окно недельного лимита убытка портфеля. */
private const val WEEK_MS = 7 * 24 * 60 * 60_000L

/**
 * Риск-менеджер (порт из MRX): перед каждым входом проверяет
 * дневной убыток, просадку и лимит открытых позиций стратегии,
 * а также лимиты портфеля — все стратегии текущего режима вместе (см. [ProtectionSettings]).
 *
 * @param isDemo база для расчётов зависит от режима: DEMO — виртуальный счёт,
 *   REAL — реальный баланс кошелька (демо-счёт исключается). По умолчанию DEMO.
 * @param protection глобальные настройки защиты (лимиты портфеля); читаются на каждой проверке.
 */
class RiskManager(
    private val db: DrxDatabase,
    private val accountCache: AccountCache,
    private val isDemo: () -> Boolean = { true },
    private val protection: () -> ProtectionSettings = { ProtectionSettings() },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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

    // ─── Портфель: все стратегии текущего режима вместе ─────────────────────

    /** Слот под вход: [Granted] держат до записи сделки и отпускают [releaseEntry]. */
    sealed interface EntrySlot {
        data class Granted(val id: Long) : EntrySlot
        data class Denied(val reason: String) : EntrySlot
    }

    // Входы, прошедшие лимиты, но ещё не записанные в БД (идёт котировка или своп).
    private val reservationsLock = Mutex()
    private val reservations = mutableMapOf<Long, Double>()
    private var nextReservationId = 1L

    /**
     * Причина, по которой портфель сейчас не пускает новые входы, или null. Без размера — предпроверка
     * цикла стратегии (позиции, убытки, пауза после серии убытков), с размером — ещё и сумма в позициях.
     */
    fun portfolioBlockReason(newSizeUsd: Double = 0.0): String? =
        portfolioBlock(protection(), portfolioSnapshot(), newSizeUsd, nowMs())

    /**
     * Занимает слот под вход: проверка лимитов и бронь — под одним локом, а брони считаются открытыми
     * позициями. Иначе стратегии, параллельно входящие в разные монеты, увидели бы один и тот же
     * свободный слот и вошли обе. Слот обязательно отпустить [releaseEntry] — к тому моменту сделка
     * уже записана в БД (и считается там) или не открылась.
     */
    suspend fun reserveEntry(sizeUsd: Double): EntrySlot = reservationsLock.withLock {
        val base = portfolioSnapshot()
        val withReserved = base.copy(
            openPositions = base.openPositions + reservations.size,
            exposureUsd = base.exposureUsd + reservations.values.sum(),
        )
        when (val reason = portfolioBlock(protection(), withReserved, sizeUsd, nowMs())) {
            null -> EntrySlot.Granted(nextReservationId++).also { reservations[it.id] = sizeUsd }
            else -> EntrySlot.Denied(reason)
        }
    }

    suspend fun releaseEntry(slot: EntrySlot) {
        if (slot is EntrySlot.Granted) reservationsLock.withLock { reservations.remove(slot.id) }
    }

    /** Срез портфеля текущего режима: открытые сделки по последней цене монитора, баланс, итоги. */
    internal fun portfolioSnapshot(): PortfolioSnapshot {
        val demo = isDemo()
        val flag = if (demo) 1L else 0L
        var exposure = 0.0
        var marketValue = 0.0
        var openPnl = 0.0
        val open = db.tradeQueries.getOpenTradesByMode(flag).executeAsList()
        for (t in open) {
            val cost = t.size_usd * (t.qty_remaining / t.qty.coerceAtLeast(1e-12))
            val price = t.last_price.takeIf { it > 0 } ?: t.entry_price
            // DEMO хранит qty в целых токенах, REAL — в raw-единицах: стоимость REAL — через доходность.
            val value = if (t.is_demo == 1L) t.qty_remaining * price else cost * (price / t.entry_price)
            exposure += cost
            marketValue += value
            openPnl += (t.pnl ?: 0.0) + (value - cost)
        }
        // Покупки «в полёте» (журнал REAL): сделки ещё нет, но слот они уже заняли.
        val inFlight = if (demo) 0 else db.txJournalQueries.pendingBuyCount().executeAsOne().toInt()
        val streakLimit = protection().lossStreakLimit.coerceAtLeast(0).toLong()
        val now = nowMs()
        return PortfolioSnapshot(
            openPositions = open.size + inFlight,
            exposureUsd = exposure,
            marketValueUsd = marketValue,
            openPnlUsd = openPnl,
            freeBalanceUsd = if (demo) accountCache.demoUsd() else accountCache.realUsd(),
            realizedTodayUsd = db.tradeQueries.realizedPnlSince(flag, startOfDayMillis()).executeAsOne(),
            realizedWeekUsd = db.tradeQueries.realizedPnlSince(flag, now - WEEK_MS).executeAsOne(),
            recentClosed = if (streakLimit == 0L) emptyList()
            else db.tradeQueries.recentClosed(flag, streakLimit).executeAsList().map { it.pnl to it.closed_at },
        )
    }
}

/**
 * Срез портфеля для лимитов.
 * @param exposureUsd себестоимость остатков открытых позиций; [marketValueUsd] — их стоимость по
 *   последней цене; [openPnlUsd] — нереализованный PnL плюс уже зафиксированные частичные выходы.
 * @param recentClosed последние закрытые сделки (PnL и время закрытия), новые первыми.
 */
internal data class PortfolioSnapshot(
    val openPositions: Int,
    val exposureUsd: Double,
    val marketValueUsd: Double,
    val openPnlUsd: Double,
    val freeBalanceUsd: Double,
    val realizedTodayUsd: Double,
    val realizedWeekUsd: Double,
    val recentClosed: List<Pair<Double?, Long>>,
) {
    /** Капитал: свободный баланс + позиции по рынку. */
    val equityUsd: Double get() = freeBalanceUsd + marketValueUsd
}

/**
 * Лимиты портфеля: причина блокировки входа или null. Убытки — закрытые сделки плюс открытые по
 * последней цене (позиция в минусе тоже съедает лимит). Капитал неизвестен (0) — %-лимиты пропускаются.
 */
internal fun portfolioBlock(s: ProtectionSettings, p: PortfolioSnapshot, newSizeUsd: Double, nowMs: Long): String? {
    if (!s.portfolioEnabled) return null
    if (s.maxOpenPositions > 0 && p.openPositions >= s.maxOpenPositions) {
        return "открыто позиций ${p.openPositions} из ${s.maxOpenPositions} (все стратегии)"
    }
    lossStreakPauseUntil(p.recentClosed, s.lossStreakLimit, s.lossStreakPauseMinutes)?.let { until ->
        if (nowMs < until) return "${s.lossStreakLimit} убыточных сделок подряд — пауза до ${formatLocalTime(until)}"
    }
    val equity = p.equityUsd
    if (equity <= 0.0) return null
    val dayPnl = p.realizedTodayUsd + p.openPnlUsd
    if (s.maxDailyLossPct > 0 && dayPnl <= -equity * s.maxDailyLossPct / 100) {
        return "убыток портфеля за день ${fmtNum(dayPnl)} USD — лимит ${fmtNum(s.maxDailyLossPct)}% капитала"
    }
    val weekPnl = p.realizedWeekUsd + p.openPnlUsd
    if (s.maxWeeklyLossPct > 0 && weekPnl <= -equity * s.maxWeeklyLossPct / 100) {
        return "убыток портфеля за 7 дней ${fmtNum(weekPnl)} USD — лимит ${fmtNum(s.maxWeeklyLossPct)}% капитала"
    }
    if (s.maxExposurePct > 0 && newSizeUsd > 0) {
        val after = p.exposureUsd + newSizeUsd
        val limit = equity * s.maxExposurePct / 100
        if (after > limit) {
            return "в позициях будет ${fmtNum(after)} USD — больше ${fmtNum(s.maxExposurePct)}% капитала (${fmtNum(limit)} USD)"
        }
    }
    return null
}

/**
 * Пауза после серии убытков: если [limit] последних закрытых сделок ([recent], новые первыми) все
 * убыточные — входы на паузе до «последний убыток + [pauseMinutes]». Сделка без PnL серию прерывает.
 * null — паузы нет.
 */
internal fun lossStreakPauseUntil(recent: List<Pair<Double?, Long>>, limit: Int, pauseMinutes: Int): Long? {
    if (limit <= 0 || pauseMinutes <= 0 || recent.size < limit) return null
    val streak = recent.take(limit)
    if (streak.any { (pnl, _) -> pnl == null || pnl >= 0.0 }) return null
    return streak.first().second + pauseMinutes * 60_000L
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
