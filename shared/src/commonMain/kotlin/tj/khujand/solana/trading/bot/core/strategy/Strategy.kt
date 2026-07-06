package tj.khujand.solana.trading.bot.core.strategy

import kotlinx.serialization.Serializable
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.ScanFilters
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * Интерфейс стратегии (модель MRX, адаптированная под DEX):
 * стратегия анализирует кандидата сканера + свечи и решает, покупать ли.
 * Бот спотовый — только LONG (BUY), шортов и плеча на DEX нет.
 */
interface Strategy {
    val name: String
    val config: StrategyConfig

    /**
     * @param candidate токен из сканера (метрики DexScreener).
     * @param candles свечи рабочего ТФ (GeckoTerminal).
     * @param higherTfCandles свечи старшего ТФ (пусто, если стратегии не нужны).
     * @return сигнал на покупку или null.
     */
    suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal?
}

@Serializable
data class Signal(
    val mint: String,
    val symbol: String,
    val pairAddress: String,
    val confidence: Double,
    val reason: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
)

enum class StrategyType(val displayName: String) {
    DARS("Dars / Smart Money"),
    MOMENTUM("Momentum Scalping"),
    RSI_EMA("RSI + EMA Trend"),
}

/**
 * Типизированный конфиг стратегии — зеркало строки таблицы strategy.
 * Единица размера позиции — % от баланса (USD), как в MRX.
 */
@Serializable
data class StrategyConfig(
    val id: String,
    val name: String = "",
    val type: String = StrategyType.DARS.name,
    val isActive: Boolean = false,
    val timeframe: String = "1m",

    // ── Размер позиции и исполнение ──
    val positionSize: Double = 5.0,
    val maxPositions: Int = 1,
    val slippagePercent: Double = 1.0,
    val priorityFeeLamports: Long = 0,

    // ── Stop Loss / Take Profit ──
    val stopLossPercent: Double = 15.0,
    val takeProfitPercent: Double = 30.0,
    val trailingStopEnabled: Boolean = false,
    val trailingStopPercent: Double = 10.0,
    val trailingActivationPercent: Double = 15.0,
    val breakEvenEnabled: Boolean = false,
    val breakEvenTriggerPercent: Double = 10.0,
    val breakEvenOffsetPercent: Double = 1.0,
    val partialTpEnabled: Boolean = false,
    val tp1Percent: Double = 15.0,
    val tp1ClosePercent: Double = 50.0,
    val tp2Percent: Double = 25.0,
    val tp2ClosePercent: Double = 30.0,
    val timeStopMinutes: Int = 0,
    val liquidityExitDropPercent: Double = 50.0,

    // ── Риск ──
    val maxDailyLoss: Double = 5.0,
    val maxDrawdown: Double = 15.0,
    val cooldownSeconds: Int = 300,

    // ── Фильтры токенов ──
    val minLiquidityUsd: Double = 10_000.0,
    val minMarketCap: Double = 50_000.0,
    val maxMarketCap: Double = 10_000_000.0,
    val minTokenAgeMinutes: Long = 30,
    val maxTokenAgeMinutes: Long = 43_200,
    val minVolumeH1Usd: Double = 5_000.0,
    val minBuySellRatio: Double = 1.0,
    val rugcheckEnabled: Boolean = true,
    val rugcheckMaxScore: Int = 5_000,

    // ── Индикаторы ──
    val rsiPeriod: Int = 14,
    val rsiOverbought: Double = 70.0,
    val rsiOversold: Double = 30.0,
    val emaFast: Int = 9,
    val emaSlow: Int = 21,
    val atrPeriod: Int = 14,
    val volumeThreshold: Double = 1.5,

    // ── Dars / Smart Money ──
    val darsHigherTf: String = "15m",
    val darsCandleLimit: Int = 200,
    val darsSwingPivotPct: Double = 1.0,
    val darsRequireHtfTrend: Boolean = true,
    val darsDominanceRatio: Double = 1.3,
    val darsMinCorrectionLenPct: Double = 30.0,
    val darsRejectAtResistance: Boolean = true,
    val darsResistanceProximityPct: Double = 1.0,
    val darsMinLegs: Int = 2,
    val darsUseImpulseCorrection: Boolean = true,
    val darsUseTrendLevels: Boolean = true,
    val darsUseFalseBreakout: Boolean = true,
    val darsUseTriangle: Boolean = true,
) {
    fun scanFilters(): ScanFilters = ScanFilters(
        minLiquidityUsd = minLiquidityUsd,
        minMarketCap = minMarketCap,
        maxMarketCap = maxMarketCap,
        minTokenAgeMinutes = minTokenAgeMinutes,
        maxTokenAgeMinutes = maxTokenAgeMinutes,
        minVolumeH1Usd = minVolumeH1Usd,
        minBuySellRatio = minBuySellRatio,
        rugcheckEnabled = rugcheckEnabled,
        rugcheckMaxScore = rugcheckMaxScore,
    )

    /** SL/TP-цены от цены входа (спот, только лонг). */
    fun stopLossPrice(entry: Double): Double = entry * (1 - stopLossPercent / 100)
    fun takeProfitPrice(entry: Double): Double = entry * (1 + takeProfitPercent / 100)
}
