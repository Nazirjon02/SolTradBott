package tj.khujand.solana.trading.bot.core.strategy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.TradeNotifier
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.core.engine.TradeExecutor
import tj.khujand.solana.trading.bot.core.engine.fmtNum
import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.exchange.dex.TokenScanner

/** Интервал одного цикла сканирования (мемкоины живут быстро — 30с как у скальпинга MRX). */
private const val SCAN_INTERVAL_MS = 30_000L

/** Минимальная уверенность сигнала для входа (как в MRX). */
private const val MIN_CONFIDENCE = 0.6

/**
 * Максимальный допустимый разбег между ценой кандидата из кеша сканера и живой ценой
 * в момент входа. Кеш живёт до 5 минут — на мемкоинах цена за это время успевает уйти,
 * а от неё считаются qty, SL и TP. Разбег больше порога = «цена убежала», вход отменяем.
 */
private const val MAX_ENTRY_PRICE_DRIFT = 0.03

/** Предел размера карты пер-mint локов, чтобы за месяцы работы она не росла бесконечно. */
private const val MINT_LOCKS_LIMIT = 500

/**
 * Менеджер стратегий (модель MRX): держит по джобу на активную стратегию.
 * Цикл: сканер кандидатов → свечи → анализ → риск-чек → вход (DEMO/REAL/только сигнал).
 */
