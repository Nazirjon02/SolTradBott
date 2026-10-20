package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.exchange.dex.DexClient

/**
 * Фабрика стратегий (как в MRX): тип из БД → реализация.
 * @param client DEX-клиент для стратегий с on-chain проверками («Мем-плейбук»: authority через RPC,
 *   копикаты через DexScreener). Без клиента такие проверки не проходят — стратегия не входит.
 */
fun createStrategy(config: StrategyConfig, client: DexClient? = null): Strategy = when (config.type) {
    StrategyType.DARS.name -> DarsStrategy(config)
    StrategyType.SCORE.name -> ScoreStrategy(config)
    StrategyType.BREAKOUT_PULLBACK.name -> BreakoutPullbackStrategy(config)
    StrategyType.MEME_PLAYBOOK.name -> MemePlaybookStrategy(
        config,
        mintInfoSource = { mint -> client?.rpc?.getSplMintInfo(mint) },
        symbolPairsSource = { symbol -> client?.dexScreener?.searchSolanaPairsBySymbol(symbol) },
    )
    StrategyType.ELLIOTT.name -> ElliottStrategy(config)
    else -> DarsStrategy(config)
}
