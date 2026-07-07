package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.math.abs

/**
 * Уровни (Урок 2): свинговые экстремумы, уровни крупного бара, разворотные точки.
 * Близкие уровни сливаются, накапливая число касаний (зеркальный/сильный уровень).
 */
object LevelDetector {

    fun detect(candles: List<Candle>, pivotPct: Double): List<Level> {
        if (candles.size < 3) return emptyList()
        val legs = LegSegmenter.segment(candles, pivotPct)
        val raw = ArrayList<Level>()

        // Свинговые экстремумы — концы ног (разворотные точки).
        legs.forEach { leg ->
            raw.add(Level(leg.endPrice, kind = "reversal"))
        }

        // Уровни крупного бара — high/low баров с аномально большим телом.
        val avgBody = candles.map { it.body }.average()
        if (avgBody > 0) {
            candles.forEach { c ->
                if (c.body > avgBody * 3) {
                    raw.add(Level(c.high, kind = "big_bar"))
                    raw.add(Level(c.low, kind = "big_bar"))
                }
            }
        }

        return mergeLevels(raw, tol = 0.004)
    }

    /** Ближайшее сопротивление выше цены. */
    fun nearestResistance(levels: List<Level>, price: Double): Level? =
        levels.filter { it.price > price }.minByOrNull { it.price - price }

    /**
     * Цель тейк-профита (Урок 2): доля хода от [price] до ближайшего сопротивления сверху.
     * Например, цена 100 и сопротивление 110 → 0.10 (TP на +10%).
     * null — если сопротивления выше нет (пробой к новым максимумам) или цена некорректна.
     */
    fun takeProfitFrac(levels: List<Level>, price: Double): Double? {
        if (price <= 0.0) return null
        val res = nearestResistance(levels, price) ?: return null
        val frac = (res.price - price) / price
        return if (frac > 0.0) frac else null
    }

    /** Ближайшая поддержка ниже цены. */
    fun nearestSupport(levels: List<Level>, price: Double): Level? =
        levels.filter { it.price < price }.maxByOrNull { it.price }

    /** Все поддержки (уровни ниже цены), отсортированы сверху вниз. */
    fun supports(levels: List<Level>, price: Double): List<Level> =
        levels.filter { it.price <= price }.sortedByDescending { it.price }

    private fun mergeLevels(levels: List<Level>, tol: Double): List<Level> {
        if (levels.isEmpty()) return emptyList()
        val sorted = levels.sortedBy { it.price }
        val out = ArrayList<Level>()
        for (l in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.price > 0 && abs(l.price - last.price) / last.price <= tol) {
                // Сливаем: усредняем цену, суммируем касания, зеркальный при 2+ касаниях.
                val touches = last.touches + l.touches
                out[out.size - 1] = last.copy(
                    price = (last.price + l.price) / 2.0,
                    kind = if (touches >= 2) "mirror" else last.kind,
                    touches = touches,
                )
            } else {
                out.add(l)
            }
        }
        return out
    }
}
