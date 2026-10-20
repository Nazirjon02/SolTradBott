package tj.khujand.solana.trading.bot.exchange.dex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Разбор mint-аккаунта SPL: COption<Pubkey> фиксированного размера (4 + 32 байта) даже при
 * отозванной authority. Раньше парсер пропускал 32 байта ключа только при option ≠ 0 и при
 * отозванной mint authority читал freeze authority из нулей — она всегда казалась отозванной.
 */
class SplMintParseTest {

    private val rpc = SolanaRpcClient()

    @AfterTest fun close() = rpc.close()

    private fun mint(mintAuthority: Boolean, freezeAuthority: Boolean, extraBytes: Int = 0): String {
        val buf = ByteBuffer.allocate(82 + extraBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(if (mintAuthority) 1 else 0)
        buf.put(ByteArray(32) { if (mintAuthority) 9 else 0 })
        buf.putLong(1_000_000_000L)
        buf.put(6)       // decimals
        buf.put(1)       // is_initialized
        buf.putInt(if (freezeAuthority) 1 else 0)
        buf.put(ByteArray(32) { if (freezeAuthority) 7 else 0 })
        return Base64.getEncoder().encodeToString(buf.array())
    }

    @Test fun freezeAuthorityDetectedWhenMintAuthorityRevoked() {
        val info = assertNotNull(rpc.parseSplMintFromBase64(mint(mintAuthority = false, freezeAuthority = true)))
        assertEquals(false, info.hasMintAuthority)
        assertEquals(true, info.hasFreezeAuthority)
        assertEquals(6, info.decimals)
        assertEquals(1_000_000_000L, info.supply)
    }

    @Test fun bothRevoked() {
        val info = assertNotNull(rpc.parseSplMintFromBase64(mint(mintAuthority = false, freezeAuthority = false)))
        assertEquals(false, info.hasMintAuthority)
        assertEquals(false, info.hasFreezeAuthority)
    }

    @Test fun bothPresent() {
        val info = assertNotNull(rpc.parseSplMintFromBase64(mint(mintAuthority = true, freezeAuthority = true)))
        assertEquals(true, info.hasMintAuthority)
        assertEquals(true, info.hasFreezeAuthority)
        assertEquals(6, info.decimals)
    }

    @Test fun token2022ExtensionsAfterBaseAreIgnored() {
        val info = assertNotNull(rpc.parseSplMintFromBase64(mint(mintAuthority = false, freezeAuthority = false, extraBytes = 200)))
        assertEquals(false, info.hasFreezeAuthority)
        assertEquals(6, info.decimals)
    }
}
