package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * Momentum Scalping — быстрый вход по всплеску объёма и давлению покупок
 * у свежих мемкоинов (аналог ScalpingStrategy в MRX, адаптирован к DEX-метрикам).
 *
 * Условия входа:
 *  1) объём последней свечи ≥ volumeThreshold × средний объём;
 *  2) давление покупок: buys/sells за час ≥ minBuySellRatio (данные сканера);
 *  3) импульс: рост за 5 минут > 0 и последние свечи преимущественно зелёные.
 */
class MomentumStrategy(override val config: StrategyConfig) : Strategy {

    override val name: String = StrategyType.MOMENTUM.displayName

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? {
        if (candles.size < 10) return null
        val entry = candidate.priceUsd
        if (entry <= 0) return null

        // 1. Всплеск объёма
        val recent = candles.takeLast(20)
        val avgVolume = recent.dropLast(1).map { it.volume }.average()
        val lastVolume = recent.last().volume
        if (avgVolume <= 0 || lastVolume < avgVolume * config.volumeThreshold) return null

        // 2. Давление покупок (из сканера)
        val ratio = if (candidate.sellsH1 == 0) candidate.buysH1.toDouble()
        else candidate.buysH1.toDouble() / candidate.sellsH1
        if (ratio < config.minBuySellRatio) return null

        // 3. Импульс: рост 5м + зелёные свечи
        if (candidate.priceChangeM5 <= 0) return null
        val lastBars = candles.takeLast(5)
        val greenBars = lastBars.count { it.close > it.open }
        if (greenBars < 3) return null

        val volumeScore = ((lastVolume / avgVolume - config.volumeThreshold) / config.volumeThreshold).coerceIn(0.0, 1.0)
        val ratioScore = ((ratio - config.minBuySellRatio) / config.minBuySellRatio).coerceIn(0.0, 1.0)
        val greenScore = greenBars / 5.0
        val confidence = (0.5 + 0.2 * volumeScore + 0.15 * ratioScore + 0.15 * greenScore).coerceAtMost(0.95)

        return Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = confidence,
            reason = "Momentum: объём ×${fmt(lastVolume / avgVolume)}, buys/sells ${fmt(ratio)}, " +
                "+${fmt(candidate.priceChangeM5)}% за 5м, $greenBars/5 зелёных",
            entryPrice = entry,
            stopLoss = config.stopLossPrice(entry),
            takeProfit = config.takeProfitPrice(entry),
        )
    }

    private fun fmt(v: Double): String {
        val r = kotlin.math.round(v * 100) / 100.0
        return r.toString()
    }
}
