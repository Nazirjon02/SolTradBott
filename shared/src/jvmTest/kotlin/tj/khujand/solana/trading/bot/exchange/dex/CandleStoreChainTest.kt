package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Свечи из блокчейна в [CandleStore]: торговле прежний ответ GeckoTerminal продлевается закрытыми барами из цепочки
 * вместо нового запроса — только когда источник видел все сделки с конца ответа; человеку и при нехватке глубины —
 * GeckoTerminal, как раньше. Сеть, часы и источник — подставные.
 */
class CandleStoreChainTest {

    private val bar = 300_000L // 5m
    private val key = CandleKey("solana", "POOL", "minute", 5)

    /** Номер текущего (ещё не закрытого) бара. */
    private val cur = 400

    /** С открытия текущего бара прошла минута. */
    private var now = cur * bar + 60_000L

    /** Лимиты запросов к GeckoTerminal. */
    private val asked = ArrayList<Int>()

    /** Что спросили у блокчейна: с какого бара, close перед ним, к какому моменту бары должны закрыться. */
    private data class ChainAsk(val fromMs: Long, val prevClose: Double, val closedByMs: Long)

    private val chainAsked = ArrayList<ChainAsk>()

    /** Бар из цепочки — с другими ценами, чем у GeckoTerminal, чтобы различать источники. */
    private fun chainBar(openMs: Long) = Candle(openMs, 2.0, 2.2, 1.9, 2.1, 7.0)

    /** Ответ блокчейна: по умолчанию — все закрытые бары отрезка; null — видел не все сделки. */
    private var chainAnswer: (fromMs: Long, closedByMs: Long) -> List<Candle>? = { from, closedBy ->
        generateSequence(from) { it + bar }.takeWhile { it + bar <= closedBy }.map(::chainBar).toList()
    }

    private fun candle(i: Int) = Candle(i * bar, 1.0, 1.1, 0.9, 1.0 + i * 0.01, 100.0)

    /** Что отдал бы GeckoTerminal в момент [at]: все бары по текущий (незакрытый) включительно. */
    private fun barsUpTo(at: Long): List<Candle> = (0..(at / bar).toInt()).map { candle(it) }

    private fun limiter() = RequestLimiter(intervalMs = { 0L }, nowMs = { now }, sleep = { now += it })

    private fun store(): CandleStore {
        val free = RequestRoute(limiter(), keyed = false)
        val keyed = RequestRoute(limiter(), keyed = true)
        val source = ClosedBarSource { pool, barMs, fromMs, prevClose, closedByMs ->
            assertEquals("POOL", pool)
            assertEquals(bar, barMs, "старший ТФ собирается из 5m, у блокчейна просят 5m")
            chainAsked += ChainAsk(fromMs, prevClose, closedByMs)
            chainAnswer(fromMs, closedByMs)
        }
        return CandleStore(
            fetch = { _, limit, _ -> asked += limit; CandleFetch.Ok(barsUpTo(now)) },
            route = { lane -> if (lane == RequestLane.USER) keyed else free },
            nowMs = { now },
            chainBars = { source },
        )
    }

    private fun List<Candle>.lastBar(): Int = (last().openTimeMs / bar).toInt()

    @Test
    fun nextClosedBarComesFromChainWithoutRequest() = runBlocking {
        val s = store()
        assertEquals(cur - 1, s.get(key, 50).lastBar())
        now = (cur + 1) * bar + 25_000L // бар cur закрылся и устоялся
        val candles = s.get(key, 50)
        assertEquals(listOf(200), asked, "нового запроса к GeckoTerminal нет")
        assertEquals(chainBar(cur * bar), candles.last())
        assertEquals(candle(cur - 1), candles[candles.size - 2], "история — от GeckoTerminal")
        assertEquals(50, candles.size)
        val ask = chainAsked.single()
        assertEquals(cur * bar, ask.fromMs, "с бара, который в ответе GeckoTerminal был ещё незакрытым")
        assertEquals(candle(cur - 1).close, ask.prevClose, "open первого бара — close последнего бара GeckoTerminal")
        assertEquals(now - CandleStore.SETTLE_MS, ask.closedByMs, "то же правило закрытого бара, что у GeckoTerminal")
        assertEquals(1, s.status.value.chainServed)
        assertEquals(1, s.status.value.geckoRequests)
    }

