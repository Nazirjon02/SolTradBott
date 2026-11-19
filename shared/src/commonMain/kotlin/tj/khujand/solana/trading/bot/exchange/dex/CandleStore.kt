package tj.khujand.solana.trading.bot.exchange.dex

import kotlinx.coroutines.CancellationException
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
 * Источник закрытых баров для торговли помимо GeckoTerminal — свечи из блокчейна
 * ([tj.khujand.solana.trading.bot.exchange.chain.ChainCandleService]).
 */
fun interface ClosedBarSource {
    /**
     * Бары пула размера [barMs] с открытием от [fromMs] по последний, закончившийся не позже [closedByMs]. open первого
     * бара — [prevClose] (close последнего бара перед отрезком, то есть последняя сделка до него — правило
     * GeckoTerminal), дальше — close предыдущего; отрезок без сделок бара не даёт.
     * @return пусто — новых закрытых баров нет или сделок в них не было; null — источник видел не все сделки
     *   отрезка (нет подписки, был обрыв, пул не поддержан): свечи нужно взять у GeckoTerminal.
     */
    suspend fun closedBars(pool: String, barMs: Long, fromMs: Long, prevClose: Double, closedByMs: Long): List<Candle>?
}

/**
 * Состояние источника свечей — для ленты активности, настроек и `/health`.
 * @param usingKey ключ API задан и в работе (бэктест, «Анализ» и график идут с ним; торговля — всегда без ключа).
 * @param keyRejected сервер отклонил ключ — работаем без него.
 * @param keyExhaustedUntilMs ключ несколько раз подряд упёрся в лимит — похоже, кончилась месячная квота: до этого
 *   момента запросы идут без ключа; 0 — не упирался.
 * @param rateLimitHits сколько раз сервер отказал по лимиту с начала работы.
 * @param keyUsedToday запросов с ключом за текущие сутки (UTC) из дневного лимита [keyDailyAllowance];
 *   [keyUsedMonth] — за месяц из [keyMonthlyQuota]; [keyNextDayMs] — когда начнутся новые сутки (см. [KeyBudget]).
 * @param chainServed сколько раз с запуска торговля получила свежие закрытые бары из блокчейна вместо запроса к
 *   GeckoTerminal ([ClosedBarSource]); [geckoRequests] — сколько запросов свечей ушло к GeckoTerminal.
 */
