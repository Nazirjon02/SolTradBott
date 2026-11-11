package tj.khujand.solana.trading.bot.exchange.chain

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Состояние прототипа для экрана настроек и отчёта.
 * @param skipped почему пулы списка наблюдения не подписаны: причина → сколько.
 */
data class ChainCandleStatus(
    val running: Boolean = false,
    val connected: Boolean = false,
    val host: String = "",
    val helius: Boolean = false,
    val poolsLive: Int = 0,
    val poolsWatched: Int = 0,
    val skipped: Map<SkipReason, Int> = emptyMap(),
    val tradesToday: Long = 0,
    val tradesTotal: Long = 0,
    val usage: UsageSnapshot = UsageSnapshot(),
    val dailyLimit: Int = 0,
    val pausedUntilMs: Long = 0,
    val reconnects: Int = 0,
    val lastReconcile: String? = null,
    val error: String? = null,
)

/**
 * Подписка на пул: цена (байты, прогноз кредитов в сутки) и что в потоке.
 * @param noise уведомления без сделки этого пула (транзакции MEV-ботов, маршруты через соседние пулы).
 * @param measuredMs сколько длится замер (с первой подписки).
 */
data class ChainPoolStat(
    val pool: String,
    val symbol: String,
    val dex: ChainDex,
    val subscribed: Boolean,
    val skip: SkipReason?,
    val bytes: Long,
    val measuredMs: Long,
    val creditsPerDayNow: Double,
    val trades: Long,
    val noise: Long,
    val failedTx: Long,
    val truncated: Long,
) {
    /** Средняя цена подписки за весь замер, кредитов в сутки. */
    val creditsPerDayAvg: Double get() = if (measuredMs <= 0) 0.0 else bytes / BYTES_PER_CREDIT * DAY_MS / measuredMs
}

/**
 * Свечи OHLCV прямо из блокчейна Solana для пулов списка наблюдения — прототип в теневом режиме: свечи
 * строятся и сверяются с GeckoTerminal, но стратегии их не получают.
 *
 * Как работает:
 *  - WebSocket к RPC ([rpcUrl] — тот же адрес, что в настройке «Solana RPC URL»; ключ Helius живёт в нём),
 *    переподключение с паузой и WS-пинг раз в минуту (Helius закрывает соединение после 10 минут тишины);
 *  - одна подписка `logsSubscribe` с `mentions: [pool]` на пул, отписка от выбывших монет;
 *  - сделки — из логов программ ([SwapLogDecoder]); время — из события PumpSwap или по слоту ([SlotClock]);
 *  - бары 1m/5m по правилу GeckoTerminal ([ChainBarSeries]), USD — по цене SOL;
 *  - история и бары, пропущенные из-за обрыва, — один раз из GeckoTerminal после закрытия неполного бара;
 *  - расход кредитов считается ([HeliusUsage]), дорогие подписки снимаются ([CreditBudget]);
 *  - раз в час — сверка 5m-баров с GeckoTerminal ([CandleReconciler]), итог — в [reportSink] и [status].
 *
 * Все общие структуры — под [lock]: соединение, менеджер подписок и очередь GeckoTerminal работают в разных
 * корутинах на пуле потоков.
 *
 * @param watchlist пулы списка наблюдения сканера (адрес пула, mint монеты, DEX).
 * @param geckoCandles закрытые бары GeckoTerminal (пул, "minute", агрегат, сколько); пусто — нет данных или
 *   очередь занята (тогда попробуем позже).
 * @param geckoSpacingMs пауза между своими запросами к GeckoTerminal: прототип не должен отнимать лимит у
 *   стратегий.
 * @param geckoMinutes просить ли у GeckoTerminal ещё и 1m-историю (без ключа — нет: лимит дорог).
 * @param geckoHistory подгружать ли историю и неполные бары из GeckoTerminal; прогон сверки выключает это,
 *   чтобы не тратить чужой лимит запросов, — ему нужна только итоговая сверка.
 * @param onEvent заметные события (старт, лимит на сутки, итог сверки) — для ленты активности.
 * @param tradeSink каждая принятая сделка и курс котировки в USD (для прогона сверки).
 */
