package tj.khujand.solana.trading.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Форматы интерфейса: суммы по-русски, цены мемкоинов, маска ключа в адресе RPC, режим счёта. */
class UiFormatTest {

    @Test
    fun moneyIsGroupedWithCommaDecimals() {
        assertEquals("10 234,56", fmtUsd(10_234.56))
        assertEquals("0,50", fmtUsd(0.5))
        assertEquals("−6,00", fmtUsd(-6.0))
        assertEquals("1 000 000,00", fmtUsd(1_000_000.0))
        assertEquals("12 345 678,90", fmtUsd(12_345_678.9), "крупные суммы — без экспоненты")
        assertEquals("0,00", fmtUsd(-0.001), "минус у нуля не рисуем")
    }

    @Test
    fun signedValues() {
        assertEquals("+12,40", fmtSignedUsd(12.4))
        assertEquals("−3,10", fmtSignedUsd(-3.1))
        assertEquals("0,00", fmtSignedUsd(0.0))
        assertEquals("+12,0%", fmtPct(12.0))
        assertEquals("−21,6%", fmtPct(-21.6))
    }

    @Test
    fun plainNumbersDropTrailingZeros() {
        assertEquals("15", fmtPlain(15.0))
        assertEquals("1,5", fmtPlain(1.5))
        assertEquals("0,25", fmtPlain(0.25))
        assertEquals("100", fmtPlain(100.0))
    }

    @Test
    fun memeCoinPrices() {
        assertEquals("0,00112", fmtPrice(0.00112))
        assertEquals("0,00000123", fmtPrice(0.00000123))
        assertEquals("1,2345", fmtPrice(1.23454))
        assertEquals("0", fmtPrice(0.0))
    }

    @Test
    fun rpcKeyIsFoundForMasking() {
        val url = "https://mainnet.helius-rpc.com/?api-key=abcd-1234"
        assertEquals("abcd-1234", url.substring(rpcSecretRange(url)!!))
        val qn = "https://x.solana-mainnet.quiknode.pro/?token=SECRET&x=1"
        assertEquals("SECRET", qn.substring(rpcSecretRange(qn)!!))
        assertNull(rpcSecretRange("https://api.mainnet-beta.solana.com"))
    }

    @Test
    fun rpcProviderNames() {
        assertEquals("Helius", rpcProviderName("https://mainnet.helius-rpc.com/?api-key=x"))
        assertEquals("PublicNode", rpcProviderName("https://solana-rpc.publicnode.com"))
        assertEquals("публичный узел Solana", rpcProviderName("https://api.mainnet-beta.solana.com"))
        assertEquals("rpc.example.org", rpcProviderName("https://rpc.example.org/path"))
        assertEquals("не задан", rpcProviderName(""))
    }

    @Test
    fun accountModeFromFlags() {
        assertEquals(AccountMode.DEMO, accountMode(demoMode = true, shadowMode = false))
        assertEquals(AccountMode.SHADOW, accountMode(demoMode = true, shadowMode = true))
        assertEquals(AccountMode.REAL, accountMode(demoMode = false, shadowMode = true), "в REAL флаг SHADOW не действует")
    }
}
