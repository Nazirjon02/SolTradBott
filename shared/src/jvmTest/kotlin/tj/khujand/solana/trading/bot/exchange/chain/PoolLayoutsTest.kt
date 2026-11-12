package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Разбор настоящих аккаунтов пулов (ответы getMultipleAccounts 2026-11-11 в `resources/chain`):
 * PumpSwap HOLD, CPMM jeff, AMM v4 SPX, CLMM TTWO/USDC, CPMM MASK без SOL/USDC и Meteora STONK.
 */
class PoolLayoutsTest {
    private val pools = listOf(
        "GUsid5mPW6KiExjQDDggoTHrFv4ecGqqAgYwiHa4Ujbb",
        "JCXWfcZGjgJC8NZa5Qb6JP3cgvqW4vGUg4Tqj87G5ZnY",
        "9t1H1uDJ558iMPNkEPSN1fqkpC4XSPQ6cqSf6uEsTfTR",
        "6PTm7MkUmtsLU7w6dWrMHJAt8PnzoCgcZdKyTKEhUAPL",
        "4GWh5saKgzoukhhj4Rh3p5FmPU6yPuAexV6FaH5JQYfm",
        "zxTpi4BtaWX3mgdAPoezkMD1hxx8CdeCfrqXMWvSCLX",
    )
    private val second = listOf(
        "4iJ7xEQrUvVUQH9KVStvw3FaY2PZDd7RtR4L9zx2pump",
        "rFkn84fuSQ3ovZECNLF7JnX5xy1to6KR7LM6TSQtKgT",
        "DGUVmREQ4hpRmkf9sRP4Fb48az5ACCLoxH4FMrvEPrZN",
    )

    private fun load(name: String, addrs: List<String>): Map<String, RawAccount> {
        val text = PoolLayoutsTest::class.java.getResource("/chain/$name")!!.readText()
        return PoolLayouts.parseMultipleAccounts(addrs, Json.parseToJsonElement(text))
    }

    private val accounts = load("pool_accounts.json", pools)
    private val extra = load("second_accounts.json", second)

    private fun resolve(pool: String, tokenMint: String): PoolResolution {
        val p = assertNotNull(PoolLayouts.parsePool(pool, accounts.getValue(pool)))
        return PoolLayouts.finish(p, tokenMint, extra)
    }

    @Test
    fun `PumpSwap — mint и хранилища из аккаунта пула, десятичные монеты из mint`() {
        val p = assertNotNull(PoolLayouts.parsePool(pools[0], accounts.getValue(pools[0])))
        assertEquals(ChainDex.PUMPSWAP, p.dex)
        assertEquals("4iJ7xEQrUvVUQH9KVStvw3FaY2PZDd7RtR4L9zx2pump", p.mint0)
        assertEquals(WSOL_MINT, p.mint1)
        assertTrue("4iJ7xEQrUvVUQH9KVStvw3FaY2PZDd7RtR4L9zx2pump" in p.needs())
        val r = assertIs<PoolResolution.Ok>(PoolLayouts.finish(p, p.mint0, extra)).pool
        assertEquals(6, r.decimals0) // mint Token-2022, decimals @44
        assertEquals(9, r.decimals1)
        assertEquals(QuoteAsset.SOL, r.quote)
        assertTrue(r.tokenIsSide0)
    }

    @Test
    fun `CPMM — монета на стороне token1, SOL на token0`() {
        val r = assertIs<PoolResolution.Ok>(resolve(pools[1], "HAsTKXYvv65zM7J6Cu5o5PcZsiKMQYo5oz4WPjgqwP6k")).pool
        assertEquals(ChainDex.RAYDIUM_CPMM, r.dex)
        assertEquals(WSOL_MINT, r.mint0)
        assertEquals(9, r.decimals0)
        assertEquals(9, r.decimals1)
        assertEquals(false, r.tokenIsSide0)
    }

    @Test
    fun `AMM v4 — десятичные из состояния, резервы из хранилищ`() {
        val r = assertIs<PoolResolution.Ok>(resolve(pools[2], "J3NKxxXZcnNiMjKw9hYb2K4LUxgwB6t1FtPtQVsv3KFr")).pool
        assertEquals(ChainDex.RAYDIUM_AMM_V4, r.dex)
        assertEquals(8, r.decimals0)
        assertEquals(9, r.decimals1)
        assertEquals(254_345_253_420_013.0, r.reserve0)
        assertEquals(11_415_049_839_158.0, r.reserve1)
    }

    @Test
    fun `CLMM — котировка USDC`() {
        val r = assertIs<PoolResolution.Ok>(resolve(pools[3], "TTWofwAge91oFhZs7kpQdyrVRkmevgM88xijGvQFbKo")).pool
        assertEquals(ChainDex.RAYDIUM_CLMM, r.dex)
        assertEquals(QuoteAsset.USDC, r.quote)
        assertEquals(6, r.decimals0)
        assertEquals(6, r.decimals1)
    }

    @Test
    fun `пул без SOL и USDC пропускается`() {
        val skip = assertIs<PoolResolution.Skip>(resolve(pools[4], "HuAXPyDWDaMYFKuwQHpqL1oPnj93zdzWmtvFGzCeCUa7"))
        assertEquals(SkipReason.QUOTE, skip.reason)
    }

    @Test
    fun `пул Meteora — не наша программа`() {
        val acc = accounts.getValue(pools[5])
        assertEquals(null, ChainDex.byProgram(acc.owner))
        assertEquals(null, PoolLayouts.parsePool(pools[5], acc))
    }

    @Test
    fun `mint монеты не совпал ни с одной стороной — пул не разобран`() {
        val skip = assertIs<PoolResolution.Skip>(resolve(pools[1], "So1anaMintNotInThisPoo1111111111111111111111"))
        assertEquals(SkipReason.BAD_LAYOUT, skip.reason)
    }
}
