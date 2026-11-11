package tj.khujand.solana.trading.bot.exchange.dex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Пул и таймфрейм свечей в терминах GeckoTerminal: "minute" | "hour" | "day" плюс агрегат (minute/5 = 5m). */
data class CandleKey(val network: String, val pool: String, val timeframe: String, val aggregate: Int) {
    /** Длительность одного бара. */
    val barMs: Long
        get() = when (timeframe) {
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            else -> 60_000L
        } * aggregate.coerceAtLeast(1)
}

/** Ответ источника свечей. */
sealed interface CandleFetch {
    /**
     * @param candles свечи по возрастанию времени; последняя может быть ещё не закрыта.
     * @param ageMs сколько ответ пролежал в кеше CDN до нас (заголовок Age); 0 — собран только что.
     */
    data class Ok(val candles: List<Candle>, val ageMs: Long = 0L) : CandleFetch

    /** Сервер отказал по лимиту (HTTP 429). [retryAfterMs] — сколько он просит подождать, null — не сказал. */
    data class RateLimited(val retryAfterMs: Long? = null) : CandleFetch

    /** Ключ API отклонён (HTTP 401/403). */
    data object Unauthorized : CandleFetch

    /** Сеть, 5xx и прочее — о свечах это ничего не говорит. */
    data class Failed(val reason: String) : CandleFetch
}

/**
 * Состояние источника свечей — для ленты активности, настроек и `/health`.
 * @param usingKey запросы идут с ключом API (лимит выше).
 * @param keyRejected сервер отклонил ключ — работаем без него.
 * @param keyExhaustedUntilMs ключ несколько раз подряд упёрся в лимит — похоже, кончилась месячная квота: до этого
 *   момента запросы идут без ключа; 0 — не упирался.
 * @param rateLimitHits сколько раз сервер отказал по лимиту с начала работы.
 */
data class CandleFeedStatus(
    val usingKey: Boolean = false,
    val keyRejected: Boolean = false,
    val keyExhaustedUntilMs: Long = 0L,
    val rateLimitHits: Int = 0,
    val lastRateLimitAtMs: Long = 0L,
    val lastOkAtMs: Long = 0L,
)

/**
 * Свечи пулов с обновлением раз в бар.
 *
 * Бесплатный GeckoTerminal пускает считаные запросы в минуту, а стратегии спрашивают свечи каждого
 * кандидата каждый цикл (~30 с). Поэтому:
 *  - стратегии получают только ЗАКРЫТЫЕ бары, и один ответ служит до закрытия следующего бара: внутри бара
 *    в закрытых свечах ничего не меняется. На 5m это один запрос вместо восьми-десяти;
 *  - старший ТФ собирается из уже загруженного младшего того же пула (15m — из трёх 5m), а не запрашивается
 *    отдельно: OHLCV складывается точно, а запрос на 15m у пула, который и так обновляется раз в 5 минут, —
 *    лишний. Младший устарел или неглубок — обновляется он (тот же один запрос, но с нужной глубиной);
 *  - запросы идут через [RequestLimiter] — по очереди и не чаще лимита; отказ 429 ставит на паузу всех,
 *    а не превращается в «свечей нет»;
 *  - пока свежих данных нет (лимит, сбой сети), отдаются прежние закрытые бары, но не старше
 *    [MAX_STALE_BARS] баров: по более старой картине входить нельзя.
 *
 * Закрытость бара считается на момент, когда ответ был собран сервером, а не получен нами, и с запасом
 * [SETTLE_MS]: CDN держит копию ответа до минуты, а источник дописывает только что закрывшийся бар ещё
 * несколько секунд.
 *
 * Режим [get] `live` (график сделки): свежесть [LIVE_TTL_MS] и незакрытый бар в конце — только когда лимит
 * это позволяет ([liveAllowed], то есть с ключом API); иначе тот же ответ, что у стратегий.
 *
 * Сеть — за [fetch], часы — параметром: логика покрыта тестами без сети и ожидания.
 */
