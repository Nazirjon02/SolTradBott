package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Гейт накопления (Урок 4, п.2): «Если видим резкий рост или падение цены, то должны дождаться
 * длительного накопления и только после этого рассматриваем точки входа.»
 *
 * Ищет последний «резкий» бар (диапазон high-low аномально большой — падающий/растущий нож)
 * и требует, чтобы после него прошло достаточно спокойных баров (консолидация). Пока их мало —
 * вход блокируется. Последний бар (триггер входа) в поиске резких движений не участвует.
 */
object AccumulationGate {

    /** Абсолютный порог «резкого» бара: диапазон ≥25% от цены. */
    private const val SHARP_ABS_PCT = 0.25

    /** …либо ≥ SHARP_MULT× медианного диапазона окна (адаптация к волатильности монеты). */
    private const val SHARP_MULT = 4.0

    /** Сколько спокойных баров должно пройти после резкого движения до входа. */
    private const val MIN_ACCUM_BARS = 5

    /** Окно анализа последних баров. */
    private const val LOOKBACK = 30

    /** true = недавно было резкое движение и накопления ещё мало → входить рано. */
    fun blocksEntry(candles: List<Candle>): Boolean {
        if (candles.size < MIN_ACCUM_BARS + 2) return false
        // Последний бар — триггер входа (может быть агрессивным по смыслу сетапа), его не считаем.
        val scan = candles.takeLast(LOOKBACK).dropLast(1)
        if (scan.size < MIN_ACCUM_BARS) return false

        val ranges = scan.map { rangePct(it) }
        val median = ranges.sorted()[ranges.size / 2]
        val threshold = maxOf(SHARP_ABS_PCT, median * SHARP_MULT)

        val lastSharpIdx = ranges.indexOfLast { it >= threshold }
        if (lastSharpIdx < 0) return false

        val barsSince = scan.size - lastSharpIdx  // баров после резкого (включая бар-триггер)
        return barsSince < MIN_ACCUM_BARS
    }

    private fun rangePct(c: Candle): Double {
        val base = c.low.coerceAtLeast(1e-12)
        return (c.high - c.low) / base
    }
}
