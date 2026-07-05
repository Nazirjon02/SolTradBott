package tj.khujand.solana.trading.bot.bot.presentation

/**
 * Распределение полей и кнопок по страницам Telegram-меню (короткие экраны).
 */
object TelegramUiPages {
    const val FILTERS_PAGE_COUNT = 3
    const val EXIT_PAGE_COUNT = 2

    private val FILTER_ENTRY_KEYS = setOf(
        "maxTokensToMonitor",
        "entryMaxAgeMinutes",
        "entryMinMarketCap",
        "entryMaxMarketCap",
        "entryMinLiquidity",
        "entryMinVolume",
        "entryMinVolumeM5"
    )

    fun filterFieldPage(key: String): Int = when (key) {
        in FILTER_ENTRY_KEYS -> 0
        "minAiScore" -> 1
        else -> 2
    }

    fun exitFieldPage(key: String): Int = when (key) {
        "aggressiveTakeProfitPct",
        "aggressiveSellPct",
        "exitStage1Cap",
        "exitStage1Pct",
        "exitStage2Cap",
        "exitStage2Pct",
        "exitStage3Cap",
        "exitStage3Pct",
        "exitStage4Cap",
        "exitStage4Pct" -> 0
        "timeBasedExitMinutes",
        "tradingHoursStartUtcHour",
        "tradingHoursEndUtcHour" -> 1
        else -> 0
    }

    fun prevPage(page: Int, total: Int): Int =
        (page + total - 1) % total

    fun nextPage(page: Int, total: Int): Int =
        (page + 1) % total
}
