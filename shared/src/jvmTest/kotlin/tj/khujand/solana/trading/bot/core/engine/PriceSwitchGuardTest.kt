package tj.khujand.solana.trading.bot.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import tj.khujand.solana.trading.bot.exchange.dex.LivePrice
import tj.khujand.solana.trading.bot.exchange.dex.PriceSource
import tj.khujand.solana.trading.bot.exchange.dex.PriceSource.DEXSCREENER
import tj.khujand.solana.trading.bot.exchange.dex.PriceSource.JUPITER

/** Смена источника цены без скачков: большой разрыв между источниками ждёт подтверждения следующей проверкой. */
class PriceSwitchGuardTest {

    private fun p(price: Double, source: PriceSource) = LivePrice(price, source, fetchedAtMs = 0L)

    @Test fun sameSourceMovesFreelyEvenOnBigJumps() {
        val g = PriceSwitchGuard()
        assertIs<PriceSwitchGuard.Verdict.Use>(g.check("t", p(1.0, JUPITER)))
        // −20% внутри одного источника — это рынок, не артефакт смены источника.
        val v = assertIs<PriceSwitchGuard.Verdict.Use>(g.check("t", p(0.8, JUPITER)))
        assertNull(v.switchedFrom)
    }

    @Test fun smallJumpOnSwitchIsAccepted() {
        val g = PriceSwitchGuard()
        g.check("t", p(1.0, JUPITER))
        val v = assertIs<PriceSwitchGuard.Verdict.Use>(g.check("t", p(1.02, DEXSCREENER)))
        assertEquals(JUPITER, v.switchedFrom)
        assertEquals(1.02, v.price.priceUsd)
    }

    @Test fun bigJumpOnSwitchWaitsForConfirmation() {
        val g = PriceSwitchGuard()
        g.check("t", p(1.0, JUPITER))
        // Отставший DexScreener показывает −15%: решения этого цикла — по прежней цене Jupiter.
        val hold = assertIs<PriceSwitchGuard.Verdict.Hold>(g.check("t", p(0.85, DEXSCREENER)))
        assertEquals(1.0, hold.last.priceUsd)
        assertEquals(15.0, hold.jumpPct, 1e-9)
        // Следующая проверка подтверждает уровень тем же источником — принимаем.
        val use = assertIs<PriceSwitchGuard.Verdict.Use>(g.check("t", p(0.86, DEXSCREENER)))
        assertEquals(0.86, use.price.priceUsd)
        assertEquals(JUPITER, use.switchedFrom)
    }

    @Test fun artefactIsDroppedWhenOldSourceReturns() {
        val g = PriceSwitchGuard()
        g.check("t", p(1.0, JUPITER))
        assertIs<PriceSwitchGuard.Verdict.Hold>(g.check("t", p(1.25, DEXSCREENER)))
        // Jupiter вернулся с прежним уровнем — скачок DexScreener был артефактом.
        val v = assertIs<PriceSwitchGuard.Verdict.Use>(g.check("t", p(1.01, JUPITER)))
        assertNull(v.switchedFrom)
        // И неподтверждённый уровень потом не «подтверждается» задним числом.
        assertIs<PriceSwitchGuard.Verdict.Hold>(g.check("t", p(1.25, DEXSCREENER)))
    }

    @Test fun keysAreIndependentAndForgotten() {
        val g = PriceSwitchGuard()
        g.check("a", p(1.0, JUPITER))
        g.check("b", p(5.0, DEXSCREENER))
        assertIs<PriceSwitchGuard.Verdict.Hold>(g.check("a", p(2.0, DEXSCREENER)))
        g.retain(setOf("b"))
        // Сделку «a» забыли — первая цена снова принимается как есть.
        assertIs<PriceSwitchGuard.Verdict.Use>(g.check("a", p(2.0, DEXSCREENER)))
    }
}