data class CandleFeedStatus(
    val usingKey: Boolean = false,
    val keyRejected: Boolean = false,
    val keyExhaustedUntilMs: Long = 0L,
    val rateLimitHits: Int = 0,
    val lastRateLimitAtMs: Long = 0L,
    val lastOkAtMs: Long = 0L,
    val keyUsedToday: Int = 0,
    val keyDailyAllowance: Int = 0,
    val keyUsedMonth: Int = 0,
    val keyMonthlyQuota: Int = 0,
    val keyNextDayMs: Long = 0L,
    val chainServed: Int = 0,
    val geckoRequests: Int = 0,
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
 *    а не превращается в «свечей нет». Очередь и ключ выбирает [route] по тому, кто просит ([RequestLane]):
 *    торговля — бесплатно без ключа, человек (бэктест, «Анализ», график) — с ключом, пока позволяет его лимит;
 *  - пока свежих данных нет (лимит, сбой сети), отдаются прежние закрытые бары, но не старше
 *    [MAX_STALE_BARS] баров: по более старой картине входить нельзя.
 *
 * Закрытость бара считается на момент, когда ответ был собран сервером, а не получен нами, и с запасом
 * [SETTLE_MS]: CDN держит копию ответа до минуты, а источник дописывает только что закрывшийся бар ещё
 * несколько секунд.
 *
 * Режим [get] `live` (график сделки): свежесть [LIVE_TTL_MS] и незакрытый бар в конце — только когда запрос
 * идёт с ключом; иначе тот же ответ, что у стратегий.
 *
 * Свечи из блокчейна ([chainBars], по умолчанию нет): торговле прежний ответ GeckoTerminal продлевается барами,
 * закрывшимися после него, — без запроса, по тому же правилу закрытого бара; источник отдаёт их, только если видел
 * все сделки с конца ответа (иначе — запрос к GeckoTerminal, как раньше). История и глубина остаются за
 * GeckoTerminal; человеку (бэктест, «Анализ», график) — всегда GeckoTerminal.
 *
 * Сеть — за [fetch] (третий параметр — идёт ли запрос с ключом), выбор пути — за [route], часы — параметром:
 * логика покрыта тестами без сети и ожидания.
 */
class CandleStore(
    private val fetch: suspend (key: CandleKey, limit: Int, keyed: Boolean) -> CandleFetch,
    private val route: (lane: RequestLane) -> RequestRoute,
    private val onUnauthorized: () -> Unit = {},
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val chainBars: () -> ClosedBarSource? = { null },
) {
    /**
     * @param candles как пришли: по возрастанию времени, последняя может быть незакрытой.
     * @param limit сколько свечей запрашивали (запрос на меньшее число обслуживается из этой же записи).
     * @param asOfMs момент, на который ответ верен: получение минус возраст копии в CDN.
     * @param validUntilMs до этого времени закрытые бары перезапрашивать незачем.
     * @param complete в записи вся история пула (источник отдал меньше, чем просили).
     * @param chainFromMs с какого бара в записи бары из блокчейна; null — вся запись от GeckoTerminal.
     */
    private class Entry(
        val candles: List<Candle>,
        val limit: Int,
        val fetchedAtMs: Long,
        val asOfMs: Long,
        val validUntilMs: Long,
        val complete: Boolean = candles.size < limit,
        val chainFromMs: Long? = null,
    )

    /** Запись, если она годится просящему: тому, кому нужен только GeckoTerminal, — без баров из блокчейна. */
    private fun usable(e: Entry?, allowChain: Boolean): Entry? = if (!allowChain && e?.chainFromMs != null) null else e

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

    /** Расход ключа за сутки и месяц — для настроек и ленты. */
    fun setKeyBudget(v: KeyBudgetView) {
        _status.update {
            it.copy(
                keyUsedToday = v.usedToday,
                keyDailyAllowance = v.allowanceToday,
                keyUsedMonth = v.usedMonth,
                keyMonthlyQuota = v.monthlyQuota,
                keyNextDayMs = v.nextDayMs,
            )
        }
    }

    /**
     * Последние [limit] свечей. Пусто — данных нет (пул неизвестен, лимит исчерпан, а прежние бары устарели).
     * @param live график: с незакрытым баром и свежестью [LIVE_TTL_MS], если запрос идёт с ключом.
     * @param maxWaitMs сколько можно ждать очереди к API; не дождались — прежние бары или пусто.
     * @param lane кто просит: торговля идёт без ключа, человек — с ключом, пока его хватает.
     * @param allowChain годятся ли бары из блокчейна; false — только GeckoTerminal (сверка свечей из блокчейна с
     *   ним самим): запись с барами из блокчейна для такого запроса как будто устарела.
     */
    suspend fun get(
        key: CandleKey,
        limit: Int,
        live: Boolean = false,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        lane: RequestLane = RequestLane.TRADING,
        allowChain: Boolean = true,
    ): List<Candle> {
        if (key.pool.isBlank() || limit <= 0) return emptyList()
        val want = limit.coerceAtMost(MAX_FETCH_LIMIT)
        val path = route(lane)
        val liveMode = live && path.keyed
        cached(key, want, liveMode, allowChain)?.let { return it }
        if (!liveMode) {
            derived(key, want, allowStale = false, allowChain)?.let { return it }
            // Младший ТФ этого пула уже кто-то смотрит, но запись устарела или неглубока: обновляем её — это тот
            // же один запрос, что и за старшим ТФ, но младший нужен и своим стратегиям, — и собираем старший из неё.
            baseFor(key, want)?.let { (base, baseLimit) ->
                get(base, baseLimit, live = false, maxWaitMs = maxWaitMs, lane = lane, allowChain = allowChain)
                derived(key, want, allowStale = true, allowChain)?.let { return it }
            }
        }
        return lockFor(key).withLock {
            // Пока ждали замок, эти же свечи мог обновить соседний запрос.
            cached(key, want, liveMode, allowChain)
                ?: (if (liveMode) null else derived(key, want, allowStale = false, allowChain))
                ?: (if (liveMode || lane != RequestLane.TRADING || !allowChain) null else extendFromChain(key, want))
                ?: refresh(key, want, liveMode, maxWaitMs, path, allowChain)
        }
    }

    /**
     * Свежие закрытые бары без запроса к GeckoTerminal: прежний его ответ плюс бары из блокчейна ([chainBars]),
     * закрывшиеся после ответа (с тем же запасом [SETTLE_MS]). Только когда прежний ответ достаточно глубок и
     * источник видел все сделки с его конца; иначе null — бары запрашиваются у GeckoTerminal, как раньше.
     */
    private suspend fun extendFromChain(key: CandleKey, want: Int): List<Candle>? {
        val source = chainBars() ?: return null
        val old = guard.withLock { entries[key] } ?: return null
        if (old.limit < want) return null
        val base = view(old, key.barMs, liveMode = false)
        val prevClose = base.lastOrNull()?.close ?: return null
        val now = nowMs()
        val closedBy = now - SETTLE_MS
        // Первый бар, которого в ответе нет: на момент ответа он ещё не был закрыт с запасом SETTLE_MS.
        val edge = old.asOfMs - SETTLE_MS
        val from = edge - edge.mod(key.barMs)
        val tail = try {
            source.closedBars(key.pool, key.barMs, from, prevClose, closedBy)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // сбой источника не должен стоить торговле свечей — идём к GeckoTerminal
        } ?: return null
        // Источник обязан отдать только бары после ответа и только закрытые — иначе ему не верим.
        if (tail.any { it.openTimeMs < from || it.openTimeMs + key.barMs > closedBy }) return null
        if (tail.zipWithNext().any { (a, b) -> b.openTimeMs <= a.openTimeMs }) return null
        val all = base + tail
        val trimmed = all.size > MAX_FETCH_LIMIT
        val e = Entry(
            candles = if (trimmed) all.takeLast(MAX_FETCH_LIMIT) else all,
            limit = old.limit,
            fetchedAtMs = now,
            asOfMs = now,
            validUntilMs = nextSettledClose(now, key.barMs),
            complete = old.complete && !trimmed,
            chainFromMs = old.chainFromMs ?: from,
        )
        guard.withLock { entries[key] = e }
        _status.update { it.copy(chainServed = it.chainServed + 1) }
        return view(e, key.barMs, liveMode = false).takeLast(want)
    }

    private suspend fun cached(key: CandleKey, limit: Int, liveMode: Boolean, allowChain: Boolean): List<Candle>? {
        val e = usable(guard.withLock { entries[key] }, allowChain) ?: return null
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
    private suspend fun derived(target: CandleKey, want: Int, allowStale: Boolean, allowChain: Boolean): List<Candle>? {
        val now = nowMs()
        for ((base, entry) in baseEntries(target)) {
            val e = usable(entry, allowChain) ?: continue
            val fresh = now < e.validUntilMs ||
                (allowStale && now - e.asOfMs <= MAX_STALE_BARS * target.barMs + SETTLE_MS)
            val wholeHistory = e.complete
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
            if (needed <= MAX_FETCH_LIMIT || e.complete) {
                return base to needed.coerceAtMost(MAX_FETCH_LIMIT.toLong()).toInt()
            }
        }
        return null
    }

    private suspend fun refresh(
        key: CandleKey,
        limit: Int,
        liveMode: Boolean,
        maxWaitMs: Long,
        path: RequestRoute,
        allowChain: Boolean,
    ): List<Candle> {
        val current = guard.withLock { entries[key] }
        // Прежние бары на случай отказа — только годные просящему (кому нужен лишь GeckoTerminal — без блокчейна).
        val old = usable(current, allowChain)
        if (!path.limiter.acquire(maxWaitMs)) return stale(old, key.barMs, limit)
        // Берём с запасом: стратегии, монитор и график просят разное число свечей одного пула, а старший ТФ
        // собирается из этой же записи — однажды углублённая, она такой и остаётся.
        val fetchLimit = maxOf(limit, current?.limit ?: 0, MIN_FETCH_LIMIT).coerceAtMost(MAX_FETCH_LIMIT)
        _status.update { it.copy(geckoRequests = it.geckoRequests + 1) }
        return when (val r = fetch(key, fetchLimit, path.keyed)) {
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
                // Пауза — только у той очереди, которой отказали: у запросов с ключом и без ключа разные лимиты.
                path.limiter.pauseUntil(now + (r.retryAfterMs ?: RATE_LIMIT_PAUSE_MS).coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS))
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
         * Когда закроется (с запасом [SETTLE_MS]) следующий бар после [nowMs] — до тех пор бары из блокчейна, собранные
         * в [nowMs], не устаревают: копии CDN у них нет.
         */
        internal fun nextSettledClose(nowMs: Long, barMs: Long): Long {
            val t = nowMs - SETTLE_MS
            return t - t.mod(barMs) + barMs + SETTLE_MS
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
