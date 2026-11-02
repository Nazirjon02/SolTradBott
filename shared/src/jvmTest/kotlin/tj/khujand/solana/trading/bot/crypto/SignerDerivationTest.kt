package tj.khujand.solana.trading.bot.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class SignerDerivationTest {

    private val canonical = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun pastedPhraseDerivesSameWallet() {
        // Фраза из заметок: переводы строк, двойные пробелы, заглавные — тот же кошелёк.
        val messy = "Abandon abandon  abandon\nabandon abandon abandon\r\nabandon abandon abandon abandon abandon About\n"
        assertEquals(
            createSignerFromSeedPhrase(canonical).publicKeyBase58(),
            createSignerFromSeedPhrase(messy).publicKeyBase58(),
        )
    }

    @Test
    fun derivationMatchesPhantomPath() {
        // m/44'/501'/0'/0' — путь Phantom/Solflare; эталонный адрес тестовой мнемоники BIP39.
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", createSignerFromSeedPhrase(canonical).publicKeyBase58())
    }
}
