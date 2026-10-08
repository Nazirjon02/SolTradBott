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
     * Стратегия сама решает вход по своему порогу и НЕ должна дополнительно
     * отсекаться общим порогом уверенности [StrategyManager.MIN_CONFIDENCE].
     * По умолчанию false (обычные стратегии проходят общий гейт).
     */
    val selfGated: Boolean get() = false

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
    SCORE("Вход по баллу"),
    BREAKOUT_PULLBACK("Пробой + откат"),
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

    // ── Выходы в R (общие для всех стратегий, по умолчанию выключены) ──
    // R = цена входа − начальный стоп (риск на единицу). См. TradeMonitor и ExitRules.
    // rLadderEnabled: TP1/TP2 на tp1R/tp2R × R вместо % (доли закрытия — tp1/tp2ClosePercent);
    //   при взятом TP1 тайм-стоп больше не закрывает позицию («сделка пошла»).
    // breakEvenAfterTp1: после TP1 стоп переносится в безубыток + breakEvenOffsetPercent.
    // runnerEnabled: основной Take Profit не закрывает позицию — остаток ведёт трейлинг/стоп.
    // atrTrailEnabled: Chandelier-трейлинг пик × (1 − atrTrailMult × ATR%входа) после atrTrailActivationR × R.
    val rLadderEnabled: Boolean = false,
    val tp1R: Double = 1.5,
    val tp2R: Double = 3.0,
    val breakEvenAfterTp1: Boolean = false,
    val runnerEnabled: Boolean = false,
    val atrTrailEnabled: Boolean = false,
    val atrTrailMult: Double = 3.0,
    val atrTrailActivationR: Double = 1.0,

    // ── Размер позиции от риска (по умолчанию выключен — размер = positionSize% баланса) ──
    // Включён: размер = баланс × riskPerTradePercent% / расстояние до стопа, но не больше
    // positionSize% баланса и не больше maxPoolSharePercent% ликвидности пула (слиппедж).
    val riskSizingEnabled: Boolean = false,
    val riskPerTradePercent: Double = 1.0,
    val maxPoolSharePercent: Double = 1.0,

    // ── Риск ──
    // Выключатели лимитов: false = соответствующая проверка в RiskManager пропускается
    // (лимит не блокирует вход). Дефолт true — поведение существующих стратегий не меняется.
    val maxDailyLossEnabled: Boolean = true,
    val maxDailyLoss: Double = 5.0,
    val maxDrawdownEnabled: Boolean = true,
    // Заметно больше stopLossPercent: иначе штатный выход по SL сам упирается
    // в лимит просадки и блокирует стратегию (см. RiskManager.checkDrawdown).
    val maxDrawdown: Double = 30.0,
    val cooldownSeconds: Int = 300,

    // ── Фильтры токенов ──
    val minLiquidityUsd: Double = 10_000.0,
    val minMarketCap: Double = 50_000.0,
    val maxMarketCap: Double = 10_000_000.0,
    val minTokenAgeMinutes: Long = 30,
    val maxTokenAgeMinutes: Long = 43_200,

    // ── Фильтр по позиции в диапазоне (общий, для любой стратегии) ──
    // Спот, только лонг → «не покупать у верха диапазона». Позиция входа считается по
    // последним rangeLookbackBars свечам рабочего ТФ: 0.0 = минимум окна, 1.0 = максимум.
    // Вход разрешён только если позиция ≤ rangeMaxEntryPct. Дефолт OFF — поведение не меняется.
    val rangeFilterEnabled: Boolean = false,
    val rangeMaxEntryPct: Double = 0.8,   // не покупать в верхних (1 - 0.8) = 20% диапазона
    val rangeLookbackBars: Int = 100,

    // ── Вход по баллу (стратегия SCORE) ──
    // Бот входит, когда балл монеты (тот же, что на экране «Анализ», 0..100) ≥ scoreThreshold.
    // scanFiltersEnabled=false отключает фильтры сканера (ликвидность/MC/возраст) — «вход по баллу
    // без фильтров». Дефолты сохраняют прежнее поведение для остальных стратегий.
    val scoreThreshold: Int = 50,
    val scanFiltersEnabled: Boolean = true,

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
    val darsDominanceRatio: Double = 1.5,        // импульс должен явно доминировать над коррекцией
    val darsMinCorrectionLenPct: Double = 70.0,  // Урок 4: коррекция ≥70% длины импульса
    val darsRejectAtResistance: Boolean = true,
    val darsResistanceProximityPct: Double = 1.0,
    val darsMinLegs: Int = 4,                     // 4 ноги → работает сравнение с прошлыми импульсом/коррекцией
    val darsUseImpulseCorrection: Boolean = true,
    val darsUseTrendLevels: Boolean = true,
    val darsUseFalseBreakout: Boolean = true,
    val darsUseTriangle: Boolean = true,

    // ── Пробой + откат (стратегия BREAKOUT_PULLBACK) ──
    // Пробой максимума bpBreakoutLookback баров на объёме ≥ bpBreakoutVolumeMult × средний,
    // затем откат с объёмом ≤ bpPullbackVolumeRatio × объём импульса, ретест уровня пробоя
    // и бар-триггер с объёмом ≥ bpTriggerVolumeMult × средний объём отката. См. STRATEGY_RESEARCH.md §3.
    val bpHigherTf: String = "15m",
    val bpRequireHtfTrend: Boolean = true,
    val bpBreakoutLookback: Int = 20,
    val bpBreakoutVolumeMult: Double = 2.0,
    val bpMaxBarsSinceBreakout: Int = 20,
    val bpPullbackVolumeRatio: Double = 0.7,
    val bpTriggerVolumeMult: Double = 1.2,
) {
    fun scanFilters(): ScanFilters = ScanFilters(
        minLiquidityUsd = minLiquidityUsd,
        minMarketCap = minMarketCap,
        maxMarketCap = maxMarketCap,
        minTokenAgeMinutes = minTokenAgeMinutes,
        maxTokenAgeMinutes = maxTokenAgeMinutes,
        filtersEnabled = scanFiltersEnabled,
    )

    /** SL/TP-цены от цены входа (спот, только лонг). */
    fun stopLossPrice(entry: Double): Double = entry * (1 - stopLossPercent / 100)
    fun takeProfitPrice(entry: Double): Double = entry * (1 + takeProfitPercent / 100)
}
