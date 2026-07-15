package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Фильтр по позиции цены в диапазоне (адаптация range-фильтра MRX под DEX-модель).
 *
 * Бот спотовый, только лонг → единственная осмысленная сторона: «не покупать у верха диапазона».
 * Диапазон берём по последним [lookback] свечам рабочего ТФ (в MRX для перпов это готовый
 * 24ч high/low из тикера; здесь считаем из свечей, т.к. снимка с 24ч-экстремумами нет).
 */

/**
 * Позиция [price] в диапазоне последних [lookback] свечей:
 * `0.0` = минимум окна, `1.0` = максимум. `null`, если данных нет, окно пустое
 * или диапазон вырожден (high == low) — тогда фильтр не применяется.
 */
fun rangePosition(candles: List<Candle>, price: Double, lookback: Int): Double? {
    if (candles.isEmpty() || lookback <= 0) return null
    val window = if (candles.size > lookback) candles.subList(candles.size - lookback, candles.size) else candles
    val high = window.maxOf { it.high }
    val low = window.minOf { it.low }
    if (high <= low) return null
    return ((price - low) / (high - low)).coerceIn(0.0, 1.0)
}

/**
 * true, если по фильтру диапазона вход по цене [entryPrice] разрешён.
 * Возвращает true и когда фильтр выключен, и когда диапазон посчитать нельзя (нет данных) —
 * фильтр никогда не блокирует «вслепую».
 */
fun StrategyConfig.rangeAllowsEntry(candles: List<Candle>, entryPrice: Double): Boolean {
    if (!rangeFilterEnabled) return true
    val pos = rangePosition(candles, entryPrice, rangeLookbackBars) ?: return true
    return pos <= rangeMaxEntryPct
}
