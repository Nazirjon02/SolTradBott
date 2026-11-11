package tj.khujand.solana.trading.bot.exchange.dex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Одна свеча OHLCV. [openTimeMs] — время открытия в миллисекундах UTC.
 * Вспомогательные поля используются анализом прайс-экшена (модуль [tj.khujand.solana.trading.bot.domain.dars]).
 */
data class Candle(
    val openTimeMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
) {
    val isBullish: Boolean get() = close >= open

    /** Тело свечи (модуль). */
    val body: Double get() = abs(close - open)

    /** Полный диапазон свечи (high-low), не отрицательный. */
    val range: Double get() = (high - low).coerceAtLeast(0.0)

    /**
     * Где закрылся бар относительно своего диапазона: 1.0 — у максимума, 0.0 — у минимума,
     * 0.5 — середина. По методике «где закрываются бары» (Урок 1).
     */
    val closePosition: Double
        get() = if (range <= 0.0) 0.5 else ((close - low) / range).coerceIn(0.0, 1.0)
}

/** Страница истории свечей: свечи по возрастанию времени + HTTP-код ответа (0 — сетевая ошибка). */
data class OhlcvPage(val candles: List<Candle>, val status: Int)

/** Списки пулов GeckoTerminal. */
enum class GtPoolList(val path: String) {
    TRENDING("trending_pools"),
    NEW("new_pools"),
    TOP("pools"),
}

/** Пул из списков GeckoTerminal (метрики — на момент запроса). */
data class GtPool(
    val address: String,
    val name: String,
    val baseMint: String,
    val quoteMint: String,
    val dexId: String,
    val priceUsd: Double,
    val fdvUsd: Double,
    val marketCapUsd: Double?,
    val reserveUsd: Double,
    val createdAtMs: Long,
)

/**
 * Источник свечей OHLCV и списков пулов — GeckoTerminal.
 *
 * Лимиты (проверено 2026-11-11): без ключа — около 5 запросов в минуту на IP, дальше HTTP 429, зато без месячного
 * предела. С бесплатным Demo-ключом CoinGecko те же данные отдаёт `api.coingecko.com/api/v3/onchain` — до 100
 * запросов в минуту, но 10 000 в месяц; кончились — бот сам продолжает без ключа ([KeyQuotaWatch]).
 * Все запросы идут через общую очередь [RequestLimiter]; свечи для стратегий хранит [CandleStore] —
 * закрытые бары, один запрос на бар (см. там же, почему).
 */
object GeckoTerminalApi {

    private const val KEYLESS_BASE = "https://api.geckoterminal.com/api/v2"
    private const val KEYED_BASE = "https://api.coingecko.com/api/v3/onchain"
    private const val KEY_HEADER = "x-cg-demo-api-key"

    /** Без ключа — 4 запроса в минуту: чуть ниже порога, который сервер к тому же двигает под нагрузкой. */
    private const val KEYLESS_INTERVAL_MS = 15_000L

    /** С ключом — 60 в минуту при лимите 100: запас на бэктест и второе приложение с тем же ключом. */
    private const val KEYED_INTERVAL_MS = 1_000L

