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

/** Пороги ступенчатого трейлинга в «иксах» (пик / вход). */
private const val TIER2_MULTIPLE = 2.0
private const val TIER3_MULTIPLE = 5.0
private const val TIER4_MULTIPLE = 10.0

/** На сколько п.п. сужается ступенчатый трейлинг на каждой ступени «нет нового максимума». */
internal const val STALE_TIGHTEN_STEP_PCT = 10.0

/** Ниже этого отступа (% от пика) сужение не опускает трейлинг — иначе его выбьет шум. */
private const val MIN_TIGHTENED_TRAIL_PCT = 5.0

/**
 * Отступ ступенчатого трейлинга от пика, %: чем больше иксов уже взято, тем плотнее стоп
 * (до 2x — [tier1], 2–5x — [tier2], 5–10x — [tier3], от 10x — [tier4]).
 */
internal fun tieredTrailPercent(peakMultiple: Double, tier1: Double, tier2: Double, tier3: Double, tier4: Double): Double =
    when {
        peakMultiple >= TIER4_MULTIPLE -> tier4
        peakMultiple >= TIER3_MULTIPLE -> tier3
        peakMultiple >= TIER2_MULTIPLE -> tier2
        else -> tier1
    }

/**
 * Ступени сужения без нового максимума: 0 — пик свежий, 1 — прошла 1/6 срока [staleExitMinutes]
 * (4 ч из 24), 2 — половина срока (12 ч из 24). Истечение срока целиком — отдельный выход в decideExit.
 */
internal fun staleSteps(sinceHighMs: Long, staleExitMinutes: Int): Int {
    val totalMs = staleExitMinutes * 60_000L
    return when {
        totalMs <= 0L -> 0
        sinceHighMs * 2 >= totalMs -> 2
        sinceHighMs * 6 >= totalMs -> 1
        else -> 0
    }
}

/** Отступ трейлинга после [steps] ступеней сужения; не ниже 5% (и не шире исходного). */
internal fun tightenedTrailPercent(basePct: Double, steps: Int): Double =
    if (steps <= 0) basePct
    else (basePct - steps * STALE_TIGHTEN_STEP_PCT).coerceAtLeast(minOf(basePct, MIN_TIGHTENED_TRAIL_PCT))

/** ATR рабочего ТФ как доля цены; 0 — если свечей мало для расчёта. */
internal fun atrFraction(candles: List<Candle>, period: Int, price: Double): Double {
    if (price <= 0.0 || period <= 0) return 0.0
    val atr = Indicators.atr(candles, period).lastOrNull() ?: return 0.0
    val frac = atr / price
    return if (frac.isFinite() && frac > 0.0) frac else 0.0
}
