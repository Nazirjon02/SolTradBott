package tj.khujand.solana.trading.bot.core.engine

import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow

/** Что за линия на графике бота. */
enum class TradeLineKind { ENTRY, STOP_NOW, STOP_INITIAL, ONE_R, BREAK_EVEN, TRAIL_START, PARTIAL, TAKE }

/**
 * Уровень сделки для графика.
 * @param reached уровень уже отработал (частичная фиксация взята) — рисуется приглушённо.
 */
data class TradeLine(val kind: TradeLineKind, val price: Double, val label: String, val reached: Boolean = false)

/** Строка сделки → состояние позиции для правил выхода (то же, что видит [TradeMonitor]). */
fun TradeRow.toPositionState() = PositionState(
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

/**
 * Как бот ведёт сделку — уровнями для графика (порт TradeOverlay из MRX): вход, стоп сейчас и при входе,
 * +1R, безубыток, старт трейлинга, частичные фиксации и цель. Уровни — из тех же правил стратегии, что
 * исполняет [decideExit]: что в стратегии выключено, того и на графике нет.
 */
fun tradeLines(p: PositionState, config: StrategyConfig): List<TradeLine> {
    val entry = p.entryPrice
    if (entry <= 0.0) return emptyList()
    val r = riskPerUnit(entry, p.initialStop, config.stopLossPercent)
    fun pct(v: Double) = "${fmtNum(v)}%"
    fun rMult(v: Double) = "${fmtNum(v)}R"

    return buildList {
        add(TradeLine(TradeLineKind.ENTRY, entry, "вход"))
        add(TradeLine(TradeLineKind.STOP_NOW, p.stopLoss, if (p.stopLoss >= entry) "стоп (в плюсе)" else "стоп"))
        if (p.initialStop > 0.0 && kotlin.math.abs(p.initialStop - p.stopLoss) > entry * 1e-6) {
            add(TradeLine(TradeLineKind.STOP_INITIAL, p.initialStop, "стоп при входе"))
        }
        if (r > 0.0) add(TradeLine(TradeLineKind.ONE_R, entry + r, "+1R"))
        if (config.breakEvenEnabled) {
            add(TradeLine(TradeLineKind.BREAK_EVEN, entry * (1 + config.breakEvenTriggerPercent / 100), "безубыток при +${pct(config.breakEvenTriggerPercent)}"))
        }
        if (config.trailingStopEnabled) {
            add(TradeLine(TradeLineKind.TRAIL_START, entry * (1 + config.trailingActivationPercent / 100), "трейлинг с +${pct(config.trailingActivationPercent)}"))
        }
        if (config.atrTrailEnabled && r > 0.0) {
            add(TradeLine(TradeLineKind.TRAIL_START, entry + config.atrTrailActivationR * r, "ATR-трейлинг с +${rMult(config.atrTrailActivationR)}"))
        }
        val beNote = if (config.breakEvenAfterTp1) " → БУ" else ""
        if (config.rLadderEnabled && r > 0.0) {
            add(TradeLine(TradeLineKind.PARTIAL, entry + config.tp1R * r, "TP1 +${rMult(config.tp1R)} · ${pct(config.tp1ClosePercent)}$beNote", p.tp1Done))
            add(TradeLine(TradeLineKind.PARTIAL, entry + config.tp2R * r, "TP2 +${rMult(config.tp2R)} · ${pct(config.tp2ClosePercent)}", p.tp2Done))
        } else if (config.partialTpEnabled) {
            add(TradeLine(TradeLineKind.PARTIAL, entry * (1 + config.tp1Percent / 100), "TP1 +${pct(config.tp1Percent)} · ${pct(config.tp1ClosePercent)}", p.tp1Done))
            add(TradeLine(TradeLineKind.PARTIAL, entry * (1 + config.tp2Percent / 100), "TP2 +${pct(config.tp2Percent)} · ${pct(config.tp2ClosePercent)}", p.tp2Done))
        }
        if (!config.runnerEnabled && p.takeProfit > 0.0) add(TradeLine(TradeLineKind.TAKE, p.takeProfit, "цель"))
    }
}
