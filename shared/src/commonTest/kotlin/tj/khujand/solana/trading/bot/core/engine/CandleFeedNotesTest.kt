package tj.khujand.solana.trading.bot.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.CandleFeedStatus

/** Что о состоянии источника свечей попадает в ленту — и что не должно её засорять. */
class CandleFeedNotesTest {

    private var now = 1_000_000L
    private val notes = CandleFeedNotes(nowMs = { now })

    @Test
    fun keylessModeIsExplainedOnce() {
        val first = notes.onStatus(CandleFeedStatus())
        assertEquals(ActivityLevel.INFO, first.single().first)
        assertTrue(first.single().second.contains("без ключа"))
        assertTrue(notes.onStatus(CandleFeedStatus(lastOkAtMs = now)).isEmpty(), "повторять на каждый ответ незачем")
    }

    @Test
    fun workingKeyIsExplainedOnce() {
        val first = notes.onStatus(CandleFeedStatus(usingKey = true)).single()
        assertEquals(ActivityLevel.INFO, first.first)
        assertTrue(first.second.contains("торговля идёт без ключа"), first.second)
        assertTrue(notes.onStatus(CandleFeedStatus(usingKey = true, lastOkAtMs = now)).isEmpty())
    }

    @Test
    fun rejectedKeyIsAnErrorSaidOnce() {
        notes.onStatus(CandleFeedStatus(usingKey = true))
        val rejected = notes.onStatus(CandleFeedStatus(keyRejected = true))
        assertEquals(ActivityLevel.ERROR, rejected.single().first)
        assertTrue(notes.onStatus(CandleFeedStatus(keyRejected = true, lastOkAtMs = now)).isEmpty())
    }

    @Test
    fun rateLimitRefusalsAreReportedNotMoreThanOncePerTenMinutes() {
        notes.onStatus(CandleFeedStatus(usingKey = true))
        val first = notes.onStatus(CandleFeedStatus(usingKey = true, rateLimitHits = 1))
        assertEquals(ActivityLevel.WARN, first.single().first)

        now += 60_000L
        assertTrue(notes.onStatus(CandleFeedStatus(usingKey = true, rateLimitHits = 2)).isEmpty())

        now += 10 * 60_000L
        assertEquals(1, notes.onStatus(CandleFeedStatus(usingKey = true, rateLimitHits = 3)).size)
    }

    @Test
    fun exhaustedQuotaIsReportedOnceWithoutSuggestingTheKey() {
        notes.onStatus(CandleFeedStatus(usingKey = true))
        val exhausted = CandleFeedStatus(keyExhaustedUntilMs = now + 3_600_000L)
        val first = notes.onStatus(exhausted).single()
        assertEquals(ActivityLevel.WARN, first.first)
        assertTrue(first.second.contains("квота"), first.second)
        assertTrue(notes.onStatus(exhausted.copy(lastOkAtMs = now)).isEmpty(), "ни повтора, ни «ключа нет» — ключ просто отложен")
        val limitNote = notes.onStatus(exhausted.copy(rateLimitHits = 1)).single().second
        assertTrue(!limitNote.contains("Данные рынка"), "ключ уже есть — предлагать его незачем: $limitNote")
    }

    @Test
    fun refusalNoteDoesNotPromiseTheKeyFixesTrading() {
        notes.onStatus(CandleFeedStatus())
        val note = notes.onStatus(CandleFeedStatus(rateLimitHits = 1)).single().second
        assertTrue(note.contains("без ключа"), note)
        assertTrue(!note.contains("поднимает лимит"), "торговля всегда без ключа — ключ её не ускорит: $note")
    }

    @Test
    fun spentDailyAllowanceIsNotedOncePerDay() {
        val day1 = CandleFeedStatus(usingKey = true, keyDailyAllowance = 500, keyNextDayMs = 86_400_000L)
        notes.onStatus(day1.copy(keyUsedToday = 499))
        val spent = notes.onStatus(day1.copy(keyUsedToday = 500)).single()
        assertEquals(ActivityLevel.INFO, spent.first)
        assertTrue(spent.second.contains("дневной лимит"), spent.second)
        assertTrue(notes.onStatus(day1.copy(keyUsedToday = 500, lastOkAtMs = now)).isEmpty())

        val day2 = day1.copy(keyNextDayMs = 2 * 86_400_000L, keyUsedToday = 0)
        assertTrue(notes.onStatus(day2).isEmpty())
        assertEquals(1, notes.onStatus(day2.copy(keyUsedToday = 500)).size, "на следующие сутки — снова одно сообщение")
    }
}
