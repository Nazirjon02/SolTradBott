package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.network.Candle

/**
 * Определяет тренд по свечам (Урок 2: торгуем только по направлению старшего таймфрейма).
 * UP — higher highs + higher lows, DOWN — lower highs + lower lows, иначе FLAT.
 * Если структуры мало — откат на общий наклон (первая vs последняя цена).
 */
object TrendAnalyzer {

    fun trend(candles: List<Candle>, pivotPct: Double): TrendDirection {
        if (candles.size < 2) return TrendDirection.FLAT
        val legs = LegSegmenter.segment(candles, pivotPct)

        if (legs.size >= 3) {
            val highs = legs.filter { it.direction == TrendDirection.UP }.map { it.endPrice }
            val lows = legs.filter { it.direction == TrendDirection.DOWN }.map { it.endPrice }
            if (highs.size >= 2 && lows.size >= 2) {
                val hh = highs.last() > highs[highs.size - 2]
                val hl = lows.last() > lows[lows.size - 2]
                val lh = highs.last() < highs[highs.size - 2]
                val ll = lows.last() < lows[lows.size - 2]
                when {
                    hh && hl -> return TrendDirection.UP
                    lh && ll -> return TrendDirection.DOWN
                }
            }
        }
        return slopeTrend(candles)
    }

    private fun slopeTrend(candles: List<Candle>): TrendDirection {
        val first = candles.first().close
        val last = candles.last().close
        if (first <= 0.0) return TrendDirection.FLAT
        val chg = (last - first) / first
        return when {
            chg > 0.02 -> TrendDirection.UP
            chg < -0.02 -> TrendDirection.DOWN
            else -> TrendDirection.FLAT
        }
    }
}
