package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** Дневной лимит ключа CoinGecko: остаток квоты делится на оставшиеся дни месяца, счёт переживает перезапуск. */
class KeyBudgetTest {

    private fun utc(year: Int, month: Int, d: Int, hour: Int = 0) =
        LocalDateTime(year, month, d, hour, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private var now = utc(2026, 11, 11, 12)
    private var saved: KeyUsage? = null
    private var quota = DEFAULT_KEY_MONTHLY_QUOTA

    private fun budget() = KeyBudget(monthlyQuota = { quota }, load = { saved }, save = { saved = it }, nowMs = { now })

    private fun KeyBudget.spend(n: Int) = repeat(n) { assertTrue(canSpend()); record() }

    @Test
    fun quotaIsSpreadOverTheDaysLeft() {
        // 11–30 ноября — 20 дней: 10 000 / 20 = 500 в сутки.
        val v = budget().view()
        assertEquals(500, v.allowanceToday)
        assertEquals(0, v.usedToday)
        assertEquals(utc(2026, 11, 12), v.nextDayMs, "новые сутки — с полуночи UTC")
    }

    @Test
    fun spendingStopsAtTheDailyAllowance() {
        val b = budget()
        b.spend(500)
        assertFalse(b.canSpend(), "500 из 500 — дальше без ключа")
        assertEquals(500, b.view().usedToday)
    }

    @Test
    fun unspentAllowanceRollsOverToTheNextDays() {
        val b = budget()
        b.spend(100)
        now = utc(2026, 11, 12, 1)
        // Потрачено 100, осталось 9 900 на 19 дней.
        assertEquals(9_900 / 19, b.view().allowanceToday)
        assertEquals(0, b.view().usedToday)
        assertEquals(100, b.view().usedMonth)
    }

    @Test
    fun overspendingShrinksTheNextDays() {
        quota = 1_000
        val b = budget()
        assertEquals(50, b.view().allowanceToday) // 1 000 / 20
        b.spend(50)
        now = utc(2026, 11, 12, 1)
        assertEquals(950 / 19, b.view().allowanceToday)
    }

    @Test
    fun newMonthStartsFromZero() {
        val b = budget()
        b.spend(300)
        now = utc(2026, 12, 1, 0)
        val v = b.view()
        assertEquals(0, v.usedMonth)
        assertEquals(10_000 / 31, v.allowanceToday, "декабрь — 31 день")
    }

    @Test
    fun countSurvivesRestart() {
        budget().spend(42)
        val restarted = budget()
        assertEquals(42, restarted.view().usedToday)
        assertEquals(42, restarted.view().usedMonth)
    }

    @Test
    fun monthlyQuotaChangeAppliesAtOnce() {
        val b = budget()
        quota = 31_000 // платный план
        assertEquals(31_000 / 20, b.view().allowanceToday)
    }

    @Test
    fun lastDayOfMonthGetsEverythingLeft() {
        now = utc(2026, 11, 30, 18)
        saved = KeyUsage(month = "2026-11", usedMonth = 9_000, day = "2026-11-30", usedToday = 0, usedBeforeToday = 9_000)
        assertEquals(1_000, budget().view().allowanceToday)
    }
}
