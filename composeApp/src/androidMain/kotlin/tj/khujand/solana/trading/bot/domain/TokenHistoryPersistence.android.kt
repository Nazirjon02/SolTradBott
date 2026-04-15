package tj.khujand.solana.trading.bot.domain

import tj.khujand.solana.trading.bot.util.AppSettings

private const val KEY_HISTORY = "token_history_v1"

internal actual object TokenHistoryPersistence {
    actual fun load(): List<TokenHistory> =
        AppSettings.getObjectSafe(KEY_HISTORY, emptyList())

    actual fun save(list: List<TokenHistory>) {
        AppSettings.putObject(KEY_HISTORY, list)
    }

    actual fun clear() {
        AppSettings.remove(KEY_HISTORY)
    }
}