    @Test
    fun chainKeepsServingBarAfterBar() = runBlocking {
        val s = store()
        s.get(key, 50)
        now = (cur + 1) * bar + 25_000L
        s.get(key, 50)
        now += 60_000L
        s.get(key, 50)
        assertEquals(1, chainAsked.size, "внутри бара — тот же ответ, ни блокчейна, ни GeckoTerminal")

        now = (cur + 2) * bar + 25_000L
        val candles = s.get(key, 50)
        assertEquals(cur + 1, candles.lastBar())
        assertEquals((cur + 1) * bar, chainAsked[1].fromMs)
        assertEquals(chainBar(cur * bar).close, chainAsked[1].prevClose, "open — close прошлого бара из блокчейна")
        assertEquals(listOf(200), asked)
        assertEquals(2, s.status.value.chainServed)
    }

    @Test
    fun withoutFullCoverageBarsComeFromGeckoAsBefore() = runBlocking {
        chainAnswer = { _, _ -> null } // обрыв, нет подписки, дорогой пул, CLMM
        val s = store()
        s.get(key, 50)
        now = (cur + 1) * bar + 25_000L
        assertEquals(candle(cur), s.get(key, 50).last())
        assertEquals(2, asked.size)
        assertEquals(1, chainAsked.size)
        assertEquals(0, s.status.value.chainServed)
        assertEquals(2, s.status.value.geckoRequests)
    }

    @Test
    fun unclosedOrEarlierBarsFromChainAreNotTrusted() = runBlocking {
        val s = store()
        s.get(key, 50)
        now = (cur + 1) * bar + 25_000L
        chainAnswer = { from, _ -> listOf(chainBar(from), chainBar(from + bar)) } // второй ещё не закрыт
        assertEquals(candle(cur), s.get(key, 50).last())
        now = (cur + 2) * bar + 25_000L
        chainAnswer = { from, _ -> listOf(chainBar(from - bar)) } // бар, который уже есть в истории
        assertEquals(candle(cur + 1), s.get(key, 50).last())
        assertEquals(3, asked.size)
        assertEquals(0, s.status.value.chainServed)
    }

    @Test
    fun humanLaneAndDeeperHistoryStillGoToGecko() = runBlocking {
        val s = store()
        s.get(key, 50)
        now = (cur + 1) * bar + 25_000L
        s.get(key, 50, lane = RequestLane.USER)
        assertTrue(chainAsked.isEmpty(), "бэктест, «Анализ» и график — у GeckoTerminal")
        now = (cur + 2) * bar + 25_000L
        s.get(key, 300)
        assertTrue(chainAsked.isEmpty(), "глубже прежнего ответа — новый запрос к GeckoTerminal")
        assertEquals(listOf(200, 200, 300), asked)
    }

    @Test
    fun reconciliationGetsGeckoBarsNotChainExtendedRecord() = runBlocking {
        val s = store()
        s.get(key, 50)
        now = (cur + 1) * bar + 25_000L
        assertEquals(chainBar(cur * bar), s.get(key, 50).last())
        // Сверке свечей из блокчейна нужен GeckoTerminal: запись, продлённая блокчейном, ей не годится.
        val gecko = s.get(key, 50, allowChain = false)
        assertEquals(candle(cur), gecko.last())
        assertEquals(listOf(200, 200), asked)
        assertEquals(1, chainAsked.size)
        // Свежий ответ GeckoTerminal заменил запись — внутри бара стратегии получают его, без нового запроса.
        assertEquals(candle(cur), s.get(key, 50).last())
        assertEquals(listOf(200, 200), asked)
    }

    @Test
    fun higherTimeframeIsFoldedFromChainExtendedFiveMinutes() = runBlocking {
        val key15 = CandleKey("solana", "POOL", "minute", 15)
        val s = store()
        s.get(key, 200)
        // 15m-бар открывается с 5m-бара 399 (399 % 3 == 0): 399 — от GeckoTerminal, 400 и 401 — из блокчейна.
        now = 402 * bar + 25_000L
        val fifteen = s.get(key15, 50)
        assertEquals(Candle(399 * bar, 1.0, 2.2, 0.9, 2.1, 114.0), fifteen.last())
        assertEquals(listOf(200), asked, "15m — из 5m, продлённых блокчейном, без запросов")
        assertEquals(400 * bar, chainAsked.single().fromMs)
    }

    @Test
    fun chainAnswerIsValidUntilTheNextBarSettles() {
        assertEquals(11 * bar + CandleStore.SETTLE_MS, CandleStore.nextSettledClose(10 * bar + 25_000L, bar))
        assertEquals(10 * bar + CandleStore.SETTLE_MS, CandleStore.nextSettledClose(10 * bar + 10_000L, bar))
    }
}
