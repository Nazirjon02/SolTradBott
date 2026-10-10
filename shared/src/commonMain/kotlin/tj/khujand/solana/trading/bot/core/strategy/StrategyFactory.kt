package tj.khujand.solana.trading.bot.core.strategy

/** Фабрика стратегий (как в MRX): тип из БД → реализация. */
fun createStrategy(config: StrategyConfig): Strategy = when (config.type) {
    StrategyType.DARS.name -> DarsStrategy(config)
    StrategyType.SCORE.name -> ScoreStrategy(config)
    StrategyType.BREAKOUT_PULLBACK.name -> BreakoutPullbackStrategy(config)
    else -> DarsStrategy(config)
}
