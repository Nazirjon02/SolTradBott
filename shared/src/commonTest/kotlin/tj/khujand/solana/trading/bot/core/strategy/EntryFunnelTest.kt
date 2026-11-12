package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Воронка входа: монеты без повторов, окно в час, причины потерь и последний цикл каждой стратегии. */
class EntryFunnelTest {

    private var now = 10_000_000L
    private val funnel = EntryFunnel(windowMs = 60 * 60_000L, bucketMs = 60_000L, nowMs = { now })

    private fun cycle(strategy: String = "s1", at: Long = now, block: (CycleReport.() -> Unit) = {}) =
        funnel.record(CycleReport(strategy, at).apply(block))

    @Test
    fun coinsAreCountedOncePerWindow() {
        // Стратегия видит одни и те же монеты каждые 30 с — в воронке это 3 монеты, а не 6.
        cycle { coins += listOf("A", "B", "C") }
        now += 30_000
        cycle { coins += listOf("A", "B", "C") }
        val s = funnel.snapshot()
        assertEquals(2, s.cycles)
        assertEquals(3, s.coins)
    }

    @Test
    fun oldBucketsLeaveTheWindow() {
        cycle { coins += "OLD"; setups += "OLD" }
        now += 61 * 60_000L
        cycle { coins += "NEW" }
        val s = funnel.snapshot()
        assertEquals(1, s.coins, "монета старше часа из воронки ушла")
        assertEquals(0, s.setups)
        assertEquals(1, s.cycles)
    }

    @Test
    fun lossesAreGroupedByReasonWithoutRepeats() {
        cycle {
            coins += listOf("A", "B", "C")
            setups += listOf("A", "B", "C")
            loss("A", EntryLoss.COIN_CHECK)
            loss("B", EntryLoss.COIN_CHECK)
            loss("C", EntryLoss.PRICE_RAN)
        }
        now += 30_000
        cycle { coins += "A"; setups += "A"; loss("A", EntryLoss.COIN_CHECK) }
        val s = funnel.snapshot()
        assertEquals(2, s.losses[EntryLoss.COIN_CHECK])
        assertEquals(1, s.losses[EntryLoss.PRICE_RAN])
        assertEquals(3, s.rejectedCoins)
        assertEquals(3, s.setups)
    }

    @Test
    fun lastCycleOfEachStrategyIsKept() {
        cycle("s1") { block(CycleBlock.COOLDOWN, "ещё 120 с") }
        cycle("s2") { coins += "A" }
        val s = funnel.snapshot()
        assertEquals(CycleBlock.COOLDOWN, s.strategies["s1"]?.block)
        assertEquals("ещё 120 с", s.strategies["s1"]?.detail)
        assertNull(s.strategies["s2"]?.block)
        assertEquals(1, s.blockedCycles[CycleBlock.COOLDOWN])
        assertEquals(now, s.lastCycleAt)
    }

    @Test
    fun armedEntryIsNotACycle() {
        cycle { coins += "A" }
        funnel.record(CycleReport("s1", now, countsAsCycle = false).apply { setups += "B"; opened++ })
        val s = funnel.snapshot()
        assertEquals(1, s.cycles)
        assertEquals(1, s.opened)
        assertEquals(1, s.setups, "сетап взвода — монета B; в цикле сканера сетапов не было")
        assertNull(s.strategies["s1"]?.block, "вход по взводу не перезаписывает последний цикл")
    }

    @Test
    fun forgottenStrategyLeavesTheSummary() {
        cycle("s1") { block(CycleBlock.PORTFOLIO, "3 позиции из 3") }
        funnel.forgetStrategy("s1")
        assertTrue(funnel.snapshot().strategies.isEmpty())
    }

    @Test
    fun versionGrowsWithEveryReport() {
        val before = funnel.version.value
        cycle { }
        cycle { }
        assertEquals(before + 2, funnel.version.value)
    }

    @Test
    fun candleDemandCountsEachPoolOnceOnItsFinestTimeframe() {
        // s1 разбирает A и B на 5m, s2 — B и C на 1m: B нужен раз в минуту (5m собирается из 1m), A — раз в 5 минут.
        cycle("s1") { candles(5 * 60_000L, listOf("A", "B")) }
        cycle("s2") { candles(60_000L, listOf("B", "C")) }
        val d = funnel.snapshot().candleDemand
        assertEquals(3, d.pools)
        assertEquals(2.2, d.requestsPerMinute, 1e-9)
        assertEquals(mapOf(60_000L to 2, 5 * 60_000L to 1), d.poolsByTimeframeMs)

        // Стратегия давно не сканировала (кулдаун, лимит) — её пулы спросом не считаются; выключенная — тоже.
        now += 6 * 60_000L
        cycle("s2") { candles(60_000L, listOf("B", "C")) }
        assertEquals(2, funnel.snapshot().candleDemand.pools)
        funnel.forgetStrategy("s2")
        assertEquals(0, funnel.snapshot().candleDemand.pools)
    }

    @Test
    fun blockedCycleKeepsPreviousCandleDemand() {
        cycle("s1") { candles(5 * 60_000L, listOf("A")) }
        now += 30_000
        cycle("s1") { block(CycleBlock.COOLDOWN, "ещё 60 с") } // в этом цикле свечей не просили
        assertEquals(1, funnel.snapshot().candleDemand.pools)
    }
}
