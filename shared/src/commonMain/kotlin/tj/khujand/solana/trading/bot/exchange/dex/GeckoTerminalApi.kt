package tj.khujand.solana.trading.bot.exchange.dex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
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

/**
 * Источник свечей OHLCV — GeckoTerminal (бесплатно, без ключа).
 * Ktor-клиент + ленивый разбор JSON.
 *
 * Rate limit ~30 req/min, поэтому есть in-memory кэш по ключу пул+ТФ+агрегат с TTL.
 */
object GeckoTerminalApi {

    private const val BASE = "https://api.geckoterminal.com/api/v2"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }

    private data class CacheEntry(val candles: List<Candle>, val expiresAtMs: Long)

    private val cache = mutableMapOf<String, CacheEntry>()
    private val mutex = Mutex()

    /**
     * Свечи для пула Solana.
     * @param poolAddress адрес пула (на Solana = DexScreener pairAddress).
     * @param timeframe "minute" | "hour" | "day".
     * @param aggregate агрегация внутри ТФ (minute: 1/5/15, hour: 1/4/12, day: 1).
     * @param limit сколько свечей запросить (макс. 1000).
     * @param ttlMs время жизни кэша.
     * Возвращает свечи, отсортированные по возрастанию времени. Пустой список = данных нет.
     */
    suspend fun getCandles(
        poolAddress: String,
        timeframe: String,
        aggregate: Int = 1,
        limit: Int = 200,
        ttlMs: Long = 30_000L,
        network: String = "solana",
    ): List<Candle> {
        if (poolAddress.isBlank()) return emptyList()
        val key = "$network|$poolAddress|$timeframe|$aggregate|$limit"
        val now = Clock.System.now().toEpochMilliseconds()

        mutex.withLock {
            cache[key]?.let { if (it.expiresAtMs > now) return it.candles }
        }

        val candles = fetch(network, poolAddress, timeframe, aggregate, limit)
        if (candles.isNotEmpty()) {
            mutex.withLock { cache[key] = CacheEntry(candles, now + ttlMs) }
        }
        return candles
    }

    private suspend fun fetch(
        network: String,
        pool: String,
        timeframe: String,
        aggregate: Int,
        limit: Int,
    ): List<Candle> {
        return try {
            val url = "$BASE/networks/$network/pools/$pool/ohlcv/$timeframe" +
                "?aggregate=$aggregate&limit=$limit&currency=usd"
            val resp: HttpResponse = client.get(url) { header("Accept", "application/json") }
            parse(resp.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("GeckoTerminal error ($pool/$timeframe): ${e.message}")
            emptyList()
        }
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
