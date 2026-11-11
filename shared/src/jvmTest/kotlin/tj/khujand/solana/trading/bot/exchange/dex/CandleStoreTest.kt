package tj.khujand.solana.trading.bot.exchange.dex

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Свечи с обновлением раз в бар: стратегии видят только закрытые бары, один ответ служит до закрытия
 * следующего, отказ по лимиту не превращается в «свечей нет». Сеть и часы — подставные.
 */
class CandleStoreTest {

    private val bar = 300_000L // 5m
    private val key = CandleKey("solana", "POOL", "minute", 5)

    /** Номер текущего (ещё не закрытого) бара: истории хватает на любой запрошенный лимит. */
    private val cur = 400

    /** С открытия текущего бара прошла минута. */
    private var now = cur * bar + 60_000L

    private val asked = ArrayList<Int>()
    private var answer: (limit: Int) -> CandleFetch = { CandleFetch.Ok(barsUpTo(now)) }
    private var liveAllowed = false
    private var keyRejections = 0

    private fun candle(i: Int) = Candle(i * bar, 1.0, 1.1, 0.9, 1.0 + i * 0.01, 100.0)

    /** Что отдал бы источник в момент [at]: все бары по текущий (незакрытый) включительно. */
    private fun barsUpTo(at: Long): List<Candle> = (0..(at / bar).toInt()).map { candle(it) }

    private fun store() = CandleStore(
        fetch = { _, limit -> asked += limit; answer(limit) },
        limiter = RequestLimiter(intervalMs = { 0L }, nowMs = { now }, sleep = { now += it }),
        liveAllowed = { liveAllowed },
        onUnauthorized = { keyRejections++ },
        nowMs = { now },
    )

    private fun List<Candle>.lastBar(): Int = (last().openTimeMs / bar).toInt()

    @Test
    fun strategiesGetClosedBarsOnly() = runBlocking {
        val candles = store().get(key, limit = 5)
        assertEquals(5, candles.size)
        assertEquals(cur - 1, candles.lastBar(), "текущий бар ещё не закрыт — в ответе его нет")
    }

    @Test
    fun oneRequestServesUntilTheNextBarCloses() = runBlocking {
        val s = store()
        s.get(key, 50)
        now += 120_000L
        s.get(key, 50)
        now = (cur + 1) * bar + 10_000L // бар закрылся 10 с назад — источник его ещё дописывает
        assertEquals(cur - 1, s.get(key, 50).lastBar())
        assertEquals(1, asked.size, "внутри бара и в первые секунды следующего запросов нет")

        now = (cur + 1) * bar + 25_000L
        assertEquals(cur, s.get(key, 50).lastBar(), "после паузы на запись бара — свежий ответ")
        assertEquals(2, asked.size)
    }

    @Test
    fun concurrentReadersOfOnePoolSpendOneRequest() = runBlocking {
        answer = { CandleFetch.Ok(barsUpTo(now)) }
        val s = CandleStore(
            fetch = { _, limit -> asked += limit; yield(); answer(limit) },
            limiter = RequestLimiter(intervalMs = { 0L }, nowMs = { now }, sleep = { now += it }),
            nowMs = { now },
        )
        val results = listOf(async { s.get(key, 50) }, async { s.get(key, 50) }).awaitAll()
        assertEquals(1, asked.size)
        assertEquals(results[0], results[1])
    }

    @Test
    fun differentLimitsShareOneResponse() = runBlocking {
        val s = store()
        s.get(key, 150) // монитор разворота
        assertEquals(100, s.get(key, 100).size) // старший ТФ стратегии
        assertEquals(listOf(200), asked, "запрошено с запасом, второй читатель обслужен из той же записи")
        s.get(key, 300)
        assertEquals(listOf(200, 300), asked, "больше, чем есть, — новый запрос")
    }

    @Test
    fun chartGetsFormingBarOnlyWhenTheLimitAllows() = runBlocking {
        liveAllowed = true
        val s = store()
        assertEquals(cur, s.get(key, 50, live = true).lastBar(), "с ключом график видит незакрытый бар")
        assertEquals(cur - 1, s.get(key, 50).lastBar(), "стратегии из той же записи — только закрытые")
        now += 31_000L
        s.get(key, 50, live = true)
        assertEquals(2, asked.size, "график обновляется раз в 30 с")

        liveAllowed = false
        now += 31_000L
        assertEquals(cur - 1, s.get(key, 50, live = true).lastBar(), "без ключа график получает то же, что стратегии")
        assertEquals(2, asked.size)
    }

