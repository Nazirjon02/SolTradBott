package tj.khujand.solana.trading.bot.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.TradeNotifier
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.toStrategyConfig
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.exchange.dex.DexClient

/**
 * Монитор сопровождения позиций (роль как у TradeMonitor в MRX, но с важным отличием:
 * на DEX нет биржевых SL/TP — бот сам обязан исполнять выходы).
 *
 * Раз в [intervalMs] по каждой OPEN-сделке проверяются в порядке приоритета:
 *  1) ликвидность-стоп — экстренный выход при осушении пула (rug pull);
 *  2) Stop Loss (включая подтянутый trailing'ом/break-even'ом);
 *  3) основной Take Profit (в режиме «раннер» не закрывает позицию);
 *  4) partial TP1/TP2 — частичные фиксации (в % или R-лестница, она проверяется до основного TP);
 *  5) break-even — перенос SL в безубыток (по % прибыли или после TP1);
 *  6) trailing stop — подтягивание SL за пиком цены (по %, по ATR — Chandelier, или ступенчатый «по иксам»);
 *  7) time-stop — принудительный выход по времени; «нет нового максимума» — выход, если пик давно не обновлялся.
 *
 * Продажа не прошла — следующая попытка с бо́льшим slippage; с [EXIT_BLOCKED_AFTER] неудач подряд позиция
 * «продажа заблокирована»: попытки идут с паузой, в Telegram — одно предупреждение.
 * Каждый цикл начинается со сверки журнала REAL-транзакций (поздние подтверждения, потерянные ответы).
 *
 * Работает независимо от статуса движка (позиции сопровождаются и на паузе).
 */
