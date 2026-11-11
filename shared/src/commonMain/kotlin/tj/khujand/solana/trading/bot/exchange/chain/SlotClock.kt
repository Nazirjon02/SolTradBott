package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.math.roundToLong

/**
 * Время блока по номеру слота.
 *
 * `logsSubscribe` сообщает только слот. Точное время блока знают события PumpSwap (поле timestamp —
 * Clock::unix_timestamp, то же, что отдаёт getBlockTime и по чему GeckoTerminal раскладывает сделки по
 * барам), — они и служат опорами: в списке наблюдения их сотни в час. Сделки Raydium получают время
 * интерполяцией между ближайшими опорами (между ними наклон берётся по самим опорам, слот ≈ 400 мс),
 * без опор вовсе — null, и вызывающий берёт время получения уведомления.
 *
 * Время блока — целые секунды, поэтому оценка округляется до секунды.
 */
class SlotClock(private val maxAnchors: Int = 4_096) {
    // Слот → время блока, сек; слоты по возрастанию.
    private val slots = ArrayList<Long>()
    private val times = ArrayList<Long>()

    val size: Int get() = slots.size

    /** Опора: в слоте [slot] время блока [timeSec]. */
    fun anchor(slot: Long, timeSec: Long) {
        val i = slots.binarySearch(slot)
        if (i >= 0) return // у всех транзакций слота одно время блока
        val at = -(i + 1)
        slots.add(at, slot)
        times.add(at, timeSec)
        if (slots.size > maxAnchors) {
            slots.removeAt(0)
            times.removeAt(0)
        }
    }

    /** Оценка времени блока слота [slot], мс; null — опор нет. */
    fun estimateMs(slot: Long): Long? {
        if (slots.isEmpty()) return null
        val i = slots.binarySearch(slot)
        if (i >= 0) return times[i] * 1000
        val at = -(i + 1)
        val sec = when {
            at in 1 until slots.size -> {
                val s0 = slots[at - 1]
                val s1 = slots[at]
                val t0 = times[at - 1]
                val t1 = times[at]
                t0 + (t1 - t0).toDouble() * (slot - s0) / (s1 - s0)
            }
            at == 0 -> times[0] - (slots[0] - slot) * msPerSlot() / 1000.0
            else -> times.last() + (slot - slots.last()) * msPerSlot() / 1000.0
        }
        return sec.roundToLong() * 1000
    }

    /** Длительность слота по опорам (нужна, когда слот за краем опор); по умолчанию 400 мс. */
    fun msPerSlot(): Double {
        if (slots.size < 2) return DEFAULT_MS_PER_SLOT
        val ds = slots.last() - slots.first()
        if (ds < MIN_SPAN_SLOTS) return DEFAULT_MS_PER_SLOT
        return ((times.last() - times.first()) * 1000.0 / ds).coerceIn(300.0, 600.0)
    }

    companion object {
        const val DEFAULT_MS_PER_SLOT = 400.0

        /** На коротком отрезке целые секунды опор дают грубый наклон. */
        private const val MIN_SPAN_SLOTS = 150L
    }
}
