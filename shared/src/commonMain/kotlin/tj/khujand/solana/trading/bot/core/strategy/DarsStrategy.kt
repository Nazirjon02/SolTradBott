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

        // Стоп «за структурой» (минимум коррекции) + TP у уровня + гейт риск/прибыль.
        // Без приемлемого R:R вход отклоняем — даже верный по форме сетап не берём в убыток.
        val exits = resolveDarsExits(entry, result.targetFrac, result.stopFrac, config, MIN_RISK_REWARD)
            ?: return null

        val confidence = (result.score / 100.0).coerceIn(0.0, 1.0)
        return Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = confidence,
            reason = "Dars: ${result.setup} — ${result.reasons.joinToString("; ")}",
            entryPrice = entry,
            stopLoss = exits.stopLoss,
            takeProfit = exits.takeProfit,
        )
    }

    companion object {
        /** Минимально допустимое отношение прибыль/риск для входа (Урок: цель дальше риска). */
        const val MIN_RISK_REWARD = 1.5
    }
}

/** Рассчитанные выходы: цены стопа и тейка. */
internal data class DarsExits(val stopLoss: Double, val takeProfit: Double)

/**
 * Считает цены стопа и тейка для сигнала Dars и применяет гейт R:R.
 *  - Тейк: у ближайшего сопротивления (targetFrac) либо механический TP% при пробое к новым максимумам.
 *  - Стоп: под структурой (stopFrac от движка), но не рискуя больше механического SL% из конфига
 *    (`maxOf` берёт более высокую = более близкую цену стопа). Если структуры нет — механический SL%.
 *  - Гейт: если прибыль/риск < [minRiskReward] или некорректны — возвращает null (вход отклонён).
 */
internal fun resolveDarsExits(
    entry: Double,
    targetFrac: Double?,
    stopFrac: Double?,
    config: StrategyConfig,
    minRiskReward: Double,
): DarsExits? {
    if (entry <= 0.0) return null

    val takeProfit = targetFrac?.let { entry * (1 + it) } ?: config.takeProfitPrice(entry)

    val mechanicalStop = config.stopLossPrice(entry)
    val structuralStop = stopFrac?.let { entry * (1 - it) }
    val stopLoss = if (structuralStop != null) maxOf(structuralStop, mechanicalStop) else mechanicalStop

    val risk = entry - stopLoss
    val reward = takeProfit - entry
    if (risk <= 0.0 || reward <= 0.0) return null
    if (reward / risk < minRiskReward) return null

    return DarsExits(stopLoss, takeProfit)
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
        // Урок 2: без данных старшего ТФ тренд неизвестен → не торгуем вслепую.
        darsFailClosed = true,
        darsUseImpulseCorrection = darsUseImpulseCorrection,
        darsUseTrendLevels = darsUseTrendLevels,
        darsUseFalseBreakout = darsUseFalseBreakout,
        darsUseTriangle = darsUseTriangle,
    )
}
