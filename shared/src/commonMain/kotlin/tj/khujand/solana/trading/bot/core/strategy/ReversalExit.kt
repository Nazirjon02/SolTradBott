package tj.khujand.solana.trading.bot.core.strategy

import kotlin.math.abs
import tj.khujand.solana.trading.bot.core.Indicators
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Сколько свечей рабочего ТФ берёт проверка разворота (EMA(50) + структура свингов). Одинаково в боте
 * и в бэктесте: EMA засевается средним первых баров окна, и другое окно дало бы другую среднюю.
 */
const val REVERSAL_CANDLES = 150

/** Причина закрытия сделки по развороту — в журнале, Telegram и бэктесте. */
const val REVERSAL_EXIT_REASON = "Разворот рынка"

/** Направление по пересечению EMA. */
enum class EmaTrend { BULLISH, BEARISH, NEUTRAL }

/**
 * Тренд по пересечению EMA [fastPeriod]/[slowPeriod] закрытий (порт detectTrend из MRX): быстрая выше
 * медленной больше чем на 0.1% — BULLISH, ниже — BEARISH, ближе — NEUTRAL (средние сплелись).
 */
fun emaTrend(candles: List<Candle>, fastPeriod: Int = 21, slowPeriod: Int = 50): EmaTrend {
    if (candles.size < slowPeriod + 2) return EmaTrend.NEUTRAL
    val closes = candles.map { it.close }
    val fast = Indicators.ema(closes, fastPeriod).lastOrNull() ?: return EmaTrend.NEUTRAL
    val slow = Indicators.ema(closes, slowPeriod).lastOrNull() ?: return EmaTrend.NEUTRAL
    if (slow <= 0.0) return EmaTrend.NEUTRAL
    val spreadPct = abs(fast - slow) / slow * 100
    return when {
        spreadPct < 0.1 -> EmaTrend.NEUTRAL
        fast > slow -> EmaTrend.BULLISH
        else -> EmaTrend.BEARISH
    }
}

/** Свинг: бар [index] — локальный максимум ([isHigh]) или минимум с ценой [price]. */
data class SwingPivot(val index: Int, val price: Double, val isHigh: Boolean)

/**
 * Фрактальные свинги (порт zigzag из MRX): максимум (минимум) бара строго выше (ниже) [k] соседей с
 * каждой стороны. Подряд идущие свинги одного вида схлопываются в более экстремальный, поэтому максимумы
 * и минимумы чередуются. Последние [k] баров свингом ещё не стали — у них нет правых соседей.
 */
fun fractalPivots(candles: List<Candle>, k: Int): List<SwingPivot> {
    val raw = ArrayList<SwingPivot>()
    for (i in k until candles.size - k) {
        var isHigh = true
        var isLow = true
        for (j in i - k..i + k) {
            if (j == i) continue
            if (candles[j].high >= candles[i].high) isHigh = false
            if (candles[j].low <= candles[i].low) isLow = false
        }
        when {
            isHigh -> raw.add(SwingPivot(i, candles[i].high, isHigh = true))
            isLow -> raw.add(SwingPivot(i, candles[i].low, isHigh = false))
        }
    }
    val alternating = ArrayList<SwingPivot>()
    for (p in raw) {
        val last = alternating.lastOrNull()
        if (last == null || last.isHigh != p.isHigh) {
            alternating.add(p)
        } else {
            val better = if (p.isHigh) p.price > last.price else p.price < last.price
            if (better) alternating[alternating.size - 1] = p
        }
    }
    return alternating
}

/**
 * Разворот рынка против лонга (порт marketReversedAgainst из MRX; бот спотовый — только лонг): движение,
 * по которому входили, закончилось, и выйти пора раньше, чем сделку догонит стоп — сценарий «был плюс →
 * тренд сменился → ушёл в минус».
 *
 * Только по закрытым барам, иначе решение перерисовывалось бы внутри бара. Нужны ОБА признака, чтобы
 * одиночный прокол EMA или единичный свинг не выбрасывал из живой сделки:
 *  1. EMA(21) ниже EMA(50) ([emaTrend] = BEARISH);
 *  2. структура сломана вниз: последний максимум ниже предыдущего И последний минимум ниже предыдущего.
 *
 * Отличие от MRX: самый свежий из этих двух свингов должен сложиться не раньше бара входа [sinceMs].
 * Dars покупает конец коррекции, и нисходящая структура, которая была на графике ДО входа, — это то, что
 * стратегия сознательно купила, а не разворот против сделки.
 */
fun marketReversedAgainstLong(closedCandles: List<Candle>, sinceMs: Long, swingK: Int = 3): Boolean {
    if (closedCandles.size < 10) return false
    if (emaTrend(closedCandles) != EmaTrend.BEARISH) return false

    val pivots = fractalPivots(closedCandles, swingK)
    val highs = pivots.filter { it.isHigh }
    val lows = pivots.filter { !it.isHigh }
    if (highs.size < 2 || lows.size < 2) return false
    val lastHigh = highs.last()
    val lastLow = lows.last()
    val brokenDown = lastHigh.price < highs[highs.size - 2].price && lastLow.price < lows[lows.size - 2].price
    if (!brokenDown) return false
    return closedCandles[maxOf(lastHigh.index, lastLow.index)].openTimeMs >= sinceMs
}

/** Свечи без формирующейся: последняя ещё не закрылась к [nowMs] — её экстремум не окончателен. */
fun closedBarsOnly(candles: List<Candle>, barMs: Long, nowMs: Long): List<Candle> {
    val last = candles.lastOrNull() ?: return candles
    return if (last.openTimeMs + barMs > nowMs) candles.dropLast(1) else candles
}
