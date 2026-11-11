package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Грубый отсев пула сканером: что отсекается, а что — вопреки прежним правилам — нет. */
class ScanRejectionTest {

    private fun pair(
        liquidity: Double? = 30_000.0,
        fdv: Double? = 600_000.0,
        volumeH24: Double = 100_000.0,
        buysH24: Int = 1_000,
        sellsH24: Int = 900,
        buysM5: Int = 5,
    ) = TokenPair(
        chainId = "solana",
        pairAddress = "pool",
        baseToken = BaseToken("mint", "Coin", "COIN"),
        volume = Volume(h24 = volumeH24),
        liquidity = liquidity?.let { Liquidity(it) },
        fdv = fdv,
        txns = Txns(h24 = BuySell(buysH24, sellsH24), m5 = BuySell(buysM5, 0)),
    )

    @Test
    fun ordinaryCoinPasses() {
        assertNull(scanRejection(pair()))
    }

    @Test
    fun hotCoinWithVolumeManyTimesLiquidityPasses() {
        // Объём за сутки в 40 раз больше ликвидности — обычное дело в первые часы; раньше считалось скамом.
        assertNull(scanRejection(pair(volumeH24 = 1_200_000.0)))
    }

    @Test
    fun quietCoinWithoutBuysInLastFiveMinutesPasses() {
        // Тишина в накоплении — то, что стратегии и ищут; раньше ноль покупок за 5 минут отсекал монету.
        assertNull(scanRejection(pair(buysM5 = 0)))
    }

    @Test
    fun drainedOrEmptyPoolIsRejected() {
        assertNotNull(scanRejection(pair(liquidity = 2_000.0)))
        assertNotNull(scanRejection(pair(liquidity = null)))
    }

    @Test
    fun poolTooThinForItsValuationIsRejected() {
        assertNotNull(scanRejection(pair(liquidity = 5_000.0, fdv = 600_000.0)))
        assertNotNull(scanRejection(pair(fdv = null)), "без капитализации оценивать нечего")
    }

    @Test
    fun coinBeingDumpedIsRejected() {
        assertNotNull(scanRejection(pair(buysH24 = 100, sellsH24 = 400)))
    }
}
