package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Сетап «Ложный пробой» (Урок 3) для лонга: цена прокалывает поддержку вниз (сбор стопов),
 * но закрывается обратно ВЫШЕ уровня, а подтверждающий бар — агрессивный бычий
 * с закрытием у максимума. Пробой не должен быть глубоким/длинным (Урок 4).
 */
object FalseBreakoutDetector {

    fun detect(candles: List<Candle>, levels: List<Level>, cfg: DarsConfig): DarsSignal {
        if (candles.size < 5) return DarsSignal.reject("мало свечей для ложного пробоя")

        val last = candles.last()
        val price = last.close
        val lookback = candles.takeLast(5)
        val avgBody = candles.map { it.body }.average().coerceAtLeast(1e-12)

        // Поддержки ниже (или около) текущей цены.
        val supports = LevelDetector.supports(levels, price * 1.001)
        for (level in supports) {
            val tol = level.price * 0.02
            // Кто-то из последних баров прокалывал уровень вниз, но закрылся выше него.
            val pierced = lookback.any { it.low < level.price && it.close > level.price && (level.price - it.low) < tol }
            if (!pierced) continue

            // Подтверждающий бар — агрессивный бычий с сильным закрытием, выше уровня.
            val aggressive = last.isBullish &&
                last.close > level.price &&
                last.closePosition >= 0.6 &&
                last.body >= avgBody * 1.2
            if (!aggressive) continue

            val score = (55 + (last.closePosition * 40).toInt() + level.touches * 3).coerceIn(0, 100)
            return DarsSignal.pass(
                DarsSetup.FALSE_BREAKOUT, TrendDirection.UP, score,
                "ложный пробой поддержки ${fmt(level.price)} (касаний ${level.touches})",
                "агрессивный бар, закрытие ${(last.closePosition * 100).toInt()}%",
            )
        }
        return DarsSignal.reject("нет ложного пробоя поддержки")
    }

    private fun fmt(v: Double): String {
        // Компактный вывод цены токена (может быть очень мелкой).
        return if (v >= 0.01) ((v * 1_000_000).toLong() / 1_000_000.0).toString()
        else v.toString()
    }
}
