package tj.khujand.solana.trading.bot.exchange.dex

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Очередь запросов к API: ровный шаг, отказ вместо долгого ожидания, общая пауза после 429. Часы — ручные. */
class RequestLimiterTest {

    private val t0 = 1_000_000L
    private var now = t0
    private val slept = ArrayList<Long>()

    private fun limiter(intervalMs: Long = 15_000L) = RequestLimiter(
        intervalMs = { intervalMs },
        nowMs = { now },
        sleep = { ms -> slept += ms; now += ms },
    )

    @Test
    fun firstRequestGoesAtOnceAndTheRestAreEvenlySpaced() = runBlocking {
        val l = limiter()
        assertTrue(l.acquire(60_000L))
        assertTrue(slept.isEmpty(), "первый запрос не ждёт")
        assertTrue(l.acquire(60_000L))
        assertTrue(l.acquire(60_000L))
        assertEquals(listOf(15_000L, 15_000L), slept)
    }

    @Test
    fun refusesInsteadOfWaitingLongerThanCallerAllows() = runBlocking {
        val l = limiter()
        l.acquire(0L)
        assertFalse(l.acquire(10_000L), "до слота 15 с, а ждать готовы 10")
        assertTrue(slept.isEmpty(), "отказ — сразу, без ожидания")
        // Отказ слот не занял: следующий, кто готов подождать, получает его же.
        assertTrue(l.acquire(20_000L))
        assertEquals(listOf(15_000L), slept)
    }

    @Test
    fun pauseAfterRateLimitHoldsEveryRequest() = runBlocking {
        val l = limiter()
        l.acquire(0L)
        l.pauseUntil(now + 60_000L)
        assertFalse(l.acquire(20_000L))
        assertTrue(l.acquire(90_000L))
        assertEquals(t0 + 60_000L, now, "запрос ушёл ровно по окончании паузы")
    }

    @Test
    fun requestThatWasWaitingWhenRateLimitHitQueuesAgainAfterThePause() = runBlocking {
        var paused = false
        lateinit var l: RequestLimiter
        l = RequestLimiter(
            intervalMs = { 15_000L },
            nowMs = { now },
            // Пока второй запрос ждёт свой слот, первому приходит отказ 429.
            sleep = { ms ->
                now += ms
                if (!paused) {
                    paused = true
                    l.pauseUntil(now + 60_000L)
                }
            },
        )
        l.acquire(0L)
        assertTrue(l.acquire(120_000L))
        assertEquals(t0 + 15_000L + 60_000L, now, "дождался слота, увидел паузу и переждал её")
    }

    @Test
    fun intervalIsReadOnEveryRequest() = runBlocking {
        var interval = 15_000L
        val l = RequestLimiter(intervalMs = { interval }, nowMs = { now }, sleep = { ms -> slept += ms; now += ms })
        l.acquire(0L)
        interval = 1_000L // появился ключ API — шаг короче
        l.acquire(60_000L) // этот слот назначен ещё по старому шагу
        l.acquire(60_000L)
        assertEquals(listOf(15_000L, 1_000L), slept)
    }
}