class ChainCandleService(
    private val rpcUrl: () -> String?,
    private val watchlist: suspend () -> List<WatchedPool>,
    private val solPriceUsd: suspend () -> Double?,
    private val geckoCandles: suspend (pool: String, timeframe: String, aggregate: Int, limit: Int) -> List<Candle>,
    private val dailyCreditLimit: () -> Int,
    private val geckoSpacingMs: () -> Long,
    private val geckoMinutes: () -> Boolean = { false },
    private val geckoHistory: Boolean = true,
    private val reconcileEveryMs: Long = RECONCILE_EVERY_MS,
    private val reportSink: ((String) -> Unit)? = null,
    private val tradeSink: ((ChainTrade, Double) -> Unit)? = null,
    private val onEvent: (String) -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
    private val keepLeftMs: Long = KEEP_LEFT_MS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class PoolState(val meta: ChainPool, var symbol: String) {
        val coverage = Coverage()
        val rate = RateWindow()
        val m1 = ChainBarSeries(60_000L)
        val m5 = ChainBarSeries(300_000L)
        var admitted = false
        var inWatchlist = true
        var subId: Long? = null
        var reqId: Long? = null
        var reserve0 = meta.reserve0
        var reserve1 = meta.reserve1
        var trades = 0L
        var failedTx = 0L
        var truncated = 0L
        var noise = 0L
        var leftAtMs = 0L
    }

    private class GeckoJob(val pool: String, val aggregate: Int, var dueAtMs: Long, val reconcile: Boolean, var attempts: Int = 0)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()

    // ── под lock ──
    private val pools = HashMap<String, PoolState>()
    private val resolved = HashMap<String, PoolResolution>()
    private val banned = HashMap<String, Pair<Long, SkipReason>>()
    private val pendingSubs = HashMap<Long, String>()
    private val subToPool = HashMap<Long, String>()
    private val geckoJobs = ArrayList<GeckoJob>()
    private val results = HashMap<String, ReconcileResult>()
    private val clock = SlotClock()
    private val usage = HeliusUsage(nowMs)
    private var watched: List<WatchedPool> = emptyList()
    private var nextId = 1L
    private var arrival = 0L
    private var tradesTotal = 0L
    private var tradesToday = 0L
    private var tradesDay = 0L
    private var unparsed = 0L
    private var reconnects = 0
    private var pausedUntil = 0L
    private var lastReconcileText: String? = null

    // ── читаются из разных потоков без lock ──
    @Volatile private var solUsd: Double? = null
    @Volatile private var lastMessageAt = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var scope: CoroutineScope? = null
    private var wsClient: HttpClient? = null
    private var rpcClient: HttpClient? = null

    private val _status = MutableStateFlow(ChainCandleStatus())
    val status: StateFlow<ChainCandleStatus> = _status.asStateFlow()

    val isRunning: Boolean get() = scope != null

    /** Запуск (повторный вызов при работающем — ничего не делает). Адрес узла читается при каждом запуске. */
    fun start() {
        if (scope != null) return
        val url = rpcUrl()?.trim().orEmpty()
        val ws = wsUrlOf(url)
        if (ws == null) {
            lastError = "в настройке «Solana RPC URL» нет адреса узла"
            _status.update { it.copy(running = false, error = lastError) }
            return
        }
        val wsc = HttpClient {
            install(WebSockets) {
                pingIntervalMillis = pingIntervalMs
                maxFrameSize = Long.MAX_VALUE
            }
            install(HttpTimeout) { connectTimeoutMillis = 15_000L }
        }
        val rpc = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 15_000L } }
        wsClient = wsc
        rpcClient = rpc
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        lastError = null
        onEvent("⛓ Свечи из блокчейна (теневой режим): подключаюсь к ${hostOf(url)}")
        _status.update { it.copy(running = true, host = hostOf(url), helius = isHelius(url), error = null) }
        s.launch { priceLoop() }
        s.launch { connectionLoop(wsc, ws) }
        s.launch { managerLoop(rpc, url) }
        s.launch { geckoLoop() }
    }

    /** Остановка: подписки и соединение закрываются; собранные бары остаются в памяти до следующего старта. */
    fun stop() {
        val s = scope ?: return
        scope = null
        s.cancel()
        wsClient?.close()
        rpcClient?.close()
        wsClient = null
        rpcClient = null
        session = null
        onEvent("⛓ Свечи из блокчейна выключены")
        _status.update { it.copy(running = false, connected = false, poolsLive = 0) }
    }

    /** Бары пула из цепочки (закрытые, по правилу GeckoTerminal); пусто — пул не подписан. */
    suspend fun candles(pool: String, timeframe: String, includeOpen: Boolean = false): List<Candle> = lock.withLock {
        val st = pools[pool] ?: return@withLock emptyList()
        val series = if (timeframe.trim().lowercase() == "1m") st.m1 else st.m5
        series.candles(nowMs(), st.coverage, includeOpen)
    }

    /** Бары серии с источником (для отчёта и прогона сверки). */
    suspend fun bars(pool: String, aggregate: Int): List<ChainBar> = lock.withLock {
        val st = pools[pool] ?: return@withLock emptyList()
        (if (aggregate == 1) st.m1 else st.m5).bars(nowMs(), st.coverage)
    }

    /** Итоги последней сверки по пулам. */
    suspend fun reconcileResults(): List<ReconcileResult> = lock.withLock { results.values.toList() }

    /** Пулы, по которым есть бары (подписанные сейчас и выбывшие недавно): адрес → символ. */
    suspend fun poolSymbols(): Map<String, String> = lock.withLock { pools.mapValues { it.value.symbol } }

    /** Цена и поток каждой подписки — для отчёта: сколько стоит пул и какая доля уведомлений — сделки. */
    suspend fun poolStats(): List<ChainPoolStat> = lock.withLock {
        val now = nowMs()
        pools.values.map { st ->
            ChainPoolStat(
                pool = st.meta.address,
                symbol = st.symbol,
                dex = st.meta.dex,
                subscribed = st.subId != null,
                skip = banned[st.meta.address]?.second,
                bytes = st.rate.totalBytes,
                measuredMs = st.rate.ageMs(now),
                creditsPerDayNow = st.rate.creditsPerDay(now),
                trades = st.trades,
                noise = st.noise,
                failedTx = st.failedTx,
                truncated = st.truncated,
            )
        }
    }

    /**
     * Сверка сейчас: для каждого пула с полными 5m-барами — свежие бары GeckoTerminal и сравнение. Обычно
     * сверка идёт сама раз в [reconcileEveryMs]; напрямую её зовёт прогон сверки перед итоговым отчётом.
     */
    suspend fun reconcileNow(): List<ReconcileResult> {
        val targets = lock.withLock { pools.values.filter { hasCompleteBars(it) }.map { it.meta.address } }
        for (p in targets) {
            val gecko = runCatching { geckoCandles(p, "minute", 5, GECKO_LIMIT_5M) }.getOrDefault(emptyList())
            if (gecko.isNotEmpty()) applyReconcile(p, gecko)
            delay(geckoSpacingMs())
        }
        return writeReport()
    }

    // ─── Цена SOL ────────────────────────────────────────────────────────────

    private suspend fun priceLoop() {
        while (alive()) {
            val p = runCatching { solPriceUsd() }.getOrNull()
            if (p != null && p > 0) solUsd = p
            delay(SOL_PRICE_REFRESH_MS)
        }
    }

    // ─── Соединение ──────────────────────────────────────────────────────────

    private suspend fun alive(): Boolean = currentCoroutineContext().isActive && scope != null

    private suspend fun connectionLoop(client: HttpClient, wsUrl: String) {
        var failures = 0
        while (alive()) {
            try {
                client.webSocket(urlString = wsUrl) {
                    failures = 0
                    lock.withLock { usage.onConnection() }
                    session = this
                    lastMessageAt = nowMs()
                    publish()
                    resubscribeAll()
                    val parts = ArrayList<ByteArray>()
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                parts += frame.data
                                if (frame.fin) {
                                    val bytes = if (parts.size == 1) parts[0] else concat(parts)
                                    parts.clear()
                                    onMessage(bytes.decodeToString(), bytes.size)
                                }
                            }
                            is Frame.Binary -> lock.withLock { usage.onMessage(frame.data.size) }
                            else -> Unit
                        }
                    }
                    val reason = closeReason.await()
                    onDiagnostic("соединение закрыто: ${reason?.code} ${hideApiKey(reason?.message)}")
                }
                lastError = null
            } catch (e: CancellationException) {
                if (!alive()) throw e
                onDiagnostic("соединение отменено: ${hideApiKey(e.message)}")
            } catch (e: Exception) {
                // Ktor пишет в текст ошибки адрес узла целиком — вместе с ключом Helius.
                lastError = hideApiKey(e.message ?: e::class.simpleName)
                onDiagnostic("ошибка соединения: ${e::class.simpleName}: $lastError")
            }
            onDisconnected()
            failures++
            publish()
            delay(BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.lastIndex)])
        }
    }

    private suspend fun send(text: String) {
        val s = session ?: return
        try {
            s.send(Frame.Text(text))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Соединение рвётся — подписка восстановится после переподключения.
        }
    }

    /** Новое соединение: номера подписок у узла новые — подписываемся на все допущенные пулы заново. */
    private suspend fun resubscribeAll() {
        val out = lock.withLock {
            pendingSubs.clear()
            subToPool.clear()
            pools.values.filter { it.admitted }.map { subscribeRequest(it) }
        }
        out.forEach { send(it) }
    }

    /** Под [lock]. */
    private fun subscribeRequest(st: PoolState): String {
        val id = nextId++
        st.reqId = id
        st.subId = null
        pendingSubs[id] = st.meta.address
        return """{"jsonrpc":"2.0","id":$id,"method":"logsSubscribe","params":[{"mentions":["${st.meta.address}"]},{"commitment":"confirmed"}]}"""
    }

    /** Под [lock]: снять подписку (если была) и закрыть покрытие. */
    private fun unsubscribeRequest(st: PoolState, now: Long): String? {
        st.admitted = false
        st.reqId?.let { pendingSubs.remove(it) }
        st.reqId = null
        val sub = st.subId ?: return null
        st.subId = null
        subToPool.remove(sub)
        st.coverage.close(now - END_MARGIN_MS)
        val id = nextId++
        return """{"jsonrpc":"2.0","id":$id,"method":"logsUnsubscribe","params":[$sub]}"""
    }

    private suspend fun onDisconnected() {
        session = null
        val now = nowMs()
        lock.withLock {
            reconnects++
            // Соединение могло молча умереть раньше, чем это заметили: сделки после последнего сообщения
            // считаем потерянными.
            val end = minOf(now, lastMessageAt + 2_000L) - END_MARGIN_MS
            for (st in pools.values) {
                st.subId = null
                st.reqId = null
                st.coverage.close(end)
            }
            pendingSubs.clear()
            subToPool.clear()
        }
    }

    // ─── Сообщения узла ──────────────────────────────────────────────────────

    private suspend fun onMessage(text: String, bytes: Int) {
        val now = nowMs()
        lastMessageAt = now
        // Почти все уведомления — шум MEV-ботов без сделок: их не разбираем целиком, только относим к пулу.
        val maySwap = text.contains("Program data: ") || text.contains("ray_log: ")
        if (!maySwap && text.contains("\"logsNotification\"")) {
            val sub = fastSubscriptionId(text)
            lock.withLock {
                usage.onMessage(bytes)
                val st = sub?.let { subToPool[it] }?.let { pools[it] } ?: return@withLock
                st.rate.add(bytes, now)
                if (text.contains("\"err\":null")) st.noise++ else st.failedTx++
            }
            return
        }
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            null
        }
        when {
            root == null -> lock.withLock { usage.onMessage(bytes) }
            root["method"]?.jsonPrimitive?.content == "logsNotification" -> onNotification(root, bytes, now)
            else -> onResponse(root, bytes, now)
        }
    }

    private suspend fun onResponse(root: JsonObject, bytes: Int, now: Long) = lock.withLock {
        usage.onMessage(bytes)
        val id = root["id"]?.jsonPrimitive?.longOrNull ?: return@withLock
        val pool = pendingSubs.remove(id) ?: return@withLock
        val st = pools[pool] ?: return@withLock
        if (st.reqId != id) return@withLock
        st.reqId = null
        val sub = (root["result"] as? JsonPrimitive)?.longOrNull
        if (sub == null || root["error"] != null) {
            st.admitted = false
            banned[pool] = (now + RETRY_FAILED_MS) to SkipReason.SUBSCRIBE_FAILED
            return@withLock
        }
        st.subId = sub
        subToPool[sub] = pool
        st.rate.start(now)
        if (st.coverage.openSince() == null) {
            st.coverage.open(now + START_MARGIN_MS)
            // Бар, в котором подписка началась (или возобновилась), неполный: после его закрытия
            // GeckoTerminal даст и его, и историю до подписки.
            scheduleGecko(pool, now)
        }
    }

    private suspend fun onNotification(root: JsonObject, bytes: Int, now: Long) {
        val params = root["params"]?.jsonObject
        val sub = params?.get("subscription")?.jsonPrimitive?.longOrNull
        val result = params?.get("result")?.jsonObject
        val slot = result?.get("context")?.jsonObject?.get("slot")?.jsonPrimitive?.longOrNull
        val value = result?.get("value")?.jsonObject
        if (sub == null || slot == null || value == null) {
            lock.withLock { usage.onMessage(bytes) }
            return
        }
        val signature = value["signature"]?.jsonPrimitive?.content ?: ""
        val failed = value["err"].let { it != null && it !is JsonNull }
        val logs = (value["logs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val sinkCalls = ArrayList<Pair<ChainTrade, Double>>()
        lock.withLock {
            usage.onMessage(bytes)
            val st = subToPool[sub]?.let { pools[it] } ?: return@withLock
            st.rate.add(bytes, now)
            if (failed) {
                st.failedTx++
                return@withLock
            }
            val decoded = SwapLogDecoder.decode(logs)
            if (decoded.truncated) st.truncated++
            unparsed += decoded.unparsed
            // Время блока из событий PumpSwap — опора часов для всех пулов, в том числе чужих в этой транзакции.
            decoded.swaps.firstNotNullOfOrNull { it.blockTimeSec }?.let { clock.anchor(slot, it) }
            var accepted = 0
            for (swap in decoded.swaps) {
                if (swap.dex != st.meta.dex) continue
                if (swap.dex == ChainDex.RAYDIUM_AMM_V4 && !ammV4Match(swap, st.reserve0, st.reserve1)) continue
                val exact = swap.blockTimeSec
                val timeMs = exact?.times(1000) ?: clock.estimateMs(slot) ?: (now - RECEIVE_LAG_MS)
                val order = slot * 100_000L + (arrival++ % 100_000L)
                val trade = swap.toTrade(st.meta, signature, slot, order, timeMs, exact != null) ?: continue
                if (swap.dex == ChainDex.RAYDIUM_AMM_V4) trackAmmReserves(st, swap)
                val quoteUsd = if (trade.quote == QuoteAsset.USDC) 1.0 else solUsd ?: continue
                val priced = PricedTrade(trade.timeMs, order, trade.priceQuote * quoteUsd, trade.quoteAmount * quoteUsd)
                st.m1.add(priced)
                st.m5.add(priced)
                st.trades++
                countTrade(now)
                accepted++
                if (tradeSink != null) sinkCalls += trade to quoteUsd
            }
            if (accepted == 0) st.noise++
        }
        sinkCalls.forEach { (t, q) -> tradeSink?.invoke(t, q) }
    }

    /** Под [lock]: резервы AMM v4 после сделки — по ним привязывается следующий ray_log. */
    private fun trackAmmReserves(st: PoolState, swap: RawSwap) {
        val r0 = swap.reserve0 ?: return
        val r1 = swap.reserve1 ?: return
        if (swap.side0In == true) {
            st.reserve0 = r0 + swap.amountIn
            st.reserve1 = (r1 - swap.amountOut).coerceAtLeast(1.0)
        } else {
            st.reserve0 = (r0 - swap.amountOut).coerceAtLeast(1.0)
            st.reserve1 = r1 + swap.amountIn
        }
    }

    /** Под [lock]. */
    private fun countTrade(now: Long) {
        val day = utcDayStart(now)
        if (day != tradesDay) {
            tradesDay = day
            tradesToday = 0
        }
        tradesToday++
        tradesTotal++
    }

    // ─── Список наблюдения, бюджет, подписки ─────────────────────────────────

    private suspend fun managerLoop(client: HttpClient, url: String) {
        var lastWatchlistAt = 0L
        while (alive()) {
            val now = nowMs()
            if (now - lastWatchlistAt >= WATCHLIST_EVERY_MS) {
                lastWatchlistAt = now
                val list = runCatching { watchlist() }.getOrNull()
                if (list != null) {
                    lock.withLock { watched = list }
                    resolveNew(client, url, list)
                    applyWatchlist(list, now)
                }
            }
            applyBudget(now)
            watchdog(now)
            publish()
            delay(MANAGER_TICK_MS)
        }
    }

    private suspend fun resolveNew(client: HttpClient, url: String, list: List<WatchedPool>) {
        val fresh = lock.withLock {
            val current = list.map { it.address }.toSet()
            if (resolved.size > MAX_RESOLVED) resolved.keys.retainAll(current)
            val unknown = list.filter { it.address.isNotBlank() && it.address !in resolved }
            unknown.filter { it.dexId.lowercase() !in SUPPORTED_DEX_IDS }
                .forEach { resolved[it.address] = PoolResolution.Skip(SkipReason.UNSUPPORTED_DEX) }
            unknown.filter { it.dexId.lowercase() in SUPPORTED_DEX_IDS }
        }
        for (chunk in fresh.chunked(MAX_ACCOUNTS_PER_CALL)) {
            val accounts = getMultipleAccounts(client, url, chunk.map { it.address }) ?: return
            val partials = HashMap<String, PoolLayouts.Partial>()
            val out = HashMap<String, PoolResolution>()
            for (w in chunk) {
                val acc = accounts[w.address]
                val p = acc?.let { PoolLayouts.parsePool(w.address, it) }
                when {
                    acc == null -> out[w.address] = PoolResolution.Skip(SkipReason.NOT_FOUND)
                    ChainDex.byProgram(acc.owner) == null -> out[w.address] = PoolResolution.Skip(SkipReason.UNSUPPORTED_DEX)
                    p == null -> out[w.address] = PoolResolution.Skip(SkipReason.BAD_LAYOUT)
                    else -> partials[w.address] = p
                }
            }
            val needs = partials.values.flatMap { it.needs() }.distinct()
            val extra = HashMap<String, RawAccount>()
            for (n in needs.chunked(MAX_ACCOUNTS_PER_CALL)) extra += getMultipleAccounts(client, url, n) ?: return
            for (w in chunk) {
                val p = partials[w.address] ?: continue
                out[w.address] = PoolLayouts.finish(p, w.mint, extra)
            }
            lock.withLock { resolved.putAll(out) }
        }
    }

    private suspend fun getMultipleAccounts(client: HttpClient, url: String, addrs: List<String>): Map<String, RawAccount>? {
        val body = buildString {
            append("""{"jsonrpc":"2.0","id":1,"method":"getMultipleAccounts","params":[[""")
            addrs.forEachIndexed { i, a -> if (i > 0) append(','); append('"').append(a).append('"') }
            append("""],{"encoding":"base64","commitment":"confirmed"}]}""")
        }
        return try {
            lock.withLock { usage.onHttpCall() }
            val resp = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (resp.status.value != 200) {
                lastError = "getMultipleAccounts: HTTP ${resp.status.value}"
                null
            } else {
                PoolLayouts.parseMultipleAccounts(addrs, json.parseToJsonElement(resp.bodyAsText()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "getMultipleAccounts: ${hideApiKey(e.message)}"
            null
        }
    }

    /** Новые монеты — в кандидаты, выбывшие — отписка; выбывший пул живёт ещё [KEEP_LEFT_MS] для сверки. */
    private suspend fun applyWatchlist(list: List<WatchedPool>, now: Long) {
        val out = ArrayList<String>()
        lock.withLock {
            val byAddr = list.associateBy { it.address }
            for (w in list) {
                val r = resolved[w.address] as? PoolResolution.Ok ?: continue
                val st = pools.getOrPut(w.address) { PoolState(r.pool, w.symbol) }
                st.symbol = w.symbol
                st.inWatchlist = true
                st.leftAtMs = 0L
            }
            for (st in pools.values) {
                if (st.meta.address in byAddr || !st.inWatchlist) continue
                st.inWatchlist = false
                st.leftAtMs = now
                unsubscribeRequest(st, now)?.let(out::add)
                // Итог сверки выбывшего пула сохраняем: после выхода из списка его баров больше не будет.
                if (hasCompleteBars(st, now + RECONCILE_SETTLE_MS)) {
                    geckoJobs += GeckoJob(st.meta.address, 5, now + RECONCILE_SETTLE_MS, reconcile = true)
                }
            }
            pools.values.removeAll { !it.inWatchlist && now - it.leftAtMs > keepLeftMs }
        }
        out.forEach { send(it) }
    }

    private suspend fun applyBudget(now: Long) {
        val out = ArrayList<String>()
        var stoppedNow = false
        lock.withLock {
            banned.entries.removeAll { it.value.first <= now }
            if (now < pausedUntil) return@withLock
            val live = pools.values.filter { it.admitted }
            val costs = live.map {
                CreditBudget.PoolCost(it.meta.address, it.rate.creditsPerDay(now), it.rate.ageMs(now), it.rate.totalBytes)
            }
            val decision = CreditBudget.decide(costs, dailyCreditLimit(), usage.creditsToday(), usage.msLeftToday())
            if (decision.stopAll) {
                pausedUntil = usage.nextDayStart()
                stoppedNow = true
            }
            for ((pool, reason) in decision.drop) {
                val st = pools[pool] ?: continue
                unsubscribeRequest(st, now)?.let(out::add)
                banned[pool] = when (reason) {
                    CreditBudget.Reason.EXPENSIVE -> usage.nextDayStart() to SkipReason.EXPENSIVE
                    CreditBudget.Reason.OVER_BUDGET -> (now + RETRY_OVER_BUDGET_MS) to SkipReason.OVER_BUDGET
                    CreditBudget.Reason.DAY_LIMIT -> usage.nextDayStart() to SkipReason.DAY_LIMIT
                }
            }
            if (decision.stopAll || session == null) return@withLock
            // Дешёвые сначала: цену подписки заранее не знаем, а объём за час с ней хоть как-то связан.
            val candidates = watched.sortedBy { it.activity }
                .mapNotNull { w -> pools[w.address]?.takeIf { !it.admitted && it.inWatchlist && w.address !in banned } }
                .take(decision.admit)
            for (st in candidates) {
                st.admitted = true
                out += subscribeRequest(st)
            }
        }
        if (stoppedNow) onEvent("⛓ Лимит кредитов Helius на сутки исчерпан — подписки сняты до 00:00 UTC")
        out.forEach { send(it) }
    }

    /** Соединение живо по пингам, но уведомления перестали приходить — переподключаемся. */
    private suspend fun watchdog(now: Long) {
        val s = session ?: return
        val subscribed = lock.withLock { subToPool.size }
        if (subscribed >= WATCHDOG_MIN_SUBS && now - lastMessageAt > WATCHDOG_SILENCE_MS) {
            try {
                s.close(CloseReason(CloseReason.Codes.NORMAL, "silence"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // ─── GeckoTerminal: история, досинхронизация, сверка ─────────────────────

    /** Под [lock]: бары GeckoTerminal после закрытия бара, в котором началась (или возобновилась) подписка. */
    private fun scheduleGecko(pool: String, now: Long) {
        if (!geckoHistory) return
        geckoJobs.removeAll { it.pool == pool && !it.reconcile }
        geckoJobs += GeckoJob(pool, 5, now - now.mod(300_000L) + 300_000L + GECKO_SETTLE_MS, reconcile = false)
        if (geckoMinutes()) {
            geckoJobs += GeckoJob(pool, 1, now - now.mod(60_000L) + 60_000L + GECKO_SETTLE_MS, reconcile = false)
        }
    }

    private suspend fun geckoLoop() {
        var lastReconcileAt = nowMs()
        while (alive()) {
            val now = nowMs()
            if (now - lastReconcileAt >= reconcileEveryMs) {
                lastReconcileAt = now
                lock.withLock {
                    pools.values.filter { it.inWatchlist && hasCompleteBars(it) }.forEach { st ->
                        if (geckoJobs.none { it.pool == st.meta.address && it.reconcile }) {
                            geckoJobs += GeckoJob(st.meta.address, 5, now, reconcile = true)
                        }
                    }
                }
            }
            val job = lock.withLock {
                geckoJobs.filter { it.dueAtMs <= now }.minByOrNull { it.dueAtMs }?.also { geckoJobs.remove(it) }
            }
            if (job == null) {
                delay(GECKO_IDLE_MS)
                continue
            }
            val limit = if (job.aggregate == 1) GECKO_LIMIT_1M else GECKO_LIMIT_5M
            val candles = runCatching { geckoCandles(job.pool, "minute", job.aggregate, limit) }.getOrDefault(emptyList())
            when {
                candles.isEmpty() -> if (job.attempts < GECKO_MAX_ATTEMPTS) {
                    job.attempts++
                    job.dueAtMs = nowMs() + GECKO_RETRY_MS
                    lock.withLock { geckoJobs += job }
                }
                job.reconcile -> {
                    applyReconcile(job.pool, candles)
                    if (lock.withLock { geckoJobs.none { it.reconcile } }) writeReport()
                }
                else -> lock.withLock {
                    pools[job.pool]?.let { st -> (if (job.aggregate == 1) st.m1 else st.m5).putGecko(candles) }
                }
            }
            delay(geckoSpacingMs())
        }
    }

    /** Под [lock]: есть ли полные 5m-бары, которые GeckoTerminal уже успел дописать к [asOfMs]. */
    private fun hasCompleteBars(st: PoolState, asOfMs: Long = nowMs()): Boolean =
        st.m5.bars(asOfMs - RECONCILE_SETTLE_MS, st.coverage).any { it.source == BarSource.CHAIN }

    private suspend fun applyReconcile(pool: String, gecko: List<Candle>) = lock.withLock {
        val st = pools[pool] ?: return@withLock
        st.m5.putGecko(gecko)
        val cutoff = nowMs() - RECONCILE_SETTLE_MS
        val lastGecko = gecko.maxOfOrNull { it.openTimeMs } ?: return@withLock
        results[pool] = CandleReconciler.compare(
            pool = pool,
            label = "${st.symbol} (${st.meta.dex.label})",
            chain = st.m5.bars(cutoff, st.coverage),
            gecko = gecko,
            covered = { open -> st.coverage.covers(open, open + st.m5.barMs) },
            toMs = minOf(cutoff - st.m5.barMs, lastGecko),
        )
    }

    /** Отчёт сверки — в [reportSink], ленту и строку состояния. */
    private suspend fun writeReport(): List<ReconcileResult> {
        val report = lock.withLock {
            val list = results.values.toList()
            val t = CandleReconciler.total(list)
            val u = usage.snapshot()
            val summary = if (t.matched == 0) "сверка: полных баров пока нет" else
                "сверка 5m: ${t.pools} пулов, ${t.matched} баров; close ±1% — ${pct(t.closeWithin1, t.matched)}, " +
                    "объём ±1% — ${pct(t.volumeWithin1, t.volumeN)}; пропущено баров ${t.geckoOnly}, лишних ${t.chainOnly}"
            lastReconcileText = summary
            val stats = ChainReport.Stats(
                trades = tradesTotal,
                truncated = pools.values.sumOf { it.truncated },
                unparsed = unparsed,
                failedTx = pools.values.sumOf { it.failedTx },
                noise = pools.values.sumOf { it.noise },
                reconnects = reconnects,
            )
            Triple(list, summary, ChainReport.render(list, t, u, stats, nowMs()))
        }
        reportSink?.invoke(report.third)
        onEvent("⛓ ${report.second}")
        publish()
        return report.first
    }

    private fun pct(a: Int, n: Int): String = if (n == 0) "—" else "${(a * 1000L / n) / 10.0}%"

    // ─── Состояние ───────────────────────────────────────────────────────────

    private suspend fun publish() {
        val url = rpcUrl()?.trim().orEmpty()
        val status = lock.withLock {
            val skipped = HashMap<SkipReason, Int>()
            for (w in watched) {
                val reason = banned[w.address]?.second ?: (resolved[w.address] as? PoolResolution.Skip)?.reason ?: continue
                skipped[reason] = (skipped[reason] ?: 0) + 1
            }
            val now = nowMs()
            ChainCandleStatus(
                running = scope != null,
                connected = session != null,
                host = hostOf(url),
                helius = isHelius(url),
                poolsLive = subToPool.size,
                poolsWatched = watched.size,
                skipped = skipped,
                tradesToday = if (utcDayStart(now) == tradesDay) tradesToday else 0,
                tradesTotal = tradesTotal,
                usage = usage.snapshot(),
                dailyLimit = dailyCreditLimit(),
                pausedUntilMs = pausedUntil.takeIf { it > now } ?: 0L,
                reconnects = reconnects,
                lastReconcile = lastReconcileText,
                error = lastError,
            )
        }
        _status.value = status
    }

    companion object {
        /** DexScreener dexId пулов, которые разбираем (Raydium — AMM v4, CPMM и CLMM по владельцу аккаунта). */
        val SUPPORTED_DEX_IDS = setOf("pumpswap", "raydium")

        /** WS-пинг: Helius закрывает соединение после 10 минут без активности. */
        const val PING_INTERVAL_MS = 60_000L

        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000, 30_000, 60_000)

        /** Подписка подтверждена — сделки уровня confirmed идут чуть позже: начало покрытия с запасом. */
        private const val START_MARGIN_MS = 2_000L

        /** Обрыв: сделки последних секунд до него могли не дойти. */
        private const val END_MARGIN_MS = 3_000L

        /** Без опор часов: уведомление confirmed приходит примерно через столько после блока. */
        private const val RECEIVE_LAG_MS = 1_500L

        private const val SOL_PRICE_REFRESH_MS = 20_000L
        private const val MANAGER_TICK_MS = 10_000L
        private const val WATCHLIST_EVERY_MS = 60_000L
        private const val MAX_ACCOUNTS_PER_CALL = 100
        private const val MAX_RESOLVED = 2_000

        /** Выбывший из списка наблюдения пул живёт столько — для сверки его последних баров. */
        const val KEEP_LEFT_MS = 3 * 60 * 60_000L
        private const val RETRY_FAILED_MS = 60 * 60_000L
        private const val RETRY_OVER_BUDGET_MS = 60 * 60_000L
        private const val WATCHDOG_MIN_SUBS = 5
        private const val WATCHDOG_SILENCE_MS = 120_000L

        /** GeckoTerminal дописывает бар не сразу (и CDN держит копию до минуты). */
        private const val GECKO_SETTLE_MS = 90_000L

        /** Сверяем бары, закончившиеся не позже стольких минут назад: GeckoTerminal успевает их дописать. */
        const val RECONCILE_SETTLE_MS = 10 * 60_000L

        /** Сверка — раз в час. */
        const val RECONCILE_EVERY_MS = 60 * 60_000L

        private const val GECKO_LIMIT_5M = 200
        private const val GECKO_LIMIT_1M = 200
        private const val GECKO_MAX_ATTEMPTS = 3
        private const val GECKO_RETRY_MS = 2 * 60_000L
        private const val GECKO_IDLE_MS = 5_000L

        /** wss-адрес из адреса RPC: https → wss, http → ws; null — адреса нет. */
        fun wsUrlOf(rpcUrl: String): String? {
            val u = rpcUrl.trim()
            return when {
                u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
                u.startsWith("http://") -> "ws://" + u.removePrefix("http://")
                u.startsWith("wss://") || u.startsWith("ws://") -> u
                else -> null
            }
        }

        /** Хост без ключа: адрес Helius содержит api-key — его не показываем и не пишем в журнал. */
        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')

        fun isHelius(url: String): Boolean = hostOf(url).contains("helius", ignoreCase = true)

        /** Номер подписки из хвоста уведомления без разбора JSON. */
        internal fun fastSubscriptionId(text: String): Long? {
            val k = text.lastIndexOf("\"subscription\":")
            if (k < 0) return null
            var i = k + 15
            while (i < text.length && text[i] == ' ') i++
            val start = i
            while (i < text.length && text[i].isDigit()) i++
            return text.substring(start, i).toLongOrNull()
        }

        private fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) {
                p.copyInto(out, o)
                o += p.size
            }
            return out
        }
    }
}