class CandleStore(
    private val fetch: suspend (key: CandleKey, limit: Int) -> CandleFetch,
    private val limiter: RequestLimiter,
    private val liveAllowed: () -> Boolean = { false },
    private val onUnauthorized: () -> Unit = {},
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * @param candles как пришли: по возрастанию времени, последняя может быть незакрытой.
     * @param limit сколько свечей запрашивали (запрос на меньшее число обслуживается из этой же записи).
     * @param asOfMs момент, на который ответ верен: получение минус возраст копии в CDN.
     * @param validUntilMs до этого времени закрытые бары перезапрашивать незачем.
     */
    private class Entry(
        val candles: List<Candle>,
        val limit: Int,
        val fetchedAtMs: Long,
        val asOfMs: Long,
        val validUntilMs: Long,
    )

    private val guard = Mutex()
    private val entries = mutableMapOf<CandleKey, Entry>()

    // По замку на пул+ТФ: два одновременных запроса одних и тех же свечей не должны тратить два слота лимита.
    private val locks = mutableMapOf<CandleKey, Mutex>()

    private val _status = MutableStateFlow(CandleFeedStatus())
    val status: StateFlow<CandleFeedStatus> = _status.asStateFlow()

    /** Ключ API появился, сменился, отклонён сервером или отложен до сброса квоты. */
    fun setKeyState(usingKey: Boolean, keyRejected: Boolean, keyExhaustedUntilMs: Long = 0L) {
        _status.update { it.copy(usingKey = usingKey, keyRejected = keyRejected, keyExhaustedUntilMs = keyExhaustedUntilMs) }
    }

    /**
     * Последние [limit] свечей. Пусто — данных нет (пул неизвестен, лимит исчерпан, а прежние бары устарели).
     * @param live график: с незакрытым баром и свежестью [LIVE_TTL_MS], если лимит это позволяет.
     * @param maxWaitMs сколько можно ждать очереди к API; не дождались — прежние бары или пусто.
     */
    suspend fun get(
        key: CandleKey,
        limit: Int,
        live: Boolean = false,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    ): List<Candle> {
        if (key.pool.isBlank() || limit <= 0) return emptyList()
        val want = limit.coerceAtMost(MAX_FETCH_LIMIT)
        val liveMode = live && liveAllowed()
        cached(key, want, liveMode)?.let { return it }
        if (!liveMode) {
            derived(key, want, allowStale = false)?.let { return it }
            // Младший ТФ этого пула уже кто-то смотрит, но запись устарела или неглубока: обновляем её — это тот
            // же один запрос, что и за старшим ТФ, но младший нужен и своим стратегиям, — и собираем старший из неё.
            baseFor(key, want)?.let { (base, baseLimit) ->
                get(base, baseLimit, live = false, maxWaitMs = maxWaitMs)
                derived(key, want, allowStale = true)?.let { return it }
            }
        }
        return lockFor(key).withLock {
            // Пока ждали замок, эти же свечи мог обновить соседний запрос.
            cached(key, want, liveMode)
                ?: (if (liveMode) null else derived(key, want, allowStale = false))
                ?: refresh(key, want, liveMode, maxWaitMs)
        }
    }

    private suspend fun cached(key: CandleKey, limit: Int, liveMode: Boolean): List<Candle>? {
        val e = guard.withLock { entries[key] } ?: return null
        if (e.limit < limit) return null
        val now = nowMs()
        val fresh = if (liveMode) now - e.fetchedAtMs < LIVE_TTL_MS else now < e.validUntilMs
        return if (fresh) view(e, key.barMs, liveMode).takeLast(limit) else null
    }

    /** Записи младших ТФ того же пула, из которых собирается [target] (15m — из 5m и 1m…), крупнейший ТФ первым. */
    private suspend fun baseEntries(target: CandleKey): List<Pair<CandleKey, Entry>> = guard.withLock {
        entries.filter { (k, _) ->
            k != target && k.network == target.network && k.pool == target.pool &&
                k.barMs < target.barMs && target.barMs % k.barMs == 0L
        }.toList()
    }.sortedByDescending { it.first.barMs }

    /** Сколько младших баров нужно на [want] старших: +2 — первый может начаться до самой старой свечи, последний не закрыт. */
    private fun neededBaseBars(target: CandleKey, base: CandleKey, want: Int): Long = (want + 2L) * (target.barMs / base.barMs)

    /**
     * Бары [target] из записи младшего ТФ того же пула, без запроса к API. Запись годится, пока не закрылся её
     * следующий бар (иначе старший мог закрыться, а младших в ней нет; с [allowStale] — как прежние бары у
     * [stale]), и когда в ней хватает истории: либо это вся история пула, либо глубины хватает на [want] баров.
     */
    private suspend fun derived(target: CandleKey, want: Int, allowStale: Boolean): List<Candle>? {
        val now = nowMs()
        for ((base, e) in baseEntries(target)) {
            val fresh = now < e.validUntilMs ||
                (allowStale && now - e.asOfMs <= MAX_STALE_BARS * target.barMs + SETTLE_MS)
            val wholeHistory = e.candles.size < e.limit
            if (!fresh || (!wholeHistory && e.limit < neededBaseBars(target, base, want))) continue
            val bars = aggregate(view(e, base.barMs, liveMode = false), target.barMs, e.asOfMs)
            // История обрезана лимитом запроса — первый старший бар может быть собран не из всех младших.
            return (if (wholeHistory) bars else bars.drop(1)).takeLast(want)
        }
        return null
    }

    /** Младший ТФ, обновив который (с нужной глубиной), можно собрать [target]; null — такого нет. */
    private suspend fun baseFor(target: CandleKey, want: Int): Pair<CandleKey, Int>? {
        for ((base, e) in baseEntries(target)) {
            val needed = neededBaseBars(target, base, want)
            if (needed <= MAX_FETCH_LIMIT || e.candles.size < e.limit) {
                return base to needed.coerceAtMost(MAX_FETCH_LIMIT.toLong()).toInt()
            }
        }
        return null
    }

    private suspend fun refresh(key: CandleKey, limit: Int, liveMode: Boolean, maxWaitMs: Long): List<Candle> {
        val old = guard.withLock { entries[key] }
        if (!limiter.acquire(maxWaitMs)) return stale(old, key.barMs, limit)
        // Берём с запасом: стратегии, монитор и график просят разное число свечей одного пула, а старший ТФ
        // собирается из этой же записи — однажды углублённая, она такой и остаётся.
        val fetchLimit = maxOf(limit, old?.limit ?: 0, MIN_FETCH_LIMIT).coerceAtMost(MAX_FETCH_LIMIT)
        return when (val r = fetch(key, fetchLimit)) {
            is CandleFetch.Ok -> {
                val now = nowMs()
                val asOf = now - r.ageMs.coerceAtLeast(0L)
                val e = Entry(r.candles, fetchLimit, now, asOf, validUntil(now, asOf, key.barMs, r.candles.isEmpty()))
                guard.withLock {
                    entries[key] = e
                    prune(now)
                }
                _status.update { it.copy(lastOkAtMs = now) }
                view(e, key.barMs, liveMode).takeLast(limit)
            }
            is CandleFetch.RateLimited -> {
                val now = nowMs()
                limiter.pauseUntil(now + (r.retryAfterMs ?: RATE_LIMIT_PAUSE_MS).coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS))
                _status.update { it.copy(rateLimitHits = it.rateLimitHits + 1, lastRateLimitAtMs = now) }
                stale(old, key.barMs, limit)
            }
            CandleFetch.Unauthorized -> {
                onUnauthorized()
                stale(old, key.barMs, limit)
            }
            is CandleFetch.Failed -> stale(old, key.barMs, limit)
        }
    }

    /**
     * Свечи записи: для стратегий — только бары, закрытые на момент сборки ответа (с запасом [SETTLE_MS] на
     * дописывание только что закрывшегося); для графика — как пришли.
     */
    private fun view(e: Entry, barMs: Long, liveMode: Boolean): List<Candle> =
        if (liveMode) e.candles else e.candles.dropLastWhile { it.openTimeMs + barMs + SETTLE_MS > e.asOfMs }

    /** Прежние закрытые бары, пока свежих нет; слишком старые не отдаём — пусто честнее. */
    private fun stale(e: Entry?, barMs: Long, limit: Int): List<Candle> {
        if (e == null || nowMs() - e.asOfMs > MAX_STALE_BARS * barMs + SETTLE_MS) return emptyList()
        return view(e, barMs, liveMode = false).takeLast(limit)
    }

    private suspend fun lockFor(key: CandleKey): Mutex = guard.withLock { locks.getOrPut(key) { Mutex() } }

    /** Пулы, за которыми давно никто не следит, не должны копиться месяцами. Вызывается под [guard]. */
    private fun prune(now: Long) {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries.removeAll { now - it.value.fetchedAtMs > PRUNE_AGE_MS }
        locks.entries.removeAll { it.key !in entries && !it.value.isLocked }
    }

    companion object {
        /** Сколько стратегия ждёт очереди к API, прежде чем обойтись прежними барами. */
        const val DEFAULT_MAX_WAIT_MS = 20_000L

        /** Свежесть свечей графика (режим live). */
        const val LIVE_TTL_MS = 30_000L

        /** Пауза после отказа по лимиту, если сервер не назвал свою. */
        const val RATE_LIMIT_PAUSE_MS = 60_000L

        /** Прежние бары отдаются, пока им не больше стольких баров. */
        const val MAX_STALE_BARS = 3

        /** Бар попадает в историю источника не мгновенно — столько ждём после его закрытия. */
        internal const val SETTLE_MS = 20_000L

        /** Столько CDN GeckoTerminal держит копию ответа (s-maxage=60): раньше перезапрашивать бесполезно. */
        internal const val CDN_TTL_MS = 60_000L

        /** Пул без свечей (ещё не проиндексирован) перепроверяем не реже. */
        internal const val EMPTY_RETRY_MS = 3 * 60_000L

        private const val MIN_REFRESH_MS = 5_000L
        private const val MIN_PAUSE_MS = 15_000L
        private const val MAX_PAUSE_MS = 5 * 60_000L
        private const val MIN_FETCH_LIMIT = 200
        private const val MAX_FETCH_LIMIT = 1000
        private const val MAX_ENTRIES = 400
        private const val PRUNE_AGE_MS = 2 * 60 * 60_000L

        /**
         * Когда закрытые бары стоит запросить снова (чистая функция).
         *
         * Ответ собран после закрытия последнего бара (с запасом [SETTLE_MS]) — он верен до закрытия
         * следующего. Собран раньше (копия из CDN старше границы бара или запрос в первые секунды бара) —
         * перечитываем, как только копия в CDN протухнет. В обоих случаях не раньше [SETTLE_MS] от начала
         * бара: его предшественник в этот момент ещё дописывается.
         *
         * @param fetchedAtMs когда получили ответ, [asOfMs] — на какой момент он верен.
         * @param empty свечей нет вовсе — пул перепроверяем не реже [EMPTY_RETRY_MS].
         */
        internal fun validUntil(fetchedAtMs: Long, asOfMs: Long, barMs: Long, empty: Boolean = false): Long {
            val barOpen = fetchedAtMs / barMs * barMs
            val settled = asOfMs >= barOpen + SETTLE_MS
            var t = if (settled) barOpen + barMs + SETTLE_MS else asOfMs + CDN_TTL_MS
            val open = t / barMs * barMs
            if (t - open < SETTLE_MS) t = open + SETTLE_MS
            if (empty) t = minOf(t, fetchedAtMs + EMPTY_RETRY_MS)
            return maxOf(t, fetchedAtMs + MIN_REFRESH_MS)
        }

        /**
         * Бары ТФ [barMs] из баров младшего ТФ (по возрастанию времени): open — первого, high/low — экстремумы,
         * close — последнего, объём — сумма. Корзины выровнены по эпохе, как бары GeckoTerminal (по UTC); бар без
         * сделок источник не отдаёт — корзина собирается из тех, что есть. Корзина, не закрытая к [asOfMs] (с
         * запасом [SETTLE_MS]), отбрасывается: в ней ещё не все младшие бары.
         */
        internal fun aggregate(bars: List<Candle>, barMs: Long, asOfMs: Long): List<Candle> {
            val out = ArrayList<Candle>(bars.size / 2 + 1)
            var start = Long.MIN_VALUE
            var open = 0.0
            var high = 0.0
            var low = 0.0
            var close = 0.0
            var volume = 0.0
            fun flush() {
                if (start != Long.MIN_VALUE) out += Candle(start, open, high, low, close, volume)
            }
            for (b in bars) {
                val bucket = b.openTimeMs - b.openTimeMs.mod(barMs)
                if (bucket != start) {
                    flush()
                    start = bucket
                    open = b.open
                    high = b.high
                    low = b.low
                    close = b.close
                    volume = b.volume
                } else {
                    high = maxOf(high, b.high)
                    low = minOf(low, b.low)
                    close = b.close
                    volume += b.volume
                }
            }
            flush()
            return out.filter { it.openTimeMs + barMs + SETTLE_MS <= asOfMs }
        }
    }
}
