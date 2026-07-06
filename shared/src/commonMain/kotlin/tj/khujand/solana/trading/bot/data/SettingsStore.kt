package tj.khujand.solana.trading.bot.data

import kotlin.time.Clock
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/**
 * Типизированная обёртка над таблицей `settings` (key/value).
 * Хранит runtime-настройки, которые можно менять без перезапуска сервера
 * (Telegram-токен, режим DEMO/REAL, RPC-URL и т.д.).
 *
 * Все ключи и дефолты централизованы здесь, чтобы по строковым именам не было опечаток.
 */
class SettingsStore(private val db: DrxDatabase) {

    object Keys {
        const val TELEGRAM_TOKEN   = "telegram.token"
        const val TELEGRAM_CHAT_ID = "telegram.chat_id"
        const val DEMO_MODE        = "bot.demo_mode"
        const val SIGNAL_ONLY      = "bot.signal_only"
        const val RPC_URL          = "solana.rpc_url"
        const val WALLET_SEED      = "wallet.seed_phrase"
        const val AI_API_KEY       = "ai.api_key"
    }

    // ─── Raw API ─────────────────────────────────────────────────────────────

    fun get(key: String): String? =
        db.settingsQueries.get(key).executeAsOneOrNull()

    fun set(key: String, value: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.settingsQueries.set(key, value, now)
    }

    fun delete(key: String) {
        db.settingsQueries.delete(key)
    }

    fun all(): Map<String, String> =
        db.settingsQueries.getAll().executeAsList().associate { it.key to it.val_text }

    // ─── Telegram ────────────────────────────────────────────────────────────

    fun getTelegramToken(): String?  = get(Keys.TELEGRAM_TOKEN)?.takeIf { it.isNotBlank() }
    fun setTelegramToken(v: String)  = set(Keys.TELEGRAM_TOKEN, v)

    fun getTelegramChatId(): Long?   = get(Keys.TELEGRAM_CHAT_ID)?.toLongOrNull()
    fun setTelegramChatId(v: Long)   = set(Keys.TELEGRAM_CHAT_ID, v.toString())

    // ─── Режимы бота ─────────────────────────────────────────────────────────

    // DEMO = paper-trading на DemoAccountManager, REAL = свопы через Jupiter.
    fun getDemoMode(): Boolean?      = get(Keys.DEMO_MODE)?.toBooleanStrictOrNull()
    fun setDemoMode(v: Boolean)      = set(Keys.DEMO_MODE, v.toString())

    // Режим «только сигнал»: бот не открывает сделки, а лишь шлёт сигнал + параметры входа.
    fun getSignalOnly(): Boolean?    = get(Keys.SIGNAL_ONLY)?.toBooleanStrictOrNull()
    fun setSignalOnly(v: Boolean)    = set(Keys.SIGNAL_ONLY, v.toString())

    // ─── Solana ──────────────────────────────────────────────────────────────

    fun getRpcUrl(): String?         = get(Keys.RPC_URL)?.takeIf { it.isNotBlank() }
    fun setRpcUrl(v: String)         = set(Keys.RPC_URL, v)

    // Seed-фраза кошелька для REAL-режима (подпись Jupiter-свопов). Хранится локально в SQLite.
    fun getWalletSeed(): String?     = get(Keys.WALLET_SEED)?.takeIf { it.isNotBlank() }
    fun setWalletSeed(v: String)     = set(Keys.WALLET_SEED, v)
    fun clearWalletSeed()            = delete(Keys.WALLET_SEED)

    // ─── AI-анализатор (опциональный фильтр confidence) ──────────────────────

    fun getAiApiKey(): String?       = get(Keys.AI_API_KEY)?.takeIf { it.isNotBlank() }
    fun setAiApiKey(v: String)       = set(Keys.AI_API_KEY, v)
}
