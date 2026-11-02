package tj.khujand.solana.trading.bot.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class MnemonicNormalizeTest {

    @Test
    fun collapsesWhitespaceAndCase() {
        val pasted = "  Abandon  abandon\nabandon\tabandon ABOUT \r\n"
        assertEquals("abandon abandon abandon abandon about", normalizeMnemonic(pasted))
    }

    @Test
    fun canonicalPhraseUnchanged() {
        val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        assertEquals(phrase, normalizeMnemonic(phrase))
    }
}