    /** Бэктест и списки пулов могут ждать очереди долго: их никто не торопит, а пропуск страницы ломает историю. */
    private const val HISTORY_MAX_WAIT_MS = 120_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }

    // StateFlow — потокобезопасная ячейка: ключ читают параллельные запросы, меняют настройки.
    private val apiKey = MutableStateFlow<String?>(null)

    // Отказы 429 на запросах с ключом: несколько подряд — кончилась месячная квота, дальше без ключа.
    private val quota = KeyQuotaWatch()

    private val limiter = RequestLimiter(intervalMs = { if (activeKey() != null) KEYED_INTERVAL_MS else KEYLESS_INTERVAL_MS })

    private val store = CandleStore(
        fetch = ::fetchCandles,
        limiter = limiter,
        liveAllowed = { activeKey() != null },
        onUnauthorized = ::rejectKey,
    )

    /** Состояние источника: ключ, отказы по лимиту, последний успешный ответ. */
    val status: StateFlow<CandleFeedStatus> get() = store.status

    /**
     * Demo-ключ CoinGecko из настроек; null или пусто — работаем без ключа. Повторное сохранение того же
     * ключа снимает отметки «отклонён» и «квота кончилась»: пользователь мог его активировать, поправить
     * опечатку или сменить план.
     */
    fun setApiKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        apiKey.value = clean
        quota.reset()
        store.setKeyState(usingKey = clean != null, keyRejected = false)
    }

    /**
     * Ключ, с которым идут запросы, или null. Отклонённый сервером ключ не используем до следующего сохранения,
     * упёршийся в месячную квоту — до срока из [KeyQuotaWatch]; срок вышел — пробуем ключ снова.
     */
    private fun activeKey(): String? {
        val key = apiKey.value ?: return null
        val s = store.status.value
        if (s.keyRejected) return null
        if (s.keyExhaustedUntilMs > 0L) {
            if (Clock.System.now().toEpochMilliseconds() < s.keyExhaustedUntilMs) return null
            store.setKeyState(usingKey = true, keyRejected = false)
        }
        return key
    }

    private fun rejectKey() {
        println("GeckoTerminal: ключ CoinGecko отклонён — работаю без ключа")
        store.setKeyState(usingKey = false, keyRejected = true)
    }

    /** Итог запроса с ключом: несколько отказов 429 подряд — кончилась квота, дальше без ключа (см. [KeyQuotaWatch]). */
    private fun onKeyedResponse(code: Int) {
        val until = quota.onKeyedResponse(code, Clock.System.now().toEpochMilliseconds()) ?: return
        println("GeckoTerminal: ключ CoinGecko упирается в лимит — похоже, кончилась месячная квота; работаю без ключа")
        store.setKeyState(usingKey = false, keyRejected = false, keyExhaustedUntilMs = until)
    }

    /**
     * Свечи пула Solana — закрытые бары по возрастанию времени (см. [CandleStore]).
     * @param poolAddress адрес пула (на Solana = DexScreener pairAddress).
     * @param timeframe "minute" | "hour" | "day".
     * @param aggregate агрегация внутри ТФ (minute: 1/5/15, hour: 1/4/12, day: 1).
     * @param limit сколько свечей нужно (макс. 1000).
     * @param live график сделки: с незакрытым баром и обновлением раз в 30 с — только с ключом API.
     * @param maxWaitMs сколько ждать очереди к API; не дождались — прежние бары или пусто.
     * Пустой список = данных нет.
     */
    suspend fun getCandles(
        poolAddress: String,
        timeframe: String,
        aggregate: Int = 1,
        limit: Int = 200,
        live: Boolean = false,
        maxWaitMs: Long = CandleStore.DEFAULT_MAX_WAIT_MS,
        network: String = "solana",
    ): List<Candle> = store.get(CandleKey(network, poolAddress, timeframe, aggregate), limit, live, maxWaitMs)

    /** Запрос к API: с ключом — на CoinGecko, без — на публичный GeckoTerminal. Второе значение — шёл ли ключ. */
    private suspend fun request(path: String): Pair<HttpResponse, Boolean> {
        val key = activeKey()
        val base = if (key != null) KEYED_BASE else KEYLESS_BASE
        val response = client.get("$base$path") {
            header("Accept", "application/json")
            if (key != null) header(KEY_HEADER, key)
        }
        return response to (key != null)
    }

    private suspend fun fetchCandles(key: CandleKey, limit: Int): CandleFetch = try {
        val (resp, withKey) = request(
            "/networks/${key.network}/pools/${key.pool}/ohlcv/${key.timeframe}" +
                "?aggregate=${key.aggregate}&limit=$limit&currency=usd"
        )
        val code = resp.status.value
        if (withKey) onKeyedResponse(code)
        when (code) {
            200 -> CandleFetch.Ok(parse(resp.bodyAsText()), ageMs = secondsHeader(resp, "Age") ?: 0L)
            // Пул ещё не проиндексирован — это «свечей нет», а не сбой: перепроверим позже, а не каждый цикл.
            404 -> CandleFetch.Ok(emptyList())
            429 -> CandleFetch.RateLimited(secondsHeader(resp, "Retry-After"))
            401, 403 -> if (withKey) CandleFetch.Unauthorized else CandleFetch.Failed("HTTP $code")
            else -> CandleFetch.Failed("HTTP $code")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("GeckoTerminal error (${key.pool}/${key.timeframe}): ${e.message}")
        CandleFetch.Failed(e.message ?: "ошибка сети")
    }

    /** Заголовок с числом секунд (Age, Retry-After) в миллисекундах; null — заголовка нет или он не число. */
    private fun secondsHeader(resp: HttpResponse, name: String): Long? =
        resp.headers[name]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }

    /**
     * Запрос вне [CandleStore] (история для бэктеста, списки пулов) — через ту же очередь, чтобы бэктест
     * не отбирал лимит у работающего бота. Возвращает HTTP-код и тело (тело — только при 200);
     * 429 — в том числе «очередь слишком длинная», 0 — сетевая ошибка.
     */
    private suspend fun queued(path: String, maxWaitMs: Long, what: String): Pair<Int, String?> {
        if (!limiter.acquire(maxWaitMs)) return 429 to null
        return try {
            val (resp, withKey) = request(path)
            val code = resp.status.value
            if (withKey) onKeyedResponse(code)
            when {
                code == 429 -> limiter.pauseUntil(
                    Clock.System.now().toEpochMilliseconds() + (secondsHeader(resp, "Retry-After") ?: CandleStore.RATE_LIMIT_PAUSE_MS)
                )
                (code == 401 || code == 403) && withKey -> rejectKey()
            }
            code to (if (code == 200) resp.bodyAsText() else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("GeckoTerminal $what error: ${e.message}")
            0 to null
        }
    }

    /**
     * Одна страница истории свечей БЕЗ кэша — для бэктеста (пагинация назад по времени).
     * @param beforeTimestampSec вернуть свечи строго раньше этого момента (сек), null — самые свежие.
     * @return свечи по возрастанию времени + HTTP-код (429 — превышен лимит, 0 — сетевая ошибка).
     */
    suspend fun getCandlesPage(
        poolAddress: String,
        timeframe: String,
        aggregate: Int,
        limit: Int = 1000,
        beforeTimestampSec: Long? = null,
        network: String = "solana",
    ): OhlcvPage {
        val before = beforeTimestampSec?.let { "&before_timestamp=$it" } ?: ""
        val (status, body) = queued(
            "/networks/$network/pools/$poolAddress/ohlcv/$timeframe?aggregate=$aggregate&limit=$limit&currency=usd$before",
            HISTORY_MAX_WAIT_MS,
            "page ($poolAddress/$timeframe)",
        )
        return OhlcvPage(body?.let { parse(it) } ?: emptyList(), status)
    }

    /**
     * Страница списка пулов сети (trending / new / top по объёму) — монеты для бэктеста и сканера.
     * @param duration окно трендовых пулов: "5m" | "1h" | "6h" | "24h"; null — по умолчанию сервера (24h).
     * @param maxWaitMs сколько ждать очереди к API (сканеру долго ждать незачем — возьмёт список в другой раз).
     * @return пулы + HTTP-код (429 — превышен лимит, 0 — сетевая ошибка).
     */
    suspend fun getPools(
        list: GtPoolList,
        page: Int,
        network: String = "solana",
        duration: String? = null,
        maxWaitMs: Long = HISTORY_MAX_WAIT_MS,
    ): Pair<List<GtPool>, Int> {
        val window = duration?.takeIf { list == GtPoolList.TRENDING }?.let { "&duration=$it" } ?: ""
        val (status, body) = queued("/networks/$network/${list.path}?page=$page$window", maxWaitMs, "pools (${list.path})")
        return (body?.let { parsePools(it) } ?: emptyList()) to status
    }

    private fun parsePools(body: String): List<GtPool> = runCatching {
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        data.mapNotNull { el ->
            val obj = el.jsonObject
            val a = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            fun str(key: String) = a[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.takeIf { it != "null" }
            val rel = obj["relationships"]?.jsonObject
            fun relId(key: String) = rel?.get(key)?.jsonObject?.get("data")?.jsonObject
                ?.get("id")?.jsonPrimitive?.content
            GtPool(
                address = str("address") ?: return@mapNotNull null,
                name = str("name") ?: "",
                // id вида "solana_<mint>" — отрезаем префикс сети.
                baseMint = relId("base_token")?.substringAfter('_') ?: "",
                quoteMint = relId("quote_token")?.substringAfter('_') ?: "",
                dexId = relId("dex") ?: "",
                priceUsd = str("base_token_price_usd")?.toDoubleOrNull() ?: 0.0,
                fdvUsd = str("fdv_usd")?.toDoubleOrNull() ?: 0.0,
                marketCapUsd = str("market_cap_usd")?.toDoubleOrNull(),
                reserveUsd = str("reserve_in_usd")?.toDoubleOrNull() ?: 0.0,
                createdAtMs = str("pool_created_at")
                    ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L,
            )
        }
    }.getOrElse {
        println("GeckoTerminal pools parse error: ${it.message}")
        emptyList()
    }

    private fun parse(body: String): List<Candle> {
        return try {
            val list = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("attributes")?.jsonObject
                ?.get("ohlcv_list")?.jsonArray ?: return emptyList()

            val out = ArrayList<Candle>(list.size)
            for (el in list) {
                val a = runCatching { el.jsonArray }.getOrNull() ?: continue
                if (a.size < 6) continue
                val ts = a[0].jsonPrimitive.content.toDoubleOrNull()?.toLong() ?: continue
                val open = a[1].jsonPrimitive.content.toDoubleOrNull() ?: continue
                val high = a[2].jsonPrimitive.content.toDoubleOrNull() ?: continue
                val low = a[3].jsonPrimitive.content.toDoubleOrNull() ?: continue
                val close = a[4].jsonPrimitive.content.toDoubleOrNull() ?: continue
                val volume = a[5].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                out.add(
                    Candle(
                        openTimeMs = if (ts < 1_000_000_000_000L) ts * 1000 else ts,
                        open = open, high = high, low = low, close = close, volume = volume,
                    )
                )
            }
            // GeckoTerminal отдаёт новые→старые; нам нужен хронологический порядок.
            out.sortBy { it.openTimeMs }
            out
        } catch (e: Exception) {
            println("GeckoTerminal parse error: ${e.message}")
            emptyList()
        }
    }
}
