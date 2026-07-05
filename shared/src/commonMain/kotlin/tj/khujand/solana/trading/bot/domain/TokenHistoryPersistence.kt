package tj.khujand.solana.trading.bot.domain

/**
 * Хранение журнала сделок: на JVM нельзя держать большой JSON в java.util.prefs (лимит ~8KB на ключ).
 * Платформенные реализации: файл на desktop, AppSettings на Android/iOS.
 */
internal expect object TokenHistoryPersistence {
    fun load(): List<TokenHistory>
    fun save(list: List<TokenHistory>)
    fun clear()
}
