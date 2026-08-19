package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.domain.dars.DarsConfig
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalyzerService
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * Стратегия «Вход по баллу» — покупает монету, когда её балл (тот же 0..100, что на экране
 * «🔬 Анализ») ≥ [StrategyConfig.scoreThreshold]. Балл считает тем же путём, что и экран анализа
 * ([MarketAnalyzerService] + [DarsConfig] из активной стратегии), поэтому число совпадает с тем,
 * что видит пользователь.
 *
 * Гейт — только балл: RR-порог и общий [StrategyManager.MIN_CONFIDENCE] не применяются
 * ([selfGated] = true), иначе скрытый порог 60% перебил бы пользовательский (по умолчанию 50).
 * Фильтры сканера отключаются флагом [StrategyConfig.scanFiltersEnabled] (см. [StrategyConfig.scanFilters]).
 *
 * @param analyzer источник анализа; по умолчанию реальный сервис (GeckoTerminal). Инъекция — для тестов.
 */
class ScoreStrategy(
    override val config: StrategyConfig,
    private val analyzer: MarketAnalyzerService = MarketAnalyzerService(),
) : Strategy {

    override val name: String = StrategyType.SCORE.displayName

    // Порог балла — единственный гейт входа; общий порог уверенности пропускаем.
    override val selfGated: Boolean = true

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? {
        val entry = candidate.priceUsd
        if (entry <= 0.0) return null

        // Тот же конфиг, что и у экрана «Анализ» (activeDarsConfig), — балл получается идентичный.
        val cfg = DarsConfig.from(config.toDarsFilterSettings())
        val a = analyzer.analyze(candidate, cfg)

        if (a.score < config.scoreThreshold) return null

        // SL/TP механические из конфига (дефолт −15% / +30%). RR-гейт намеренно не навешиваем:
        // по требованию вход определяется только баллом.
        return Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = (a.score / 100.0).coerceIn(0.0, 1.0),
            reason = "Балл ${a.score} ≥ ${config.scoreThreshold} · ${a.readiness.label} · ${a.phase.label}",
            entryPrice = entry,
            stopLoss = config.stopLossPrice(entry),
            takeProfit = config.takeProfitPrice(entry),
        )
    }
}
