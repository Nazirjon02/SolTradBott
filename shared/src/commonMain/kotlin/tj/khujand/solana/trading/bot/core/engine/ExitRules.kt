package tj.khujand.solana.trading.bot.core.engine

import tj.khujand.solana.trading.bot.core.Indicators
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Чистые правила выходов в R (Van Tharp: R = риск на единицу = вход − начальный стоп).
 * Используются [TradeMonitor]; вынесены отдельно, чтобы покрываться тестами без БД и сети.
 */

/** Границы дистанции ATR-трейлинга от пика (доля цены): не душим шумом и не отдаём всё движение. */
private const val MIN_TRAIL_FRAC = 0.02
private const val MAX_TRAIL_FRAC = 0.5

/**
 * Риск на единицу (R). Берётся от начального стопа сделки; у старых сделок без него
 * (initial_stop = 0) — запасной вариант из конфига: вход × SL%.
 */
internal fun riskPerUnit(entry: Double, initialStop: Double, stopLossPercent: Double): Double =
    if (initialStop > 0.0 && initialStop < entry) entry - initialStop
    else entry * stopLossPercent / 100

/** Текущая прибыль в R. */
internal fun gainInR(price: Double, entry: Double, r: Double): Double =
    if (r <= 0.0) 0.0 else (price - entry) / r

/**
 * Какую ступень R-лестницы фиксировать сейчас: 1 = TP1, 2 = TP2, null = ничего.
 * TP2 берётся только после TP1 — как и в процентной лестнице.
 */
internal fun rLadderStep(gainR: Double, tp1Done: Boolean, tp2Done: Boolean, tp1R: Double, tp2R: Double): Int? = when {
    !tp1Done && gainR >= tp1R -> 1
    tp1Done && !tp2Done && gainR >= tp2R -> 2
    else -> null
}

/**
 * Chandelier-стоп (LeBeau) в мультипликативной форме: пик × (1 − mult × ATR%входа).
 * ATR хранится как доля цены на входе, поэтому дистанция растёт вместе с ценой и не требует
 * дополнительных запросов свечей (лимит GeckoTerminal ~30/мин).
 * Нет ATR (старые сделки, мало свечей на входе) → [fallbackFrac] (трейлинг-% конфига).
 */
internal fun atrTrailStop(peak: Double, atrFrac: Double, mult: Double, fallbackFrac: Double): Double {
    val frac = if (atrFrac > 0.0) atrFrac * mult else fallbackFrac
    return peak * (1 - frac.coerceIn(MIN_TRAIL_FRAC, MAX_TRAIL_FRAC))
}

/** ATR рабочего ТФ как доля цены; 0 — если свечей мало для расчёта. */
internal fun atrFraction(candles: List<Candle>, period: Int, price: Double): Double {
    if (price <= 0.0 || period <= 0) return 0.0
    val atr = Indicators.atr(candles, period).lastOrNull() ?: return 0.0
    val frac = atr / price
    return if (frac.isFinite() && frac > 0.0) frac else 0.0
}
