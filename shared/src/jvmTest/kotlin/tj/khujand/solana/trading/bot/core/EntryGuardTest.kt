package tj.khujand.solana.trading.bot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.risk.EntryGuard
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.exchange.dex.QuoteResult
import tj.khujand.solana.trading.bot.exchange.dex.TOKEN_2022_PROGRAM_ID

/**
 * Проверка монеты перед покупкой: authority, Token-2022, влияние на цену, обратная продажа.
 * Вход $100 при SOL = $100 → котировка покупки на 1 SOL (1e9 lamports).
 */
class EntryGuardTest {

    private var now = 1_000_000L

    private fun guard(q: FakeSwapQuotes, s: ProtectionSettings = ProtectionSettings()) = EntryGuard(q, { s }, { now })

    private suspend fun EntryGuard.check100() = check("mint1", sizeUsd = 100.0, buySlippageBps = 100)

    @Test fun cleanTokenPasses() = runBlocking {
        val q = FakeSwapQuotes()
        val pass = assertIs<EntryGuard.Verdict.Pass>(guard(q).check100())
        assertEquals(1_000_000_000L, pass.buyQuote?.inAmount)
        assertEquals(2.0, pass.roundTripLossPct!!, 0.01) // sellReturn 0.98
        // Продажа проверяется на всё купленное количество.
        assertEquals(listOf("mint", "buy:1000000000", "sell:100000000000@300"), q.calls)
    }

    @Test fun mintAuthorityRejectedBeforeQuotes() = runBlocking {
        val q = FakeSwapQuotes(mint = CLEAN_MINT.copy(hasMintAuthority = true))
        val reject = assertIs<EntryGuard.Verdict.Reject>(guard(q).check100())
        assertTrue("mint authority" in reject.reason)
        assertTrue(q.calls.none { it.startsWith("buy") }, "до котировок дело не доходит")
    }

    @Test fun freezeAuthorityRejected() = runBlocking {
        val q = FakeSwapQuotes(mint = CLEAN_MINT.copy(hasFreezeAuthority = true))
        assertTrue("freeze authority" in assertIs<EntryGuard.Verdict.Reject>(guard(q).check100()).reason)
    }

    @Test fun authoritiesAllowedWhenCheckDisabled() = runBlocking {
        val q = FakeSwapQuotes(mint = CLEAN_MINT.copy(hasMintAuthority = true, hasFreezeAuthority = true))
        assertIs<EntryGuard.Verdict.Pass>(guard(q, ProtectionSettings(requireRevokedAuthorities = false)).check100())
        Unit
    }

    @Test fun riskyToken2022Rejected() = runBlocking {
        // MetadataPointer + TokenMetadata — безобидны, PermanentDelegate — может забрать токены у любого.
        val q = FakeSwapQuotes(mint = CLEAN_MINT.copy(tokenProgram = TOKEN_2022_PROGRAM_ID, extensions = listOf(18, 19, 12)))
        val reject = assertIs<EntryGuard.Verdict.Reject>(guard(q).check100())
        assertTrue("перманентный делегат" in reject.reason, reject.reason)
    }

    @Test fun transferFeeToken2022Rejected() = runBlocking {
        val q = FakeSwapQuotes(mint = CLEAN_MINT.copy(tokenProgram = TOKEN_2022_PROGRAM_ID, extensions = listOf(1)))
        assertTrue("комиссия за перевод" in assertIs<EntryGuard.Verdict.Reject>(guard(q).check100()).reason)
    }

    @Test fun metadataOnlyToken2022Passes() = runBlocking {
        val q = FakeSwapQuotes(mint = CLEAN_MINT.copy(tokenProgram = TOKEN_2022_PROGRAM_ID, extensions = listOf(18, 19)))
        assertIs<EntryGuard.Verdict.Pass>(guard(q).check100())
        Unit
    }

    @Test fun noSellRouteRejectedAndRemembered() = runBlocking {
        val q = FakeSwapQuotes(sell = { QuoteResult.NoRoute("COULD_NOT_FIND_ANY_ROUTE") })
        val g = guard(q)
        val first = assertIs<EntryGuard.Verdict.Reject>(g.check100())
        assertTrue("нет маршрута продажи" in first.reason)
        assertFalse(first.cached)

        q.calls.clear()
        val second = assertIs<EntryGuard.Verdict.Reject>(g.check100())
        assertTrue(second.cached, "повтор берётся из памяти")
        assertTrue(q.calls.isEmpty(), "без новых запросов к Jupiter")

        // Через 5 минут монету проверяем заново — маршрут мог появиться.
        now += 5 * 60_000L + 1
        q.sell = null
        assertIs<EntryGuard.Verdict.Pass>(g.check100())
        Unit
    }

    @Test fun highRoundTripLossRejected() = runBlocking {
        val q = FakeSwapQuotes(sellReturn = 0.9) // −10% «туда-обратно» при лимите 5%
        assertTrue("туда-обратно" in assertIs<EntryGuard.Verdict.Reject>(guard(q).check100()).reason)
    }

    @Test fun zeroRoundTripLimitDisablesCheckButNotRoute() = runBlocking {
        val s = ProtectionSettings(maxRoundTripLossPct = 0.0)
        val pass = assertIs<EntryGuard.Verdict.Pass>(guard(FakeSwapQuotes(sellReturn = 0.5), s).check100())
        assertEquals(50.0, pass.roundTripLossPct!!, 0.01)
        // Маршрут продажи нужен всегда.
        val noRoute = FakeSwapQuotes(sell = { QuoteResult.NoRoute("x") })
        assertIs<EntryGuard.Verdict.Reject>(guard(noRoute, s).check100())
        Unit
    }

    @Test fun highPriceImpactRejectedBeforeSellQuote() = runBlocking {
        val q = FakeSwapQuotes(impactPct = 4.0) // лимит 3%
        assertTrue("влияние на цену" in assertIs<EntryGuard.Verdict.Reject>(guard(q).check100()).reason)
        assertTrue(q.calls.none { it.startsWith("sell") })
    }

    @Test fun networkFailuresAreNotRemembered() = runBlocking {
        val q = FakeSwapQuotes(mint = null)
        val g = guard(q)
        assertFalse(assertIs<EntryGuard.Verdict.Reject>(g.check100()).cached)
        q.mint = CLEAN_MINT
        assertIs<EntryGuard.Verdict.Pass>(g.check100())

        q.sell = { QuoteResult.Failed("HTTP 429") }
        assertIs<EntryGuard.Verdict.Reject>(g.check100())
        q.sell = null
        assertIs<EntryGuard.Verdict.Pass>(g.check100(), "сбой сети не должен запомниться как отказ")
        Unit
    }

    @Test fun noSolPriceRejects() = runBlocking {
        val q = FakeSwapQuotes(solPrice = null)
        assertTrue("цены SOL" in assertIs<EntryGuard.Verdict.Reject>(guard(q).check100()).reason)
    }

    @Test fun disabledGuardPassesWithoutRequests() = runBlocking {
        val q = FakeSwapQuotes(mint = CLEAN_MINT.copy(hasMintAuthority = true), sell = { QuoteResult.NoRoute("x") })
        val pass = assertIs<EntryGuard.Verdict.Pass>(guard(q, ProtectionSettings(guardEnabled = false)).check100())
        assertNull(pass.buyQuote)
        assertTrue(q.calls.isEmpty())
    }
}
