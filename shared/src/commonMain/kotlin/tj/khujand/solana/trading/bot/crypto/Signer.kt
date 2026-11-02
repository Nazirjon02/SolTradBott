package tj.khujand.solana.trading.bot.crypto

interface Signer {
    fun publicKeyBase58(): String
    fun sign(message: ByteArray): ByteArray
}

expect fun createSignerFromSeedPhrase(seedPhrase: String): Signer

/**
 * Мнемоника в каноническом виде BIP39: слова через один пробел, нижний регистр.
 * Seed считается от точной строки, поэтому фраза, вставленная из заметок с переводами строк,
 * двойными пробелами или заглавными буквами, давала ДРУГОЙ кошелёк — бот показывал чужой пустой
 * адрес вместо кошелька из Phantom/Solflare.
 */
internal fun normalizeMnemonic(phrase: String): String =
    phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase() }
