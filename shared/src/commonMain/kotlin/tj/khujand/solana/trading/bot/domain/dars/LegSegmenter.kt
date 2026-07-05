package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Разбивает свечи на ноги (импульсы/коррекции) методом ZigZag: новый пивот фиксируется,
 * когда цена развернулась от локального экстремума более чем на [pivotPct] процентов.
 *
 * Для каждой ноги считаются 4 признака методики (Урок 1):
 * скорость (размер хода на бар), размер баров, где закрываются бары, объём.
 */
object LegSegmenter {

    fun segment(candles: List<Candle>, pivotPct: Double): List<Leg> {
        if (candles.size < 3) return emptyList()
        val closes = candles.map { it.close }
        val pivots = findPivots(closes, (pivotPct.coerceAtLeast(0.1)) / 100.0)
        if (pivots.size < 2) return emptyList()

        val legs = ArrayList<Leg>(pivots.size - 1)
        for (k in 0 until pivots.size - 1) {
            val s = pivots[k]
            val e = pivots[k + 1]
            if (e <= s) continue
            val slice = candles.subList(s, e + 1)
            val startP = candles[s].close
            val endP = candles[e].close
            val dir = if (endP >= startP) TrendDirection.UP else TrendDirection.DOWN

            val volumes = slice.map { it.volume }
            val avgVol = if (volumes.isEmpty()) 0.0 else volumes.average()
            val maxVol = volumes.maxOrNull() ?: 0.0
            val avgBarSize = if (slice.isEmpty()) 0.0 else slice.map { it.body }.average()
            val closeStrength = if (slice.isEmpty()) 0.5 else slice.map {
                if (dir == TrendDirection.UP) it.closePosition else 1.0 - it.closePosition
            }.average()

            legs.add(
                Leg(
                    startIndex = s,
                    endIndex = e,
                    startPrice = startP,
                    endPrice = endP,
                    direction = dir,
                    bars = e - s,
                    maxVolume = maxVol,
                    avgVolume = avgVol,
                    avgBarSize = avgBarSize,
                    closeStrength = closeStrength,
                )
            )
        }
        return legs
    }

    /**
     * Индексы пивотов (разворотов) по ценам закрытия. Классический ZigZag: пока идёт движение
     * в одну сторону — расширяем экстремум; при откате на [threshold] от экстремума фиксируем пивот.
     */
    private fun findPivots(closes: List<Double>, threshold: Double): List<Int> {
        val n = closes.size
        if (n == 0) return emptyList()
        val pivots = mutableListOf(0)
        var direction = 0            // +1 вверх, -1 вниз, 0 не определено
        var extremeIdx = 0

        for (i in 1 until n) {
            val price = closes[i]
            val extreme = closes[extremeIdx]
            when (direction) {
                0 -> {
                    if (extreme > 0 && price >= extreme * (1 + threshold)) {
                        direction = 1; extremeIdx = i
                    } else if (extreme > 0 && price <= extreme * (1 - threshold)) {
                        direction = -1; extremeIdx = i
                    } else if (price > extreme || price < extreme) {
                        // пока направление не определено, двигаем стартовый экстремум к текущему
                        extremeIdx = i
                        pivots[0] = i
                    }
                }
                1 -> { // восходящее движение: extreme — локальный максимум
                    if (price > extreme) {
                        extremeIdx = i
                    } else if (extreme > 0 && price <= extreme * (1 - threshold)) {
                        pivots.add(extremeIdx)   // зафиксировали максимум
                        direction = -1
                        extremeIdx = i
                    }
                }
                -1 -> { // нисходящее движение: extreme — локальный минимум
                    if (price < extreme) {
                        extremeIdx = i
                    } else if (extreme > 0 && price >= extreme * (1 + threshold)) {
                        pivots.add(extremeIdx)   // зафиксировали минимум
                        direction = 1
                        extremeIdx = i
                    }
                }
            }
        }
        if (pivots.last() != extremeIdx) pivots.add(extremeIdx)
        return pivots.distinct()
    }
}
