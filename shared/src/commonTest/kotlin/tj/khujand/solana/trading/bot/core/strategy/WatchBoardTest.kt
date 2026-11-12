package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Доска наблюдения: шаги детекторов, статус строки и жизнь строк на доске. */
class WatchBoardTest {

    private val cfg = StrategyConfig(id = "vb", name = "Пробой на объёме", timeframe = "5m", stopLossPercent = 15.0)

    @Test
    fun detectorTellsWhereSetupStopped() {
        val f = VolumeBreakoutFixtures
        val wick = VolumeBreakoutDetector.detect(
            f.accumulation() + f.impulse() + f.pullback() + f.retest() + f.breakout(close = 1.315, high = 1.37), cfg,
        )
        val r = assertIs<VolumeBreakoutResult.Reject>(wick)
        assertEquals(5, r.stage, r.reason)
        assertEquals(1.32, r.level!!, 1e-9) // максимум, пробоя которого ждём

        val quiet = VolumeBreakoutDetector.detect(f.accumulation() + f.impulse() + f.pullback() + f.retest() + f.breakout(volume = 200.0), cfg)
        assertEquals(6, assertIs<VolumeBreakoutResult.Reject>(quiet).stage)
    }

    @Test
    fun statusFollowsProgressAndDistance() {
        val signal = Signal("m", "S", "p", 0.8, "r", 1.0, 0.9, 1.3)
        assertEquals(WatchStatus.READY, watchStatus(Evaluation.ready(signal, 8), 1.0, accepted = true))
        assertEquals(WatchStatus.NEAR, watchStatus(Evaluation.ready(signal, 8), 1.0, accepted = false))
        // 5 из 8 пройдено, уровень в 1% над ценой — «близко»; в 10% — «ждём»; 2 из 8 — не на доске.
        assertEquals(WatchStatus.NEAR, watchStatus(Evaluation.stoppedAt(6, 8, "x", entryLevel = 1.01), 1.0, accepted = true))
        assertEquals(WatchStatus.WAITING, watchStatus(Evaluation.stoppedAt(6, 8, "x", entryLevel = 1.10), 1.0, accepted = true))
        assertEquals(WatchStatus.NONE, watchStatus(Evaluation.stoppedAt(3, 8, "x", entryLevel = 1.0), 1.0, accepted = true))
    }

    private fun item(strategy: String, mint: String, status: WatchStatus, at: Long, passed: Int = 5) = WatchItem(
        mint = mint, symbol = mint.uppercase(), pairAddress = "p", strategyId = strategy, strategyName = strategy,
        timeframe = "5m", price = 1.0, entryLevel = null, passed = passed, total = 8, missing = null, status = status, updatedAt = at,
    )

    @Test
    fun boardKeepsOneRowPerCoinAndStrategyBestFirst() {
        var now = 1_000_000L
        val board = WatchBoard(ttlMs = 60_000L, nowMs = { now })
        board.update(item("s1", "a", WatchStatus.WAITING, now))
        board.update(item("s1", "b", WatchStatus.READY, now))
        board.update(item("s2", "a", WatchStatus.NEAR, now))
        board.update(item("s1", "a", WatchStatus.NEAR, now, passed = 7)) // та же монета и стратегия — замена
        assertEquals(listOf("b", "a", "a"), board.items.value.map { it.mint })
        assertEquals(listOf(WatchStatus.READY, WatchStatus.NEAR, WatchStatus.NEAR), board.items.value.map { it.status })
        assertEquals(7, board.items.value[1].passed, "внутри статуса — по доле пройденных условий")

        board.remove("b")
        board.clearStrategy("s2")
        assertEquals(listOf("s1" to "a"), board.items.value.map { it.strategyId to it.mint })

        now += 61_000L // строка «a» не обновлялась дольше TTL — уходит при следующем обновлении доски
        board.update(item("s1", "c", WatchStatus.WAITING, now))
        assertEquals(listOf("c"), board.items.value.map { it.mint })
    }

    @Test
    fun removeAllDropsListedCoinsAndLeavesBoardAloneOtherwise() {
        val board = WatchBoard(ttlMs = 60_000L, nowMs = { 1_000_000L })
        board.update(item("s1", "a", WatchStatus.NEAR, 1_000_000L))
        board.update(item("s2", "a", WatchStatus.WAITING, 1_000_000L))
        board.update(item("s1", "b", WatchStatus.WAITING, 1_000_000L))
        val before = board.items.value
        board.removeAll(setOf("x", "y"))
        assertTrue(before === board.items.value, "монет с доски в наборе нет — список тот же, подписчики не дёргаются")
        board.removeAll(setOf("a", "x"))
        assertEquals(listOf("b"), board.items.value.map { it.mint }, "монета уходит со строк всех стратегий")
    }
}
