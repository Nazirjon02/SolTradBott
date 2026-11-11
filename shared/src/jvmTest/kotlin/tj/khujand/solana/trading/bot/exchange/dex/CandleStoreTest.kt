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

    /** Ключ в работе и дневной лимит не потрачен — запросы человека идут с ключом. */
    private var keyAvailable = false

    /** Сколько запросов ушло с ключом. */
    private var keyedAsked = 0
    private var keyRejections = 0

    private fun candle(i: Int) = Candle(i * bar, 1.0, 1.1, 0.9, 1.0 + i * 0.01, 100.0)

    /** Что отдал бы источник в момент [at]: все бары по текущий (незакрытый) включительно. */
    private fun barsUpTo(at: Long): List<Candle> = (0..(at / bar).toInt()).map { candle(it) }

    private fun testLimiter() = RequestLimiter(intervalMs = { 0L }, nowMs = { now }, sleep = { now += it })

    /** Путь как у GeckoTerminalApi: торговля — без ключа, человек — с ключом, если он доступен. */
    private fun routes(): (RequestLane) -> RequestRoute {
        val free = RequestRoute(testLimiter(), keyed = false)
        val keyed = RequestRoute(testLimiter(), keyed = true)
        return { lane -> if (lane == RequestLane.USER && keyAvailable) keyed else free }
    }

    private fun store() = CandleStore(
        fetch = { _, limit, withKey -> asked += limit; if (withKey) keyedAsked++; answer(limit) },
        route = routes(),
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
            fetch = { _, limit, _ -> asked += limit; yield(); answer(limit) },
            route = routes(),
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
        keyAvailable = true
        val s = store()
        val chart = RequestLane.USER
        assertEquals(cur, s.get(key, 50, live = true, lane = chart).lastBar(), "с ключом график видит незакрытый бар")
        assertEquals(cur - 1, s.get(key, 50).lastBar(), "стратегии из той же записи — только закрытые")
        now += 31_000L
        s.get(key, 50, live = true, lane = chart)
        assertEquals(2, asked.size, "график обновляется раз в 30 с")

        keyAvailable = false // ключ кончился или дневной лимит потрачен
        now += 31_000L
        assertEquals(cur - 1, s.get(key, 50, live = true, lane = chart).lastBar(), "без ключа график получает то же, что стратегии")
        assertEquals(2, asked.size)
    }

    @Test
    fun tradingNeverSpendsTheKey() = runBlocking {
        keyAvailable = true
        val s = store()
        s.get(key, 50) // стратегия
        assertEquals(0, keyedAsked, "торговля идёт без ключа, даже когда ключ есть")

        val other = CandleKey("solana", "POOL2", "minute", 5)
        s.get(other, 50, lane = RequestLane.USER) // «Анализ» или бэктест
        assertEquals(1, keyedAsked, "запрос человека — с ключом")

        // Свечи, загруженные с ключом, достаются и стратегиям — без нового запроса.
        s.get(other, 50)
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

    // ─── Закрытие бара и старший ТФ из младшего ─────────────────────────────

    @Test
    fun barThatClosedSecondsAgoWaitsUntilTheSourceFinishesIt() = runBlocking {
        val s = store()
        now = (cur + 1) * bar + 5_000L // бар закрылся 5 с назад — источник его ещё дописывает
        assertEquals(cur - 1, s.get(key, 50).lastBar(), "только что закрытый бар в ответе может быть недописан")
        now = (cur + 1) * bar + 66_000L // копия в CDN протухла — перечитываем
        assertEquals(cur, s.get(key, 50).lastBar())
        assertEquals(2, asked.size)
    }

    private val key15 = CandleKey("solana", "POOL", "minute", 15)

    /** Что запрошено у источника: агрегат ТФ (5 или 15) и лимит. */
    private val fetchedTf = ArrayList<Pair<Int, Int>>()

    /** С какого 5m-бара начинается история пула. */
    private var firstBar = 0

    /** 5m-бар с разными экстремумами и объёмом — чтобы свёртка в 15m проверялась по-настоящему. */
    private fun rich(i: Int) = Candle(
        i * bar, 1.0 + i * 0.01, 1.05 + i * 0.01 + (i % 3) * 0.01, 0.95 + i * 0.01 - (i % 2) * 0.01,
        1.0 + (i + 1) * 0.01, 100.0 + i,
    )

    /** Независимая свёртка 5m в 15m (как у источника) — эталон для проверки. */
    private fun fold15(fives: List<Candle>): List<Candle> = fives.groupBy { it.openTimeMs / (3 * bar) }.map { (j, g) ->
        Candle(j * 3 * bar, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close, g.sumOf { it.volume })
    }

    /** Закрытые (с запасом на запись) 15m-бары источника на текущий момент. */
    private fun closed15(): List<Candle> =
        fold15((firstBar..(now / bar).toInt()).map { rich(it) }).filter { it.openTimeMs + 3 * bar + CandleStore.SETTLE_MS <= now }

    private fun tfStore() = CandleStore(
        fetch = { k, limit, _ ->
            fetchedTf += k.aggregate to limit
            val fives = (firstBar..(now / bar).toInt()).map { rich(it) }
            CandleFetch.Ok((if (k.aggregate == 5) fives else fold15(fives)).takeLast(limit))
        },
        route = routes(),
        nowMs = { now },
    )

    @Test
    fun fifteenMinuteBarsAreFoldedFromFiveMinuteOnesWithoutARequest() = runBlocking {
        // Пул моложе запрошенной глубины (151 свеча 5m): в ответе вся его история.
        firstBar = cur - 150
        val s = tfStore()
        s.get(key, 200)
        val derived = s.get(key15, 200)
        assertEquals(listOf(5 to 200), fetchedTf, "15m собраны из уже загруженных 5m — запроса за ними нет")
        assertEquals(closed15().takeLast(200), derived)
        assertEquals(132, (derived.last().openTimeMs / (3 * bar)).toInt(), "незакрытый 15m-бар (последний 5m ещё идёт) не отдаётся")
    }

    @Test
    fun shallowFiveMinuteHistoryIsDeepenedOnceInsteadOfFetchingFifteen() = runBlocking {
        now = 1000 * bar + 60_000L // история длиннее 200 свечей 5m
        val s = tfStore()
        s.get(key, 200)
        val derived = s.get(key15, 200)
        assertEquals(listOf(5 to 200, 5 to 606), fetchedTf, "5m перезапрошены глубже (тот же один запрос), 15m — нет")
        assertEquals(closed15().takeLast(200), derived, "первый, возможно неполный, 15m-бар отброшен — остальные точные")

        // Дальше и 5m, и 15m обслуживаются из глубокой записи.
        now += 60_000L
        s.get(key, 200)
        s.get(key15, 200)
        assertEquals(2, fetchedTf.size)
    }

    @Test
    fun staleFiveMinuteRecordIsRefreshedAndServesFifteen() = runBlocking {
        firstBar = cur - 150
        val s = tfStore()
        s.get(key, 200)
        s.get(key15, 200)
        now = (cur + 3) * bar + 25_000L // три бара 5m спустя — запись 5m устарела, 15m-бар закрылся
        val derived = s.get(key15, 200)
        assertEquals(2, fetchedTf.size, "одно обновление 5m на новый 15m-бар")
        assertTrue(fetchedTf.all { it.first == 5 }, "за 15m отдельно не ходили: $fetchedTf")
        assertEquals(closed15().takeLast(200), derived)
        // 5m-бары 399–401 закрылись — 15m-бар №133, которого при первом запросе ещё не было, уже в ответе.
        assertEquals(133, (derived.last().openTimeMs / (3 * bar)).toInt())
    }

    @Test
    fun withoutFiveMinuteDataFifteenIsFetchedDirectly() = runBlocking {
        val s = tfStore()
        s.get(key15, 200)
        assertEquals(listOf(15 to 200), fetchedTf)
    }

    @Test
    fun foldSkipsMissingBarsAndUnclosedBucket() {
        val five = 5 * 60_000L
        val fifteen = 3 * five
        val bars = listOf(
            Candle(0, 1.0, 1.2, 0.9, 1.1, 10.0),
            Candle(2 * five, 1.1, 1.5, 1.0, 1.4, 5.0), // бара 5–10 мин нет — сделок не было
            Candle(3 * five, 1.4, 1.6, 1.3, 1.5, 7.0),
        )
        val folded = CandleStore.aggregate(bars, fifteen, asOfMs = fifteen + CandleStore.SETTLE_MS)
        assertEquals(listOf(Candle(0, 1.0, 1.5, 0.9, 1.4, 15.0)), folded, "второй 15m-бар ещё не закрыт")
    }
}
