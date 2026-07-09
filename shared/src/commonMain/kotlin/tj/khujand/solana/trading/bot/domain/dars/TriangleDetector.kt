package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Сетап «Треугольник» (Урок 5) для восходящего тренда:
 *  - каждая коррекционная волна слабее предыдущей (сужение);
 *  - каждый минимум выше предыдущего (higher lows);
 *  - вход после пробоя ВВЕРХ верхней границы треугольника на хорошем импульсном баре
 *    с сильным закрытием (обычно 3-4 касание).
 * Если текущая коррекционная волна сильнее предыдущей — сетап игнорируется.
 *
 * Верхняя граница (сопротивление) считается по максимуму high свечей внутри треугольника,
 * КРОМЕ последнего (пробойного) бара — иначе сам пробойный бар поднял бы планку и пробой
 * никогда бы не фиксировался.
 */
object TriangleDetector {

    /** Сила закрытия и агрессивность пробойного бара — как в остальных сетапах. */
    private const val CONFIRM_CLOSE_POS = 0.6
    private const val CONFIRM_BODY_MULT = 1.2

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

        // Верхняя граница треугольника = макс. high внутри окна треугольника без пробойного бара.
        val triangleStart = corrections.first().startIndex.coerceIn(0, candles.size - 2)
        val window = candles.subList(triangleStart, candles.size - 1)
        if (window.isEmpty()) return DarsSignal.reject("нет окна треугольника")
        val resistance = window.maxOf { it.high }

        // Пробой вверх на импульсном баре с сильным закрытием (Урок 5 + Урок 4).
        val last = candles.last()
        val avgBody = candles.map { it.body }.average().coerceAtLeast(1e-12)
        val breakout = last.close > resistance &&
            last.isBullish &&
            last.closePosition >= CONFIRM_CLOSE_POS &&
            last.body >= avgBody * CONFIRM_BODY_MULT
        if (!breakout) return DarsSignal.reject("нет пробоя треугольника вверх на импульсном баре")

        val touches = corrections.size + 1
        val score = (60 + touches * 5 + (last.closePosition * 20).toInt()).coerceIn(0, 100)
        return DarsSignal.pass(
            DarsSetup.TRIANGLE, TrendDirection.UP, score,
            "треугольник: $touches касаний, higher lows",
            "пробой ${fmt(resistance)} вверх, закрытие ${(last.closePosition * 100).toInt()}%",
        )
    }

    private fun fmt(v: Double): String =
        if (v >= 0.01) ((v * 1_000_000).toLong() / 1_000_000.0).toString() else v.toString()
}
