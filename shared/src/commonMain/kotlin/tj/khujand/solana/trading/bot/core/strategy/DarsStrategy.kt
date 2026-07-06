package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.domain.dars.DarsEntryEngine
import tj.khujand.solana.trading.bot.exchange.dex.BaseToken
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair

/**
 * Dars / Smart Money — обёртка существующего DarsEntryEngine
 * (импульс/коррекция, ложный пробой, треугольник, уровни) в Strategy-интерфейс MRX.
 * Свечи движок тянет сам через свой candleSource — параметры ТФ передаём через мост FilterSettings.
 */
class DarsStrategy(override val config: StrategyConfig) : Strategy {

    override val name: String = StrategyType.DARS.displayName

    private val engine = DarsEntryEngine()

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? {
        val pair = TokenPair(
            chainId = "solana",
            dexId = candidate.dexId,
            pairAddress = candidate.pairAddress,
            baseToken = BaseToken(candidate.mint, candidate.name, candidate.symbol),
        )
        val result = engine.evaluate(pair, config.toDarsFilterSettings())
        if (!result.passed) return null

        val entry = candidate.priceUsd
        if (entry <= 0) return null
        val confidence = (result.score / 100.0).coerceIn(0.0, 1.0)
        return Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = confidence,
            reason = "Dars: ${result.setup} — ${result.reasons.joinToString("; ")}",
            entryPrice = entry,
            stopLoss = config.stopLossPrice(entry),
            takeProfit = config.takeProfitPrice(entry),
        )
    }
}

/** Мост: типизированный StrategyConfig → legacy FilterSettings, который понимает DarsEntryEngine. */
internal fun StrategyConfig.toDarsFilterSettings(): FilterSettings {
    val (entryTf, entryAgg) = DexClient.parseTimeframe(timeframe)
    val (higherTf, higherAgg) = DexClient.parseTimeframe(darsHigherTf)
    return FilterSettings(
        darsEnabled = true,
        darsOnlyMode = true,
        darsHigherTf = higherTf,
        darsHigherTfAggregate = higherAgg,
        darsEntryTf = entryTf,
        darsEntryTfAggregate = entryAgg,
        darsCandleLimit = darsCandleLimit,
        darsSwingPivotPct = darsSwingPivotPct,
        darsRequireHtfTrend = darsRequireHtfTrend,
        darsDominanceRatio = darsDominanceRatio,
        darsMinCorrectionLenPct = darsMinCorrectionLenPct,
        darsRejectAtResistance = darsRejectAtResistance,
        darsResistanceProximityPct = darsResistanceProximityPct,
        darsMinLegs = darsMinLegs,
        darsFailClosed = false,
        darsUseImpulseCorrection = darsUseImpulseCorrection,
        darsUseTrendLevels = darsUseTrendLevels,
        darsUseFalseBreakout = darsUseFalseBreakout,
        darsUseTriangle = darsUseTriangle,
    )
}
