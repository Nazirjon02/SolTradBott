package tj.khujand.solana.trading.bot.crypto

actual fun createSignerFromSeedPhrase(seedPhrase: String): Signer {
    throw UnsupportedOperationException("Auto-signing is not supported on iOS in this build.")
}
