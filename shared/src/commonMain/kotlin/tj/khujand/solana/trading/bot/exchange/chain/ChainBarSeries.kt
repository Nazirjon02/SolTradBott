package tj.khujand.solana.trading.bot.exchange.chain

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/** Сделка с ценой в USD — вход сборщика свечей. [order] — порядок сделок (слот и номер прихода). */
data class PricedTrade(val timeMs: Long, val order: Long, val priceUsd: Double, val volumeUsd: Double)

/** Откуда бар. */
enum class BarSource {
    /** Собран из цепочки, подписка была жива весь бар. */
    CHAIN,

    /** Из цепочки, но часть бара подписки не было (начало подписки, обрыв) — сделки могли пропасть. */
    PARTIAL,

    /** Бар GeckoTerminal: история до подписки или замена неполного бара. */
    GECKO,
}

/**
 * Бар серии.
 * @param trades сделок из цепочки в баре (у баров GeckoTerminal — 0).
 * @param openReliable open = close предыдущего бара, и между ними подписка ничего не пропустила.
 */
data class ChainBar(val candle: Candle, val source: BarSource, val trades: Int, val openReliable: Boolean)

/**
 * Отрезки времени, когда подписка на пул была жива и сделки приходили все.
 * Бар считается полным, только если его целиком покрывает один отрезок.
 */
class Coverage {
    private val spans = ArrayList<LongArray>()

    val isOpen: Boolean get() = spans.lastOrNull()?.let { it[1] == Long.MAX_VALUE } ?: false

    /** Подписка подтверждена: сделки идут с [fromMs]. */
    fun open(fromMs: Long) {
        if (isOpen) return
        spans += longArrayOf(fromMs, Long.MAX_VALUE)
        if (spans.size > MAX_SPANS) spans.removeAt(0)
    }

    /** Подписки больше нет (обрыв, отписка): сделки после [toMs] могли не дойти. */
    fun close(toMs: Long) {
        val last = spans.lastOrNull() ?: return
        if (last[1] != Long.MAX_VALUE) return
        if (toMs <= last[0]) spans.removeAt(spans.lastIndex) else last[1] = toMs
    }

    fun covers(fromMs: Long, toMs: Long): Boolean = spans.any { it[0] <= fromMs && toMs <= it[1] }

    /** Начало текущего отрезка; null — подписки нет. */
    fun openSince(): Long? = spans.lastOrNull()?.takeIf { it[1] == Long.MAX_VALUE }?.get(0)

    private companion object {
        const val MAX_SPANS = 256
    }
}

/**
 * Бары одного пула и одного таймфрейма по правилу GeckoTerminal (сверено с его сделками и свечами 2026-11-11):
 * open = close предыдущего бара (последняя сделка до начала бара), high/low включают этот open, close —
 * последняя сделка бара, объём — сумма сделок в USD; интервал без сделок бара не даёт.
 *
 * Сделки из цепочки копятся по барам; бары GeckoTerminal (история до подписки и замена неполных баров
 * после обрыва) лежат отдельно, а общая серия собирается в [bars] с учётом [Coverage]: полный бар — из
 * цепочки, неполный — от GeckoTerminal, если он есть, иначе неполный из цепочки.
 */
class ChainBarSeries(val barMs: Long, private val maxBars: Int = 1_500) {
    private class Agg(trade: PricedTrade) {
        var first = trade.priceUsd
        var firstOrder = trade.order
        var last = trade.priceUsd
        var lastOrder = trade.order
        var lastTimeMs = trade.timeMs
        var high = trade.priceUsd
        var low = trade.priceUsd
        var volume = 0.0
        var trades = 0
    }

    private val aggs = HashMap<Long, Agg>()
    private val gecko = HashMap<Long, Candle>()

    fun openOf(timeMs: Long): Long = timeMs - timeMs.mod(barMs)

    fun add(trade: PricedTrade) {
        if (trade.priceUsd <= 0.0 || trade.priceUsd.isNaN() || trade.priceUsd.isInfinite()) return
        val key = openOf(trade.timeMs)
        val a = aggs.getOrPut(key) { Agg(trade) }
        if (trade.order < a.firstOrder) {
            a.first = trade.priceUsd
            a.firstOrder = trade.order
        }
        if (trade.order >= a.lastOrder) {
            a.last = trade.priceUsd
            a.lastOrder = trade.order
            a.lastTimeMs = trade.timeMs
        }
        if (trade.priceUsd > a.high) a.high = trade.priceUsd
        if (trade.priceUsd < a.low) a.low = trade.priceUsd
        a.volume += trade.volumeUsd
        a.trades++
        prune()
    }

    /** Бары GeckoTerminal (закрытые, по возрастанию). Свои полные бары они не заменяют — см. [bars]. */
    fun putGecko(candles: List<Candle>) {
        for (c in candles) gecko[openOf(c.openTimeMs)] = c
        prune()
    }

    /** Бары из цепочки (как есть, без правила open) — для сверки. */
    fun chainTradesIn(openMs: Long): Int = aggs[openMs]?.trades ?: 0

    /**
     * Серия: закрытые бары по возрастанию (с [includeOpen] — и текущий незакрытый последним).
     * Бар закрыт, когда с его конца прошло [closeLagMs]: сделка в «confirmed» приходит через 1–2 с после блока.
     */
    fun bars(nowMs: Long, coverage: Coverage, includeOpen: Boolean = false, closeLagMs: Long = CLOSE_LAG_MS): List<ChainBar> {
        val keys = (aggs.keys + gecko.keys).sorted()
        val out = ArrayList<ChainBar>(keys.size)
        var prev: ChainBar? = null
        var prevLastTradeMs = 0L
        for (k in keys) {
            val closed = k + barMs <= nowMs - closeLagMs
            if (!closed && !includeOpen) continue
            val a = aggs[k]
            val g = gecko[k]
            val complete = coverage.covers(k, k + barMs)
            val bar: ChainBar
            if (a != null && (complete || g == null)) {
                val open = prev?.candle?.close ?: a.first
                val reliable = prev != null && coverage.covers(prevLastTradeMs, k)
                bar = ChainBar(
                    Candle(k, open, maxOf(a.high, open), minOf(a.low, open), a.last, a.volume),
                    if (complete) BarSource.CHAIN else BarSource.PARTIAL,
                    a.trades,
                    reliable,
                )
                prevLastTradeMs = a.lastTimeMs
            } else {
                val c = g ?: continue
                bar = ChainBar(c, BarSource.GECKO, 0, openReliable = true)
                prevLastTradeMs = k + barMs
            }
            out += bar
            prev = bar
        }
        return out
    }

    /** Свечи для потребителя (стратегий — когда сверка позволит): закрытые бары по правилу GeckoTerminal. */
    fun candles(nowMs: Long, coverage: Coverage, includeOpen: Boolean = false): List<Candle> =
        bars(nowMs, coverage, includeOpen).map { it.candle }

    private fun prune() {
        if (aggs.size > maxBars) aggs.keys.sorted().take(aggs.size - maxBars).forEach { aggs.remove(it) }
        if (gecko.size > maxBars) gecko.keys.sorted().take(gecko.size - maxBars).forEach { gecko.remove(it) }
    }

    companion object {
        /** Через сколько после конца бара он считается закрытым. */
        const val CLOSE_LAG_MS = 5_000L
    }
}
