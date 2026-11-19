package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Спрос на свечи в карточке «Почему нет сделок» при свечах из блокчейна: пулы, которым бары даёт блокчейн, очередь
 * GeckoTerminal без ключа не нагружают — в оценке спроса их нет, а подсказка о нехватке их называет.
 */
class ChainCandleDemandTest {

    @Test
    fun poolsFedFromChainAreNotCandleDemand() {
        val now = 10_000_000L
        val funnel = EntryFunnel(nowMs = { now })
        funnel.record(CycleReport("s1", now).apply { candles(5 * 60_000L, listOf("A", "B", "C")) })

        val d = funnel.snapshot(chainPools = setOf("B", "Z")).candleDemand
        assertEquals(2, d.pools)
        assertEquals(0.4, d.requestsPerMinute, 1e-9)
        assertEquals(mapOf(5 * 60_000L to 2), d.poolsByTimeframeMs)
        assertEquals(1, d.chainPools, "Z стратегии не просят — не считается")

        assertEquals(CandleDemand(chainPools = 3), funnel.snapshot(chainPools = setOf("A", "B", "C")).candleDemand)
        assertEquals(3, funnel.snapshot().candleDemand.pools, "без свечей из блокчейна — как раньше")
    }

    @Test
    fun shortageHintMentionsCoinsFedFromChain() {
        val tooMuch = CandleDemand(pools = 30, requestsPerMinute = 6.0, poolsByTimeframeMs = mapOf(5 * 60_000L to 30), chainPools = 12)
        val hint = candleShortageHint(tooMuch, 4.0)!!
        assertTrue("(30 монет; ещё 12 монет получают свечи из блокчейна)" in hint, hint)
        assertNull(candleShortageHint(CandleDemand(pools = 10, requestsPerMinute = 2.0, chainPools = 25), 4.0))
        val one = candleShortageHint(tooMuch.copy(chainPools = 1), 4.0)!!
        assertTrue("ещё 1 монета получает свечи из блокчейна" in one, one)
    }
}
