package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Сетап «Треугольник» (Урок 5) для восходящего тренда:
 *  - каждая коррекционная волна слабее предыдущей (сужение);
 *  - каждый минимум выше предыдущего (higher lows);
 *  - вход после пробоя вверх на хорошем импульсном баре с сильным закрытием (обычно 3-4 касание).
 * Если текущая коррекционная волна сильнее предыдущей — сетап игнорируется.
 */
object TriangleDetector {

    fun detect(candles: List<Candle>, legs: List<Leg>, cfg: DarsConfig): DarsSignal {
        if (candles.size < 6) return DarsSignal.reject("мало свечей для треугольника")

        val corrections = legs.filter { it.direction == TrendDirection.DOWN }.takeLast(4)
        if (corrections.size < 3) return DarsSignal.reject("мало коррекционных волн (${corrections.size}<3)")

        // Каждая последующая коррекция слабее предыдущей (сужение по величине).
        val weakening = corrections.zipWithNext().all { (a, b) -> b.sizeAbs <= a.sizeAbs * 1.05 }
        if (!weakening) return DarsSignal.reject("коррекционная волна усилилась — не треугольник")

        // Higher lows: концы коррекций (минимумы) поднимаются.
        val higherLows = corrections.map { it.endPrice }.zipWithNext().all { (a, b) -> b >= a * 0.999 }
        if (!higherLows) return DarsSignal.reject("минимумы не растут — нет восходящего треугольника")

        // Пробой вверх: последний бар закрывается выше последнего свингового максимума.
        val lastSwingHigh = legs.filter { it.direction == TrendDirection.UP }.lastOrNull()?.endPrice
            ?: return DarsSignal.reject("нет свингового максимума для пробоя")
        val last = candles.last()
        val avgBody = candles.map { it.body }.average().coerceAtLeast(1e-12)
        val breakout = last.close > lastSwingHigh &&
            last.isBullish &&
            last.closePosition >= 0.6 &&
            last.body >= avgBody * 1.2
        if (!breakout) return DarsSignal.reject("нет пробоя треугольника вверх на импульсном баре")

        val touches = corrections.size + 1
        val score = (60 + touches * 5 + (last.closePosition * 20).toInt()).coerceIn(0, 100)
        return DarsSignal.pass(
            DarsSetup.TRIANGLE, TrendDirection.UP, score,
            "треугольник: $touches касаний, higher lows",
            "пробой вверх, закрытие ${(last.closePosition * 100).toInt()}%",
        )
    }
}
