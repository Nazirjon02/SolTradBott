package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** Месячная квота ключа CoinGecko: когда считать её кончившейся и когда пробовать ключ снова. */
class KeyQuotaWatchTest {

    private val day = 24 * 60 * 60_000L

    private fun utc(year: Int, month: Int, d: Int, hour: Int = 0) =
        LocalDateTime(year, month, d, hour, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private val now = utc(2026, 11, 11, 11)

    @Test
    fun threeRefusalsInARowMeanTheQuotaIsGone() {
        val w = KeyQuotaWatch()
        assertNull(w.onKeyedResponse(429, now))
        assertNull(w.onKeyedResponse(429, now))
        assertEquals(now + day, w.onKeyedResponse(429, now), "до сброса квоты далеко — ключ пробуем снова через сутки")
    }

    @Test
    fun successInBetweenStartsTheCountAgain() {
        val w = KeyQuotaWatch()
        w.onKeyedResponse(429, now)
        w.onKeyedResponse(429, now)
        w.onKeyedResponse(200, now)
        assertNull(w.onKeyedResponse(429, now))
        assertNull(w.onKeyedResponse(429, now))
    }

    @Test
    fun serverErrorsNeitherCountNorReset() {
        val w = KeyQuotaWatch()
        w.onKeyedResponse(429, now)
        assertNull(w.onKeyedResponse(503, now))
        assertNull(w.onKeyedResponse(429, now))
        assertEquals(now + day, w.onKeyedResponse(429, now))
    }

    @Test
    fun keyIsRetriedWhenTheMonthTurnsIfThatIsSooner() {
        val w = KeyQuotaWatch()
        val lateInMonth = utc(2026, 11, 30, 12)
        repeat(2) { w.onKeyedResponse(429, lateInMonth) }
        assertEquals(utc(2026, 12, 1), w.onKeyedResponse(429, lateInMonth))
    }

    @Test
    fun savingTheKeyAgainForgetsRefusals() {
        val w = KeyQuotaWatch()
        repeat(2) { w.onKeyedResponse(429, now) }
        w.reset()
        assertNull(w.onKeyedResponse(429, now))
    }

    @Test
    fun nextMonthStartCrossesTheYear() {
        assertEquals(utc(2027, 1, 1), nextMonthStartUtc(utc(2026, 12, 15, 8)))
        assertEquals(utc(2026, 12, 1), nextMonthStartUtc(utc(2026, 11, 1)))
    }
}
