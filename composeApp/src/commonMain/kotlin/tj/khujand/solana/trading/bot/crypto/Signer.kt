package tj.khujand.solana.trading.bot.crypto

interface Signer {
    fun publicKeyBase58(): String
    fun sign(message: ByteArray): ByteArray
}

expect fun createSignerFromSeedPhrase(seedPhrase: String): Signer
