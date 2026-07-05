package tj.khujand.solana.trading.bot.data

import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings

enum class StrategyProfile(
    val label: String,
    val description: String,
    val emoji: String,
) {
    AGGRESSIVE(
        label       = "Агрессивный",
        description = "Молодые токены, большой TP, быстрый вход",
        emoji       = "🔥",
    ),
    BALANCED(
        label       = "Сбалансированный",
        description = "Средний риск, стадийный выход",
        emoji       = "⚖️",
    ),
    CONSERVATIVE(
        label       = "Консервативный",
        description = "Крупные токены, жёсткий SL, меньше сделок",
        emoji       = "🛡",
    ),
    SCALPING(
        label       = "Скальпинг",
        description = "Быстрый вход/выход, маленький TP, высокий оборот",
        emoji       = "⚡",
    );

    fun applyTo(base: FilterSettings): FilterSettings = when (this) {
        AGGRESSIVE -> base.copy(
            entryMaxAgeMinutes       = 15,
            entryMinMarketCap        = 30_000.0,
            entryMaxMarketCap        = 150_000.0,
            entryMinLiquidity        = 3_000.0,
            entryMinVolume           = 80_000.0,
            exitStrategy             = "aggressive",
            aggressiveTakeProfitPct  = 80.0,
            aggressiveSellPct        = 60.0,
            stopLossByPricePct       = 30.0,
            stopLossByMarketCapPct   = 35.0,
            trailingStopPct          = 40.0,
            timeBasedExitMinutes     = 20,
            maxTokensToMonitor       = 8,
        )
        BALANCED -> base.copy(
            entryMaxAgeMinutes       = 30,
            entryMinMarketCap        = 80_000.0,
            entryMaxMarketCap        = 300_000.0,
            entryMinLiquidity        = 5_000.0,
            entryMinVolume           = 150_000.0,
            exitStrategy             = "stages",
            exitStage1Cap            = 200_000.0,
            exitStage1Pct            = 30.0,
            exitStage2Cap            = 350_000.0,
            exitStage2Pct            = 30.0,
            exitStage3Cap            = 500_000.0,
            exitStage3Pct            = 20.0,
            exitStage4Cap            = 750_000.0,
            exitStage4Pct            = 20.0,
            stopLossByPricePct       = 25.0,
            stopLossByMarketCapPct   = 30.0,
            trailingStopPct          = 35.0,
            timeBasedExitMinutes     = 30,
            maxTokensToMonitor       = 6,
        )
        CONSERVATIVE -> base.copy(
            entryMaxAgeMinutes       = 60,
            entryMinMarketCap        = 200_000.0,
            entryMaxMarketCap        = 1_000_000.0,
            entryMinLiquidity        = 20_000.0,
            entryMinVolume           = 500_000.0,
            exitStrategy             = "stages",
            exitStage1Cap            = 400_000.0,
            exitStage1Pct            = 40.0,
            exitStage2Cap            = 700_000.0,
            exitStage2Pct            = 30.0,
            exitStage3Cap            = 1_000_000.0,
            exitStage3Pct            = 20.0,
            exitStage4Cap            = 1_500_000.0,
            exitStage4Pct            = 10.0,
            stopLossByPricePct       = 15.0,
            stopLossByMarketCapPct   = 20.0,
            trailingStopPct          = 25.0,
            timeBasedExitMinutes     = 60,
            maxTokensToMonitor       = 3,
            requireSocials           = true,
            requireWebsite           = true,
        )
        SCALPING -> base.copy(
            entryMaxAgeMinutes       = 10,
            entryMinMarketCap        = 20_000.0,
            entryMaxMarketCap        = 100_000.0,
            entryMinLiquidity        = 2_000.0,
            entryMinVolume           = 50_000.0,
            exitStrategy             = "aggressive",
            aggressiveTakeProfitPct  = 30.0,
            aggressiveSellPct        = 80.0,
            stopLossByPricePct       = 12.0,
            stopLossByMarketCapPct   = 15.0,
            trailingStopPct          = 20.0,
            timeBasedExitMinutes     = 10,
            maxTokensToMonitor       = 10,
            requireSocials           = false,
            requireWebsite           = false,
        )
    }
}
