package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.core.Indicators
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * RSI + EMA Trend (порт из MRX, только LONG):
 * вход при выходе RSI из перепроданности + подтверждение трендом EMA fast > EMA slow.
 * Рассчитана на «взрослые» мемкоины с достаточной историей свечей.
 */
class RsiEmaStrategy(override val config: StrategyConfig) : Strategy {

    override val name: String = StrategyType.RSI_EMA.displayName

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? {
        val minBars = maxOf(config.rsiPeriod + 2, config.emaSlow + 2)
        if (candles.size < minBars) return null
        val entry = candidate.priceUsd
        if (entry <= 0) return null

        val closes = candles.map { it.close }
        val rsi = Indicators.rsi(closes, config.rsiPeriod)
        val emaFast = Indicators.ema(closes, config.emaFast)
        val emaSlow = Indicators.ema(closes, config.emaSlow)
        if (rsi.size < 2 || emaFast.isEmpty() || emaSlow.isEmpty()) return null

        val rsiPrev = rsi[rsi.size - 2]
        val rsiNow = rsi.last()

        // Выход из перепроданности: RSI пересекает уровень oversold снизу вверх.
        val rsiCrossUp = rsiPrev <= config.rsiOversold && rsiNow > config.rsiOversold
        // Подтверждение трендом: EMA fast выше EMA slow.
        val trendUp = emaFast.last() > emaSlow.last()

        if (!rsiCrossUp || !trendUp) return null

        val rsiScore = ((rsiNow - config.rsiOversold) / 10.0).coerceIn(0.0, 1.0)
        val spread = (emaFast.last() - emaSlow.last()) / emaSlow.last() * 100
        val trendScore = (spread / 2.0).coerceIn(0.0, 1.0)
        val confidence = (0.6 + 0.2 * rsiScore + 0.2 * trendScore).coerceAtMost(0.95)

        return Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = confidence,
            reason = "RSI+EMA: RSI ${rsiNow.toInt()} вышел из перепроданности, EMA${config.emaFast}>EMA${config.emaSlow}",
            entryPrice = entry,
            stopLoss = config.stopLossPrice(entry),
            takeProfit = config.takeProfitPrice(entry),
        )
    }
}
