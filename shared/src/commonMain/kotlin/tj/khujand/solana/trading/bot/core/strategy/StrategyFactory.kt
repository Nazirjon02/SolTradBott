package tj.khujand.solana.trading.bot.core.strategy

/** Фабрика стратегий (как в MRX): тип из БД → реализация. */
fun createStrategy(config: StrategyConfig): Strategy = when (config.type) {
    StrategyType.DARS.name -> DarsStrategy(config)
    StrategyType.MOMENTUM.name -> MomentumStrategy(config)
    StrategyType.RSI_EMA.name -> RsiEmaStrategy(config)
    else -> DarsStrategy(config)
}
