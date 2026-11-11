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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient

/**
 * Сколько стратегий движок крутит одновременно. У каждой свой цикл скана (~30 с), а пул монет и свечи общие:
 * монета на одном ТФ — один запрос свечей на бар, сколько бы стратегий её ни смотрело. Потолок — защита от
 * случайно наплодённых копий, а не от встроенных стратегий: раньше он был 3, и из шести включённых молча
 * работали первые три.
 */
private const val MAX_ACTIVE_STRATEGIES = 10

/**
 * Главный движок (порт BotEngine из MRX): старт/стоп/пауза/резюме,
 * статус в StateFlow (UI и WebSocket подписаны), до [MAX_ACTIVE_STRATEGIES] стратегий параллельно.
 */
class BotEngine(
    private val client: DexClient,
    private val strategyManager: StrategyManager,
    private val notifier: TradeNotifier,
    private val db: DrxDatabase,
    private val accountCache: AccountCache,
    private val executor: TradeExecutor,
    private val settingsStore: SettingsStore,
    val activityLog: ActivityLog = ActivityLog(),
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _status = MutableStateFlow(BotStatus.STOPPED)
    val status: StateFlow<BotStatus> = _status.asStateFlow()
    private var startTime: Long = 0L

    /** Когда движок последний раз запущен (мс); 0 — ещё не запускался. Для `/health`. */
    val startedAt: Long get() = startTime

    // Остановка по лимиту убытка идёт из цикла стратегии, а stop() гасит и циклы стратегий, и детей
    // [scope] — поэтому отдельный scope, который stop() не отменяет. Лок — несколько стратегий могут
    // увидеть лимит в одну секунду, остановка и сообщение нужны один раз.
    private val controlScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val limitStopLock = Mutex()

    init {
        strategyManager.onHardStop = { reason -> controlScope.launch { stopByLossLimit(reason) } }
    }

    private suspend fun stopByLossLimit(reason: String) = limitStopLock.withLock {
        if (_status.value != BotStatus.RUNNING) return@withLock
        stop(
            note = "🛑 DRX Bot остановлен: $reason.\nОткрытые позиции сопровождаются. Запуск — только вручную " +
                "(/start или «🟢 Старт»), когда разберётесь с причиной."
        )
    }

    suspend fun start() {
        if (_status.value == BotStatus.RUNNING) return
        _status.value = BotStatus.RUNNING
        startTime = Clock.System.now().toEpochMilliseconds()

        val activeStrategies = db.strategyQueries.getActiveStrategies().executeAsList()
        val running = activeStrategies.take(MAX_ACTIVE_STRATEGIES)
        if (running.isEmpty()) {
            // Нечего запускать — не оставляем статус RUNNING, иначе бот «работает» впустую.
            _status.value = BotStatus.STOPPED
            settingsStore.setDesiredState(BotStatus.STOPPED.name)
            activityLog.warn("⚠️ Нет активных стратегий. Включите стратегию на вкладке «Стратегии».")
            notifier.send("⚠️ Бот не запущен: нет активных стратегий")
            return
        }
        // Фиксируем «желаемое состояние» — чтобы после kill'а процесса сервис возобновил торговлю.
        settingsStore.setDesiredState(BotStatus.RUNNING.name)
        activityLog.success("🟢 Движок запущен — стратегий активно: ${running.size}")
        val skipped = activeStrategies.drop(MAX_ACTIVE_STRATEGIES)
        if (skipped.isNotEmpty()) {
            activityLog.warn(
                "⚠️ Включено стратегий: ${activeStrategies.size}, одновременно работают не больше $MAX_ACTIVE_STRATEGIES — " +
                    "не запущены: ${skipped.joinToString { "«${it.name}»" }}"
            )
        }
        // «Запущен» — только когда реально есть что запускать: раньше это уходило в Telegram
        // до проверки, и сразу за ним следовало «⚠️ Бот не запущен».
        notifier.send("🟢 DRX Bot запущен (${executor.modeLabel()})")
        running.forEach { s ->
            scope.launch { strategyManager.run(s.toStrategyConfig()) }
        }
    }

    /** @param note сообщение в Telegram вместо обычного «остановлен» (остановка по лимиту убытка). */
    suspend fun stop(note: String? = null) {
        strategyManager.stopAll()
        scope.coroutineContext.cancelChildren()
        _status.value = BotStatus.STOPPED
        settingsStore.setDesiredState(BotStatus.STOPPED.name)
        activityLog.info("🔴 Движок остановлен")
        notifier.send(note ?: "🔴 DRX Bot остановлен")
    }

    /**
     * Полное гашение движка при пересоздании runtime — НЕ обычный stop.
     * После этого объект мёртв.
     */
    fun shutdown() {
        CoroutineScope(Dispatchers.Default).launch { strategyManager.stopAll() }
        scope.cancel()
        controlScope.cancel()
        _status.value = BotStatus.STOPPED
    }

    suspend fun pause() {
        strategyManager.stopAll()
        _status.value = BotStatus.PAUSED
        settingsStore.setDesiredState(BotStatus.PAUSED.name)
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
            val s = db.strategyQueries.getById(strategyId).executeAsOneOrNull()
                ?: return StrategyToggle.PENDING_START
            if (strategyManager.activeCount() >= MAX_ACTIVE_STRATEGIES) {
                // Экран стратегий результат не показывает — без строки в ленте отказ был бы незаметен.
                activityLog.warn("⚠️ «${s.name}» не запущена: одновременно работают не больше $MAX_ACTIVE_STRATEGIES стратегий")
                return StrategyToggle.LIMIT_REACHED
            }
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

    /**
     * Применяет сохранённую правку (или удаление) стратегии к уже работающему сканеру.
     * Цикл стратегии держит снимок конфига со своего старта: без перезапуска новые фильтры/SL/TP
     * не действовали бы до рестарта бота, а удалённая стратегия продолжала бы открывать сделки.
     * Не запущенную стратегию не трогаем — она возьмёт конфиг из БД при старте/включении.
     */
    suspend fun reloadStrategy(strategyId: String) {
        if (!strategyManager.isRunning(strategyId)) return
        strategyManager.stopStrategy(strategyId)
        val s = db.strategyQueries.getById(strategyId).executeAsOneOrNull()
        if (s == null || s.is_active == 0L || _status.value != BotStatus.RUNNING) {
            activityLog.info("➖ Стратегия остановлена")
            return
        }
        scope.launch { strategyManager.run(s.toStrategyConfig()) }
        activityLog.info("🔄 «${s.name}»: новые параметры применены, сканер перезапущен")
    }

    /**
     * Итоги текущего режима (DEMO/REAL) с начала идущего забега («Новый старт»), без него — за всю историю
     * режима. «Сегодня» — с начала дня или забега, что позже.
     */
    suspend fun getStats(): BotStats {
        val flag = if (executor.isDemo()) 1L else 0L
        val runStart = db.runStartedAt(executor.isDemo())
        val all = db.tradeQueries.getStatsByModeSince(flag, runStart).executeAsOne()
        val today = db.tradeQueries.getStatsByModeSince(flag, maxOf(startOfDayMillis(), runStart)).executeAsOne()
        val uptime = if (startTime > 0) formatUptime(Clock.System.now().toEpochMilliseconds() - startTime) else "—"
        return BotStats(
            totalTrades = all.total_trades,
            wins = all.wins ?: 0L,
            losses = all.losses ?: 0L,
            totalPnl = all.total_pnl,
            totalFees = all.total_fees,
            todayPnl = today.total_pnl,
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
                exitFailures = t.exit_failures.toInt(),
                exitNote = t.exit_note,
                execMode = t.exec_mode,
            )
        }
    }

    /**
     * Цена для ручного закрытия. DEMO исполняется ровно по этой цене, поэтому без живой цены
     * закрывать нельзя (null): подстановка цены входа записала бы выдуманный нулевой PnL.
     * REAL продаёт через Jupiter и считает выход по фактической выручке — там цена лишь подсказка.
     */
    private suspend fun manualClosePrice(t: TradeRow): Double? {
        val live = runCatching { client.getTokenPriceUsd(t.mint) }.getOrNull()?.takeIf { it > 0 }
        return live ?: if (t.is_demo == 1L) null else t.entry_price
    }

    /** 🚨 Закрыть все позиции немедленно (рыночно, по текущей цене). */
    suspend fun closeAllPositions() {
        val open = db.tradeQueries.getOpenTrades().executeAsList()
        var closed = 0
        open.forEach { t ->
            val price = manualClosePrice(t)
            val result = price?.let { executor.closeTrade(t, it, 100.0, "Закрыто вручную", manual = true) }
            when {
                result != null -> closed++
                // Уже закрыта параллельно (монитором по стопу/тейку) — цель достигнута.
                !executor.isOpen(t.id) -> closed++
                price == null -> activityLog.error("⚠️ ${t.symbol}: нет текущей цены — не закрыта, повторите позже")
                else -> activityLog.error("⚠️ ${t.symbol}: не удалось закрыть — проверьте вручную")
            }
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

        val price = manualClosePrice(t) ?: run {
            activityLog.error("⚠️ ${t.symbol}: нет текущей цены — позиция не закрыта, повторите позже")
            return ClosePositionResult(ClosePositionStatus.FAILED, symbol = t.symbol)
        }
        val result = executor.closeTrade(t, price, 100.0, "Закрыто вручную", manual = true) ?: run {
            // Пока ждали лок, позицию закрыл монитор (стоп/тейк) — сообщаем «уже закрыта», а не сбой.
            if (!executor.isOpen(t.id)) return ClosePositionResult(ClosePositionStatus.NOT_OPEN, symbol = t.symbol)
            val why = if (executor.hasPendingSell(t.id)) "продажа уже отправлена, ждём подтверждения"
            else "проверьте позицию"
            activityLog.error("⚠️ ${t.symbol}: не удалось закрыть вручную — $why")
            return ClosePositionResult(ClosePositionStatus.FAILED, symbol = t.symbol)
        }
        // Токенов на кошельке не было — позиция просто снята с сопровождения, выручки нет.
        if (result.soldNothing) {
            notifier.send("⚠️ ${t.symbol}: токенов на кошельке нет — позиция закрыта без продажи (PnL неизвестен)")
            return ClosePositionResult(ClosePositionStatus.CLOSED, symbol = t.symbol)
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