    @Test
    fun staleCdnCopyDoesNotPassAnUnfinishedBarAsClosed() = runBlocking {
        val s = store()
        now = (cur + 1) * bar + 25_000L
        // CDN отдал копию 40-секундной давности: её собрали за 15 с до закрытия бара.
        answer = { CandleFetch.Ok(barsUpTo(now - 40_000L), ageMs = 40_000L) }
        assertEquals(cur - 1, s.get(key, 50).lastBar(), "закрывшийся бар в копии недописан — закрытым его не считаем")

        // Копия в CDN живёт минуту: как только протухнет — перечитываем, не дожидаясь следующего бара.
        answer = { CandleFetch.Ok(barsUpTo(now)) }
        now = (cur + 1) * bar + 40_000L
        s.get(key, 50)
        assertEquals(1, asked.size, "раньше срока копии запрос вернул бы её же")
        now = (cur + 1) * bar + 46_000L
        assertEquals(cur, s.get(key, 50).lastBar())
        assertEquals(2, asked.size)
    }

    @Test
    fun rateLimitServesPreviousBarsAndPausesRequests() = runBlocking {
        val s = store()
        s.get(key, 50)
        now = (cur + 1) * bar + 25_000L
        answer = { CandleFetch.RateLimited() }
        assertEquals(cur - 1, s.get(key, 50).lastBar(), "отказ по лимиту — прежние закрытые бары, а не пусто")
        assertEquals(1, s.status.value.rateLimitHits)

        now += 5_000L
        s.get(key, 50)
        assertEquals(2, asked.size, "пока идёт пауза после отказа, новых запросов нет")

        now += 61_000L
        answer = { CandleFetch.Ok(barsUpTo(now)) }
        assertEquals(cur, s.get(key, 50).lastBar(), "после паузы данные снова свежие")
        assertTrue(s.status.value.lastOkAtMs > 0)
    }

    @Test
    fun tooOldBarsAreNotServed() = runBlocking {
        val s = store()
        s.get(key, 50)
        answer = { CandleFetch.Failed("нет сети") }
        now = (cur + 2) * bar + 25_000L
        assertEquals(cur - 1, s.get(key, 50).lastBar(), "два бара без обновления — ещё терпимо")
        now = (cur + 4) * bar + 25_000L
        assertTrue(s.get(key, 50).isEmpty(), "по картине старше трёх баров входить нельзя — честнее пусто")
    }

    @Test
    fun emptyPoolIsRecheckedLaterNotEveryCycle() = runBlocking {
        val hourly = CandleKey("solana", "NEW_POOL", "hour", 1)
        answer = { CandleFetch.Ok(emptyList()) }
        val s = store()
        assertTrue(s.get(hourly, 50).isEmpty())
        now += 60_000L
        s.get(hourly, 50)
        assertEquals(1, asked.size, "пул без свечей не перезапрашиваем каждый цикл")
        now += 3 * 60_000L
        s.get(hourly, 50)
        assertEquals(2, asked.size, "…но и не ждём закрытия часового бара: пул могли проиндексировать")
    }

    @Test
    fun rejectedKeyIsReported() = runBlocking {
        answer = { CandleFetch.Unauthorized }
        assertTrue(store().get(key, 50).isEmpty())
        assertEquals(1, keyRejections)
    }

    @Test
    fun validUntilFollowsBarClosesAndCdnCopyAge() {
        val minute = 60_000L
        val open = 100 * minute
        // Свежий ответ в середине бара — верен до закрытия следующего плюс пауза на запись.
        assertEquals(open + minute + 20_000L, CandleStore.validUntil(open + 30_000L, open + 30_000L, minute))
        // Запрос в первые секунды бара: копия ляжет в CDN на минуту, раньше её не обновить; а через минуту
        // начнётся новый бар — ждём ещё и паузу на запись.
        assertEquals(open + minute + 20_000L, CandleStore.validUntil(open + 3_000L, open + 3_000L, minute))
        // Копия из CDN собрана до закрытия бара: перечитываем, когда она протухнет.
        val five = 5 * minute
        val open5 = 20 * five
        assertEquals(open5 - 5_000L + 60_000L, CandleStore.validUntil(open5 + 25_000L, open5 - 5_000L, five))
        // Пусто — перепроверка не позже чем через три минуты, даже на часовом баре.
        val hour = 60 * minute
        assertEquals(10 * hour + 5 * minute + 3 * minute, CandleStore.validUntil(10 * hour + 5 * minute, 10 * hour + 5 * minute, hour, empty = true))
    }
}
