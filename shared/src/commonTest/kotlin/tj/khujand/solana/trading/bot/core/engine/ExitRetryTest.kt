package tj.khujand.solana.trading.bot.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** Повторные попытки выхода: slippage продажи растёт до потолка, пауза — только у «заблокированной» продажи. */
class ExitRetryTest {

    @Test fun sellSlippageDoublesUpToCap() {
        assertEquals(listOf(300, 600, 1200, 1500, 1500), (0..4).map { sellSlippageBps(it, maxPct = 15.0) })
    }

    @Test fun capBelowBaseKeepsBaseSlippage() {
        assertEquals(300, sellSlippageBps(5, maxPct = 1.0))
    }

    @Test fun firstFailuresRetryNextCycle() {
        assertEquals(0L, exitRetryDelayMs(1))
        assertEquals(0L, exitRetryDelayMs(EXIT_BLOCKED_AFTER - 1))
    }

    @Test fun blockedExitPauseGrowsAndIsCapped() {
        assertEquals(
            listOf(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L),
            listOf(3, 4, 5, 6, 7, 30).map { exitRetryDelayMs(it) },
        )
    }
}
