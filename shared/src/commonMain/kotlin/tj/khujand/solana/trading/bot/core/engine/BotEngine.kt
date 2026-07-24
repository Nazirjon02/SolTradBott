package tj.khujand.solana.trading.bot.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.TradeNotifier
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.core.strategy.toStrategyConfig
import tj.khujand.solana.trading.bot.data.AccountBalance
import tj.khujand.solana.trading.bot.data.BotStats
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.OpenPosition
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient

private const val MAX_ACTIVE_STRATEGIES = 3

/**
 * Главный движок (порт BotEngine из MRX): старт/стоп/пауза/резюме,
 * статус в StateFlow (UI и WebSocket подписаны), до 3 стратегий параллельно.
 */
class BotEngine(
    private val client: DexClient,
    private val strategyManager: StrategyManager,
    private val notifier: TradeNotifier,
    private val db: DrxDatabase,
    private val accountCache: AccountCache,
    private val executor: TradeExecutor,
    val activityLog: ActivityLog = ActivityLog(),
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _status = MutableStateFlow(BotStatus.STOPPED)
    val status: StateFlow<BotStatus> = _status.asStateFlow()
    private var startTime: Long = 0L

    suspend fun start() {
        if (_status.value == BotStatus.RUNNING) return
        _status.value = BotStatus.RUNNING
        startTime = Clock.System.now().toEpochMilliseconds()
        notifier.send("🟢 DRX Bot запущен (${if (executor.isDemo()) "DEMO" else "REAL"})")

        val activeStrategies = db.strategyQueries.getActiveStrategies().executeAsList()
        val running = activeStrategies.take(MAX_ACTIVE_STRATEGIES)
        if (running.isEmpty()) {
            // Нечего запускать — не оставляем статус RUNNING, иначе бот «работает» впустую.
            _status.value = BotStatus.STOPPED
            activityLog.warn("⚠️ Нет активных стратегий. Включите стратегию на вкладке «Стратегии».")
            notifier.send("⚠️ Бот не запущен: нет активных стратегий")
            return
        }
        activityLog.success("🟢 Движок запущен — стратегий активно: ${running.size}")
        running.forEach { s ->
            scope.launch { strategyManager.run(s.toStrategyConfig()) }
        }
    }

    suspend fun stop() {
        strategyManager.stopAll()
        scope.coroutineContext.cancelChildren()
        _status.value = BotStatus.STOPPED
        activityLog.info("🔴 Движок остановлен")
        notifier.send("🔴 DRX Bot остановлен")
    }

    /**
     * Полное гашение движка при пересоздании runtime — НЕ обычный stop.
     * После этого объект мёртв.
     */
    fun shutdown() {
        CoroutineScope(Dispatchers.Default).launch { strategyManager.stopAll() }
        scope.cancel()
        _status.value = BotStatus.STOPPED
    }

    suspend fun pause() {
        strategyManager.stopAll()
        _status.value = BotStatus.PAUSED
        activityLog.info("⏸ Пауза — новые сделки не открываются (позиции сопровождаются)")
        notifier.send("⏸ DRX Bot приостановлен (открытые позиции сопровождаются)")
    }

    suspend fun resume() {
        if (_status.value != BotStatus.PAUSED) return
        _status.value = BotStatus.STOPPED // start() проверяет != RUNNING
        start()
        // start() мог не подняться (нет активных стратегий) и уже сказал об этом сам —
        // «возобновлён» в таком случае было бы враньём сразу после «не запущен».
        if (_status.value == BotStatus.RUNNING) notifier.send("▶️ DRX Bot возобновлён")
    }

    fun getStatus(): BotStatus = _status.value

    /** Результат «живого» включения/выключения стратегии из Telegram/UI. */
    enum class StrategyToggle {
        STARTED, STOPPED, ALREADY_RUNNING, PENDING_START, PENDING_STOP, LIMIT_REACHED
    }

    /**
     * Применяет включение/выключение стратегии «на лету».
     * Флаг is_active в БД должен быть обновлён вызывающей стороной.
     */
    suspend fun applyStrategyActive(strategyId: String, active: Boolean): StrategyToggle {
        if (active) {
            if (_status.value != BotStatus.RUNNING) return StrategyToggle.PENDING_START
            if (strategyManager.isRunning(strategyId)) return StrategyToggle.ALREADY_RUNNING
            if (strategyManager.activeCount() >= MAX_ACTIVE_STRATEGIES) return StrategyToggle.LIMIT_REACHED
            val s = db.strategyQueries.getById(strategyId).executeAsOneOrNull()
                ?: return StrategyToggle.PENDING_START
            scope.launch { strategyManager.run(s.toStrategyConfig()) }
            activityLog.success("➕ Стратегия «${s.name}» запущена")
            return StrategyToggle.STARTED
        } else {
            val wasRunning = strategyManager.isRunning(strategyId)
            strategyManager.stopStrategy(strategyId)
            return if (_status.value == BotStatus.RUNNING && wasRunning) {
                activityLog.info("➖ Стратегия остановлена")
                StrategyToggle.STOPPED
            } else {
                StrategyToggle.PENDING_STOP
            }
        }
    }

    suspend fun getStats(): BotStats {
        val all = db.tradeQueries.getStats().executeAsOne()
        val todayStart = startOfDayMillis()
        val today = db.tradeQueries.getTodayStats(todayStart).executeAsOne()
        val uptime = if (startTime > 0) formatUptime(Clock.System.now().toEpochMilliseconds() - startTime) else "—"
        return BotStats(
            totalTrades = all.total_trades,
            wins = all.wins ?: 0L,
            losses = all.losses ?: 0L,
            totalPnl = all.total_pnl ?: 0.0,
            totalFees = all.total_fees ?: 0.0,
            todayPnl = today.total_pnl ?: 0.0,
            todayWins = today.wins ?: 0L,
            todayLosses = today.losses ?: 0L,
            uptime = uptime
        )
    }

    /**
     * Баланс: DEMO — виртуальный счёт; REAL — SOL с кошелька (обновляет кеш).
     */
    suspend fun getBalance(): AccountBalance {
        val demoUsd = executor.demoBalanceUsd()
        if (executor.isDemo()) {
            return AccountBalance(totalUsd = demoUsd, demoUsd = demoUsd, isDemo = true)
        }
        val pubkey = executor.walletPublicKey()
        if (pubkey != null) {
            runCatching { accountCache.refreshSol(client, pubkey) }
                .onSuccess { activityLog.requestOk() }
                .onFailure {
                    activityLog.requestFailed()
                    activityLog.error("⚠️ Не удалось обновить баланс кошелька: ${it.message}")
                }
        }
        val sol = accountCache.get(AccountCache.COIN_SOL)
        return AccountBalance(
            totalUsd = sol?.balanceUsd ?: 0.0,
            sol = sol?.balance ?: 0.0,
            solUsd = sol?.balanceUsd ?: 0.0,
            demoUsd = demoUsd,
            isDemo = false,
        )
    }

    /** Открытые позиции с текущим PnL (цены — DexScreener, позиций мало, спама нет). */
    suspend fun getPositions(): List<OpenPosition> {
        val open = db.tradeQueries.getOpenTrades().executeAsList()
        return open.map { t ->
            val price = runCatching { client.getTokenPriceUsd(t.mint) }.getOrNull() ?: t.entry_price
            val costRemaining = t.size_usd * (t.qty_remaining / t.qty.coerceAtLeast(1e-12))
            val valueRemaining = if (t.is_demo == 1L) t.qty_remaining * price
            else costRemaining * (price / t.entry_price)
            val pnlUsd = (t.pnl ?: 0.0) + (valueRemaining - costRemaining)
            OpenPosition(
                tradeId = t.id,
                mint = t.mint,
                symbol = t.symbol,
                strategyName = t.strategy_name,
                entryPrice = t.entry_price,
                currentPrice = price,
                qtyRemaining = t.qty_remaining,
                sizeUsd = t.size_usd,
                valueUsd = kotlin.math.round(valueRemaining * 100) / 100,
                pnlUsd = kotlin.math.round(pnlUsd * 100) / 100,
                pnlPercent = if (t.size_usd > 0) kotlin.math.round(pnlUsd / t.size_usd * 1000) / 10 else 0.0,
                stopLoss = t.stop_loss,
                takeProfit = t.take_profit,
                isDemo = t.is_demo == 1L,
                openedAt = t.opened_at,
            )
        }
    }

    /** 🚨 Закрыть все позиции немедленно (рыночно, по текущей цене). */
    suspend fun closeAllPositions() {
        val open = db.tradeQueries.getOpenTrades().executeAsList()
        var closed = 0
        open.forEach { t ->
            val price = runCatching { client.getTokenPriceUsd(t.mint) }.getOrNull() ?: t.entry_price
            val result = executor.closeTrade(t, price, 100.0, "Закрыто вручную")
            if (result != null) closed++
            else activityLog.error("⚠️ ${t.symbol}: не удалось закрыть — проверьте вручную")
        }
        val failed = open.size - closed
        notifier.send(
            if (failed == 0) "🚨 Все позиции закрыты ($closed шт.)"
            else "🚨 Закрыто $closed из ${open.size} позиций — $failed не закрылись, проверьте вручную!"
        )
    }

    /** Исход ручного закрытия одной позиции — для внятного ответа в Telegram/REST. */
    enum class ClosePositionStatus { CLOSED, NOT_FOUND, NOT_OPEN, FAILED }

    data class ClosePositionResult(
        val status: ClosePositionStatus,
        val symbol: String? = null,
        val pnlUsd: Double = 0.0,
    )

    /**
     * Закрыть ОДНУ позицию вручную (рыночно, по текущей цене) по id сделки.
     * Уведомление и запись в БД — как при авто-выходе (см. TradeMonitor.closeFull).
     */
    suspend fun closePosition(tradeId: String): ClosePositionResult {
        val t = db.tradeQueries.getById(tradeId).executeAsOneOrNull()
            ?: return ClosePositionResult(ClosePositionStatus.NOT_FOUND)
        if (t.status != "OPEN") return ClosePositionResult(ClosePositionStatus.NOT_OPEN, symbol = t.symbol)

        val price = runCatching { client.getTokenPriceUsd(t.mint) }.getOrNull() ?: t.entry_price
        val result = executor.closeTrade(t, price, 100.0, "Закрыто вручную") ?: run {
            activityLog.error("⚠️ ${t.symbol}: не удалось закрыть вручную — проверьте позицию")
            return ClosePositionResult(ClosePositionStatus.FAILED, symbol = t.symbol)
        }

        val pnlUsd = kotlin.math.round(result.pnlUsd * 100) / 100
        val pnlPercent = if (t.size_usd > 0) ((t.pnl ?: 0.0) + result.pnlUsd) / t.size_usd * 100 else 0.0
        activityLog.success("🏁 ${t.symbol}: закрыта вручную, PnL $pnlUsd USD")
        notifier.sendCloseAlert(
            symbol = t.symbol,
            strategyName = t.strategy_name,
            entryPrice = t.entry_price,
            exitPrice = result.exitPrice,
            pnlUsd = pnlUsd,
            pnlPercent = kotlin.math.round(pnlPercent * 10) / 10,
            reason = "Закрыто вручную",
            isDemo = t.is_demo == 1L,
        )
        return ClosePositionResult(ClosePositionStatus.CLOSED, symbol = t.symbol, pnlUsd = pnlUsd)
    }

    private fun startOfDayMillis(): Long {
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        return today.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    }

    private fun formatUptime(ms: Long): String {
        val seconds = ms / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return "${hours}ч ${minutes}м"
    }
}