class TradeMonitor(
    private val client: DexClient,
    private val db: DrxDatabase,
    private val executor: TradeExecutor,
    private val notifier: TradeNotifier,
    private val activityLog: ActivityLog = ActivityLog(),
    private val intervalMs: Long = 15_000L,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // При запуске (в т.ч. после перезапуска приложения) сообщаем, сколько
            // открытых позиций из БД взято на сопровождение — чтобы это было видно в UI.
            val resumed = runCatching { db.tradeQueries.getOpenCountTotal().executeAsOne() }.getOrDefault(0L)
            if (resumed > 0) {
                activityLog.info("↩️ Подхвачено открытых позиций: $resumed — сопровождаю")
            }
            while (isActive) {
                try {
                    checkOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    activityLog.warn("⚠️ Монитор сделок: ${e.message ?: "ошибка проверки"}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Полная остановка (закрывает scope) — при пересоздании runtime/выходе из приложения. */
    fun close() {
        scope.cancel()
    }

    suspend fun checkOnce() {
        reconcileJournal()
        val open = db.tradeQueries.getOpenTrades().executeAsList()
        if (open.isEmpty()) return
        for (t in open) {
            try {
                checkTrade(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activityLog.warn("⚠️ ${t.symbol}: ошибка сопровождения — ${e.message}")
            }
        }
    }

    /** Итоги REAL-транзакций, которые не дождались в момент отправки, — в ленту и Telegram. */
    private suspend fun reconcileJournal() {
        val messages = try {
            executor.reconcileJournal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            activityLog.warn("⚠️ Сверка журнала транзакций: ${e.message ?: "ошибка"}")
            return
        }
        for (m in messages) {
            activityLog.warn(m)
            notifier.send(m)
        }
    }

    private suspend fun checkTrade(t: TradeRow) {
        val config = db.strategyQueries.getById(t.strategy_id).executeAsOneOrNull()?.toStrategyConfig()
            ?: fallbackConfig(t)

        // Цена и ликвидность: детали пары надёжнее, fallback — цена по mint.
        val pair = runCatching { client.getPairDetails(t.pair_address) }.getOrNull()
        val price = pair?.priceUsd?.toDoubleOrNull()
            ?: runCatching { client.getTokenPriceUsd(t.mint) }.getOrNull()
            ?: run {
                activityLog.requestFailed()
                return // цены нет — не трогаем позицию (лучше опоздать, чем закрыть вслепую)
            }
        activityLog.requestOk()
        if (price <= 0) return
        // Последняя цена — для лимитов портфеля (нереализованный PnL без лишних запросов).
        db.tradeQueries.updateLastPrice(price, t.id)
        applyExits(t, config, price, pair?.liquidity?.usd)
    }

    /**
     * Правила выхода по уже полученной цене (вынесено из [checkTrade] — тестируется без сети).
     * Решение принимает общая чистая [decideExit] (её же использует бэктест), здесь — только
     * запись в БД, исполнение и лента активности.
     * @param liquidity текущая ликвидность пула, null — неизвестна (ликвидность-стоп пропускается).
     */
    internal suspend fun applyExits(t: TradeRow, config: StrategyConfig, price: Double, liquidity: Double?) {
        val now = Clock.System.now().toEpochMilliseconds()
        val d = decideExit(t.toPositionState(), config, price, liquidity, now)

        if (d.peak > t.peak_price) db.tradeQueries.updatePeak(d.peak, d.peakAtMs, t.id)
        if (d.stop > t.stop_loss) {
            db.tradeQueries.updateStopLoss(d.stop, t.id)
            if (d.breakEvenMoved) activityLog.info("🛡 ${t.symbol}: SL перенесён в безубыток (${fmtNum(d.stop)})")
        }

        // Продажа уже не прошла несколько раз подряд — до назначенного времени не повторяем (пик и стоп
        // выше всё равно обновлены, решение о выходе пересчитается на следующей попытке).
        if (d.action != ExitAction.Hold && now < t.exit_retry_at) return

        when (val action = d.action) {
            ExitAction.Hold -> Unit
            is ExitAction.CloseAll -> closeFull(t, price, action.reason)
            is ExitAction.ClosePart -> {
                val result = closePartial(t, price, action.portionOfOriginal, action.reason, action.tp1, action.tp2)
                // Остаток сразу защищаем безубытком — не ждём следующего цикла монитора.
                val stopAfter = action.stopAfter
                if (result != null && !result.isFull && stopAfter != null && stopAfter > d.stop) {
                    db.tradeQueries.updateStopLoss(stopAfter, t.id)
                    activityLog.info("🛡 ${t.symbol}: после TP1 SL в безубыток (${fmtNum(stopAfter)})")
                }
            }
        }
    }

    private fun TradeRow.toPositionState() = PositionState(
        entryPrice = entry_price,
        initialStop = initial_stop,
        stopLoss = stop_loss,
        takeProfit = take_profit,
        peakPrice = peak_price,
        tp1Done = tp1_done == 1L,
        tp2Done = tp2_done == 1L,
        entryLiquidityUsd = entry_liquidity_usd,
        entryAtrFrac = entry_atr_frac,
        openedAtMs = opened_at,
        peakAtMs = peak_at,
    )

    private suspend fun closeFull(t: TradeRow, price: Double, reason: String) {
        val result = executor.closeTrade(t, price, 100.0, reason) ?: run {
            onCloseFailed(t, reason)
            return
        }
        if (result.soldNothing) {
            notifier.send(
                "⚠️ ${t.symbol}: токенов на кошельке нет — позиция закрыта без продажи ($reason). " +
                    "Похоже, монету продали вне бота: PnL этой сделки неизвестен."
            )
            return
        }
        val pnlPercent = if (t.size_usd > 0) ((t.pnl ?: 0.0) + result.pnlUsd) / t.size_usd * 100 else 0.0
        activityLog.success("🏁 ${t.symbol}: закрыта ($reason), PnL ${fmtNum(result.pnlUsd)} USD")
        notifier.sendCloseAlert(
            symbol = t.symbol,
            strategyName = t.strategy_name,
            entryPrice = t.entry_price,
            exitPrice = result.exitPrice,
            pnlUsd = kotlin.math.round(result.pnlUsd * 100) / 100,
            pnlPercent = kotlin.math.round(pnlPercent * 10) / 10,
            reason = reason,
            isDemo = t.is_demo == 1L,
        )
    }

    private suspend fun closePartial(
        t: TradeRow,
        price: Double,
        portion: Double,
        reason: String,
        tp1: Boolean,
        tp2: Boolean,
    ): CloseResult? {
        val result = executor.closeTrade(t, price, portion, reason, tp1Done = tp1, tp2Done = tp2) ?: run {
            onCloseFailed(t, reason)
            return null
        }
        if (result.soldNothing) {
            notifier.send("⚠️ ${t.symbol}: токенов на кошельке нет — позиция закрыта без продажи ($reason).")
            return result
        }
        activityLog.success("💰 ${t.symbol}: частичная фиксация ${portion.toInt()}% ($reason), PnL ${fmtNum(result.pnlUsd)} USD")
        notifier.send(
            "💰 ЧАСТИЧНАЯ ФИКСАЦИЯ ${if (t.is_demo == 1L) "(DEMO)" else ""} ${t.symbol}\n" +
                "$reason — продано ${portion.toInt()}% позиции, PnL ${fmtNum(result.pnlUsd)} USD"
        )
        return result
    }

    /**
     * Выход не состоялся. Причины разные: позицию уже закрыли вручную (UI/Telegram/REST) — не сбой;
     * продажа «в полёте» — ждём сверку; продажа не прошла — исполнитель уже записал неудачу, а на
     * [EXIT_BLOCKED_AFTER]-й подряд шлём одно предупреждение: дальше попытки идут с паузой.
     */
    private suspend fun onCloseFailed(t: TradeRow, reason: String) {
        val fresh = db.tradeQueries.getById(t.id).executeAsOneOrNull() ?: return
        if (fresh.status != "OPEN" || executor.hasPendingSell(t.id)) return
        if (fresh.exit_failures.toInt() == EXIT_BLOCKED_AFTER && fresh.exit_failures > t.exit_failures) {
            val text = "🚫 ${t.symbol}: продажа не проходит $EXIT_BLOCKED_AFTER раза подряд ($reason) — ${fresh.exit_note}. " +
                "Бот продолжит попытки с паузой и растущим slippage; проверьте монету вручную."
            activityLog.error(text)
            notifier.send(text)
        }
    }

    /** Стратегию удалили, а сделка ещё открыта — сопровождаем с дефолтными параметрами. */
    private fun fallbackConfig(t: TradeRow) = StrategyConfig(id = t.strategy_id, name = t.strategy_name)
}
