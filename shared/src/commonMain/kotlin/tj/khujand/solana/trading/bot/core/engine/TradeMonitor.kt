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
 *  3) основной Take Profit;
 *  4) partial TP1/TP2 — частичные фиксации;
 *  5) break-even — перенос SL в безубыток;
 *  6) trailing stop — подтягивание SL за пиком цены;
 *  7) time-stop — принудительный выход по времени.
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
        val liquidity = pair?.liquidity?.usd

        // 1. Ликвидность-стоп (rug pull): пул осушён — выходим немедленно.
        if (liquidity != null && t.entry_liquidity_usd > 0) {
            val dropPct = (1 - liquidity / t.entry_liquidity_usd) * 100
            if (dropPct >= config.liquidityExitDropPercent) {
                closeFull(t, price, "Ликвидность-стоп (-${dropPct.toInt()}% пула)")
                return
            }
        }

        // Пик цены — база trailing'а.
        val peak = maxOf(t.peak_price, price)
        if (peak > t.peak_price) db.tradeQueries.updatePeakPrice(peak, t.id)

        var stopLoss = t.stop_loss
        val gainPct = (price - t.entry_price) / t.entry_price * 100

        // 5. Break-even: после X% прибыли SL переносится в безубыток + offset.
        if (config.breakEvenEnabled && gainPct >= config.breakEvenTriggerPercent) {
            val beStop = t.entry_price * (1 + config.breakEvenOffsetPercent / 100)
            if (beStop > stopLoss) {
                stopLoss = beStop
                db.tradeQueries.updateStopLoss(stopLoss, t.id)
                activityLog.info("🛡 ${t.symbol}: SL перенесён в безубыток (${fmtNum(stopLoss)})")
            }
        }

        // 6. Trailing: после активации SL тянется за пиком.
        if (config.trailingStopEnabled && gainPct >= config.trailingActivationPercent) {
            val trailStop = peak * (1 - config.trailingStopPercent / 100)
            if (trailStop > stopLoss) {
                stopLoss = trailStop
                db.tradeQueries.updateStopLoss(stopLoss, t.id)
            }
        }

        // 2. Stop Loss (обычный / trailing / break-even — какой выше).
        if (price <= stopLoss) {
            val reason = when {
                stopLoss > t.entry_price -> "Trailing Stop"
                else -> "Stop Loss"
            }
            closeFull(t, price, reason)
            return
        }

        // 3. Основной Take Profit.
        if (price >= t.take_profit) {
            closeFull(t, price, "Take Profit")
            return
        }

        // 4. Partial TP: фиксируем доли на промежуточных целях.
        if (config.partialTpEnabled) {
            if (t.tp1_done == 0L && gainPct >= config.tp1Percent) {
                closePartial(t, price, config.tp1ClosePercent, "TP1 +${config.tp1Percent}%", tp1 = true, tp2 = false)
                return
            }
            if (t.tp1_done == 1L && t.tp2_done == 0L && gainPct >= config.tp2Percent) {
                closePartial(t, price, config.tp2ClosePercent, "TP2 +${config.tp2Percent}%", tp1 = true, tp2 = true)
                return
            }
        }

        // 7. Time-stop: позиция висит слишком долго.
        if (config.timeStopMinutes > 0) {
            val ageMinutes = (Clock.System.now().toEpochMilliseconds() - t.opened_at) / 60_000
            if (ageMinutes >= config.timeStopMinutes) {
                closeFull(t, price, "Time-stop (${ageMinutes}м)")
            }
        }
    }

    private suspend fun closeFull(t: TradeRow, price: Double, reason: String) {
        val result = executor.closeTrade(t, price, 100.0, reason) ?: run {
            activityLog.error("❌ ${t.symbol}: не удалось закрыть ($reason) — попробую в следующем цикле")
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

    private suspend fun closePartial(t: TradeRow, price: Double, portion: Double, reason: String, tp1: Boolean, tp2: Boolean) {
        val result = executor.closeTrade(t, price, portion, reason, tp1Done = tp1, tp2Done = tp2) ?: return
        activityLog.success("💰 ${t.symbol}: частичная фиксация ${portion.toInt()}% ($reason), PnL ${fmtNum(result.pnlUsd)} USD")
        notifier.send(
            "💰 ЧАСТИЧНАЯ ФИКСАЦИЯ ${if (t.is_demo == 1L) "(DEMO)" else ""} ${t.symbol}\n" +
                "$reason — продано ${portion.toInt()}% позиции, PnL ${fmtNum(result.pnlUsd)} USD"
        )
    }

    /** Стратегию удалили, а сделка ещё открыта — сопровождаем с дефолтными параметрами. */
    private fun fallbackConfig(t: TradeRow) = StrategyConfig(id = t.strategy_id, name = t.strategy_name)
}