class StrategyManager(
    private val client: DexClient,
    private val riskManager: RiskManager,
    private val notifier: TradeNotifier,
    private val db: DrxDatabase,
    private val scanner: TokenScanner,
    private val executor: TradeExecutor,
    private val activityLog: ActivityLog = ActivityLog(),
    private val settingsStore: SettingsStore? = null,
) {
    /**
     * Режим «только сигнал» (глобальный переключатель из настроек/Telegram).
     * Когда включён — бот НЕ открывает сделки, а лишь шлёт сигнал с параметрами входа.
     */
    private val _signalOnly = MutableStateFlow(settingsStore?.getSignalOnly() ?: false)
    val signalOnly: StateFlow<Boolean> = _signalOnly.asStateFlow()

    fun setSignalOnly(value: Boolean) {
        _signalOnly.value = value
        settingsStore?.setSignalOnly(value)
    }

    private val activeJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()

    // По одному локу на mint — чтобы две стратегии не купили один токен одновременно.
    private val mintLocks = mutableMapOf<String, Mutex>()

    // Кулдаун после сделки per-strategy: не лезем в рынок очередями.
    private val lastTradeAt = mutableMapOf<String, Long>()

    // Дедуп сигналов в режиме «только сигнал»: mint → время последнего алерта.
    private val lastSignalAt = mutableMapOf<String, Long>()

    // Троттлинг ошибок в Telegram: при обрыве сети циклы не должны слать десятки сообщений.
    private var lastErrorNotifiedAt: Long = 0L
    private val errorNotifyMinIntervalMs = 60_000L

    private suspend fun mintLock(mint: String): Mutex = mutex.withLock {
        // Сбрасываем незанятые локи, когда карта разрослась: бот живёт месяцами,
        // а каждый новый mint иначе оставался бы в памяти навсегда.
        if (mintLocks.size >= MINT_LOCKS_LIMIT) {
            mintLocks.entries.removeAll { !it.value.isLocked && it.key != mint }
        }
        mintLocks.getOrPut(mint) { Mutex() }
    }

    private suspend fun notifyErrorThrottled(text: String) {
        val now = now()
        val send = mutex.withLock {
            if (now - lastErrorNotifiedAt < errorNotifyMinIntervalMs) false
            else { lastErrorNotifiedAt = now; true }
        }
        if (send) notifier.send(text)
    }

    suspend fun run(config: StrategyConfig) {
        val strategy = createStrategy(config)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val job = scope.launchScanLoop(config, strategy)
        mutex.withLock { activeJobs[config.id] = job }
    }

    /** Один цикл стратегии: кандидаты сканера → анализ каждого → возможный вход → пауза. */
    private fun CoroutineScope.launchScanLoop(config: StrategyConfig, strategy: Strategy): Job = launch {
        activityLog.success("▶️ Стратегия запущена: ${config.name} (${config.timeframe})")
        while (isActive) {
            try {
                scanOnce(config, strategy)
                delay(SCAN_INTERVAL_MS + Random.nextLong(0L, 5_000L))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activityLog.requestFailed()
                activityLog.error("⚠️ ${config.name}: ошибка цикла — ${e.message ?: "нет соединения"}")
                notifyErrorThrottled("⚠️ Ошибка стратегии ${config.name}: ${e.message}")
                delay(30_000L + Random.nextLong(0L, 15_000L))
            }
        }
    }

    private suspend fun scanOnce(config: StrategyConfig, strategy: Strategy) {
        // Кулдаун после последней сделки этой стратегии.
        val last = mutex.withLock { lastTradeAt[config.id] } ?: 0L
        val sinceLast = now() - last
        if (sinceLast < config.cooldownSeconds * 1000L) {
            activityLog.info("⏸ ${config.name}: кулдаун ещё ${(config.cooldownSeconds - sinceLast / 1000)}с")
            return
        }

        // Риск-лимиты (в режиме «только сигнал» не проверяем — сделок нет).
        if (!_signalOnly.value) {
            // Размер позиции считается из кеша баланса — в REAL его нужно освежить,
            // иначе кеш пустой/протухший и вход отваливается с «нулевым размером».
            executor.refreshRealBalanceIfStale()
            val block = riskManager.blockReason(config)
            if (block != null) {
                activityLog.warn("⏸ ${config.name}: $block")
                return
            }
        }

        activityLog.info("🔍 ${config.name}: сканирую кандидатов…")
        val candidates = scanner.scan(config.scanFilters())
        activityLog.requestOk()
        if (candidates.isEmpty()) {
            activityLog.info("○ ${config.name}: подходящих токенов нет")
            return
        }
        activityLog.success("✓ ${config.name}: кандидатов ${candidates.size}")

        // «Одна монета — одна сделка»: пропускаем все монеты, по которым уже была сделка
        // в текущем режиме (demo/real) — открытая ИЛИ уже закрытая. Повторно не входим.
        val demoFlag = if (executor.isDemo()) 1L else 0L
        val tradedMints = db.tradeQueries.tradedMints(demoFlag).executeAsList().toSet()

        for (candidate in candidates) {
            if (candidate.mint in tradedMints) continue

            val candles = runCatching {
                client.getCandles(candidate.pairAddress, config.timeframe, config.darsCandleLimit)
            }.getOrDefault(emptyList())

            val signal = strategy.analyze(candidate, candles, emptyList()) ?: continue

            // Общий фильтр входа: не покупаем у верха диапазона (спот, только лонг).
            // Опционален и настраивается на стратегии; при выключенном/недостаточных данных — пропускает.
            if (!config.rangeAllowsEntry(candles, signal.entryPrice)) {
                activityLog.info("○ ${candidate.symbol}: цена у верха диапазона — пропуск (range-фильтр)")
                continue
            }

            if (signal.confidence < MIN_CONFIDENCE) {
                activityLog.info("○ ${candidate.symbol}: уверенность ${(signal.confidence * 100).toInt()}% < 60%")
                continue
            }

            if (_signalOnly.value) {
                emitSignalOnly(signal, config)
                continue
            }

            // Сериализуем вход по монете + перепроверка внутри лока (защита от гонки
            // между стратегиями и от повторного входа в уже торговавшуюся монету).
            val opened = mintLock(candidate.mint).withLock {
                val alreadyTraded = db.tradeQueries.hasTradedMint(candidate.mint, demoFlag).executeAsOne() > 0L
                if (alreadyTraded) return@withLock false
                if (!riskManager.canTrade(config)) return@withLock false
                val fresh = withFreshEntryPrice(signal) ?: return@withLock false
                executeSignal(fresh, config, candidate)
            }
            if (opened) {
                mutex.withLock { lastTradeAt[config.id] = now() }
                return // одна сделка за цикл — дальше кулдаун
            }
        }
    }

    /**
     * Перед входом заменяет цену кандидата (из кеша сканера, до 5 минут давности) на живую.
     * SL/TP тянутся за ней пропорционально, поэтому проценты риска сохраняются.
     * Если цена ушла больше чем на [MAX_ENTRY_PRICE_DRIFT] — сетап уже не тот, вход отменяем.
     * Живой цены нет → идём по цене сканера (лучше вход по старой цене, чем пропуск вслепую).
     */
    private suspend fun withFreshEntryPrice(signal: Signal): Signal? {
        if (signal.entryPrice <= 0.0) return null
        val live = try {
            client.getTokenPriceUsd(signal.mint)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (live == null || live <= 0.0) return signal

        val drift = abs(live - signal.entryPrice) / signal.entryPrice
        if (drift > MAX_ENTRY_PRICE_DRIFT) {
            activityLog.info(
                "○ ${signal.symbol}: цена ушла на ${(drift * 100).toInt()}% с момента скана — вход отменён"
            )
            return null
        }
        val k = live / signal.entryPrice
        return signal.copy(
            entryPrice = live,
            stopLoss = signal.stopLoss * k,
            takeProfit = signal.takeProfit * k,
        )
    }

    private suspend fun executeSignal(signal: Signal, config: StrategyConfig, candidate: TokenCandidate): Boolean {
        val sizeUsd = riskManager.calculatePositionSizeUsd(config)
        if (sizeUsd <= 0) {
            activityLog.warn("⚠️ ${config.name}: нулевой размер позиции (нет баланса?)")
            return false
        }
        activityLog.success(
            "⚡ ${signal.symbol}: сигнал BUY (${(signal.confidence * 100).toInt()}%) — открываю на $${fmtNum(sizeUsd)}"
        )
        val tradeId = executor.openTrade(signal, config, sizeUsd, candidate.liquidityUsd) ?: return false
        notifier.sendOpenAlert(
            symbol = signal.symbol,
            strategyName = config.name,
            entryPrice = signal.entryPrice,
            sizeUsd = kotlin.math.round(sizeUsd * 100) / 100,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit,
            isDemo = executor.isDemo(),
            reason = signal.reason,
        )
        return tradeId.isNotEmpty()
    }

    /** Режим «только сигнал»: шлём алерт с параметрами входа, дедуп по mint раз в 30 минут. */
    private suspend fun emitSignalOnly(signal: Signal, config: StrategyConfig) {
        val now = now()
        val send = mutex.withLock {
            val prev = lastSignalAt[signal.mint] ?: 0L
            if (now - prev < 30 * 60_000L) false
            else { lastSignalAt[signal.mint] = now; true }
        }
        if (!send) return
        activityLog.success("📣 ${signal.symbol}: сигнал (только уведомление, сделка не открыта)")
        notifier.send(
            "📣 СИГНАЛ (без сделки) ${signal.symbol}\n" +
                "Стратегия: ${config.name}\n" +
                "Цена: ${signal.entryPrice}\nSL: ${signal.stopLoss} | TP: ${signal.takeProfit}\n" +
                "Уверенность: ${(signal.confidence * 100).toInt()}%\n${signal.reason}\n" +
                "mint: ${signal.mint}"
        )
    }

    suspend fun stopStrategy(strategyId: String) {
        mutex.withLock {
            activeJobs[strategyId]?.cancel()
            activeJobs.remove(strategyId)
        }
    }

    /** true, если по стратегии сейчас крутится сканер (есть активный job). */
    suspend fun isRunning(strategyId: String): Boolean =
        mutex.withLock { activeJobs[strategyId]?.isActive == true }

    /** Сколько стратегий сейчас реально сканируют. */
    suspend fun activeCount(): Int =
        mutex.withLock { activeJobs.count { it.value.isActive } }

    suspend fun stopAll() {
        mutex.withLock {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()
}
