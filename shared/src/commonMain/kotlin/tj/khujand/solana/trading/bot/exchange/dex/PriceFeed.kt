package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Сколько может быть старой цена для ВХОДА: кеш CDN плюс (у Jupiter) время от последней сделки монеты.
 * Старше — вход запрещён: стоп и тейк считаются от этой цены.
 */
const val ENTRY_MAX_PRICE_AGE_MS = 60_000L

/**
 * Сколько может быть старой цена для решений по ОТКРЫТОЙ позиции. Порог мягче, чем у входа: у монеты, которой
 * пару минут не торговали, последняя сделка и есть цена пула. Старше — монитор берёт следующий источник.
 */
const val EXIT_MAX_PRICE_AGE_MS = 120_000L

/** Jupiter сам держит ответ в CDN 5 с (`max-age=5`) — чаще спрашивать те же монеты бессмысленно. */
private const val JUPITER_CACHE_MS = 5_000L

/** Столько mint принимает Price API v3 в одном запросе (одно место — под SOL). */
private const val JUPITER_BATCH = 50

/** Предел кеша цен, чтобы за месяцы работы он не рос бесконечно. */
private const val PRICE_CACHE_LIMIT = 500

/**
 * Длина слота до первой калибровки — номинал Solana. Больше настоящей (замер 24.11.2026 — ~0,215 с), поэтому
 * до калибровки возраст цены Jupiter завышается: ошибка в сторону осторожности.
 */
private const val DEFAULT_SLOT_MS = 400.0

/** Калибруем длину слота по SOL не чаще, чем раз в это время: на коротком отрезке шум кеша CDN велик. */
private const val SLOT_CALIBRATION_MIN_MS = 20_000L

/** Откуда цена. */
enum class PriceSource(val label: String) {
    /** Jupiter Price API v3 — основной источник: отстаёт от сделок на ~5 с. */
    JUPITER("Jupiter"),

    /** DexScreener — запасной: кеш Cloudflare до 30 с плюс собственная задержка, всего ~45 с. */
    DEXSCREENER("DexScreener"),

    /** Котировка продажи Jupiter на размер позиции — последний запасной путь монитора, когда цен нет. */
    QUOTE("котировка Jupiter"),
}

/**
 * Цена монеты в USD с источником и возрастом.
 * @param fetchedAtMs когда получен ответ (часы бота).
 * @param dataAgeMs насколько данные были старыми уже в момент ответа: кеш CDN (заголовок Age), а у Jupiter ещё и
 *   время от последней сделки монеты до самого свежего слота того же ответа (blockId монеты против blockId SOL).
 *   Собственную задержку DexScreener (~30 с) снаружи не видно — у него это только возраст кеша.
 */
data class LivePrice(
    val priceUsd: Double,
    val source: PriceSource,
    val fetchedAtMs: Long,
    val dataAgeMs: Long = 0L,
) {
    /** Возраст цены сейчас: сколько она пролежала у нас плюс возраст данных в момент ответа. */
    fun ageMs(nowMs: Long): Long = (nowMs - fetchedAtMs).coerceAtLeast(0L) + dataAgeMs

    fun isFresh(nowMs: Long, maxAgeMs: Long): Boolean = priceUsd > 0.0 && priceUsd.isFinite() && ageMs(nowMs) <= maxAgeMs
}

/** Цена монеты в ответе Jupiter Price v3: [usdPrice] null — Jupiter монету знает, но цену не даёт (сочёл ненадёжной). */
data class JupiterTokenPrice(val usdPrice: Double?, val blockId: Long?)

/** Один ответ Jupiter Price v3 (пачкой): [cdnAgeMs] — заголовок Age, сколько ответ пролежал в CDN. */
data class JupiterPriceBatch(
    val tokens: Map<String, JupiterTokenPrice>,
    val cdnAgeMs: Long,
    val fetchedAtMs: Long,
)

/** Цена DexScreener: [ageMs] — возраст ответа в кеше Cloudflare (заголовок Age, `max-age=30`). */
data class DexScreenerPrice(
    val priceUsd: Double,
    val ageMs: Long,
    val fetchedAtMs: Long,
    val pairAddress: String? = null,
)

/** Пара DexScreener целиком (ликвидность для ликвидность-стопа, цена — запасная) и возраст ответа. */
data class PairSnapshot(val pair: TokenPair, val ageMs: Long, val fetchedAtMs: Long) {
    fun livePrice(): LivePrice? = pair.priceUsd?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
        ?.let { LivePrice(it, PriceSource.DEXSCREENER, fetchedAtMs, ageMs) }
}

/** Сетевые источники цены — интерфейсом, чтобы тесты подставляли ответы без сети. */
interface PriceSources {
    /** Цены пачкой; null — Jupiter не ответил (сеть, лимит, 5xx). */
    suspend fun jupiter(mints: List<String>): JupiterPriceBatch?

    /** Цена по самой ликвидной паре монеты; null — нет ответа или пары. */
    suspend fun dexScreener(mint: String): DexScreenerPrice?
}

/** Боевые источники: Jupiter Price v3 и DexScreener `tokens/v1`. */
class ApiPriceSources(private val jupiter: JupiterApi, private val dexScreener: DexScreenerApi) : PriceSources {
    override suspend fun jupiter(mints: List<String>): JupiterPriceBatch? = jupiter.getPriceBatch(mints)

    override suspend fun dexScreener(mint: String): DexScreenerPrice? = dexScreener.getTokenPriceQuote("solana", mint)
}

/**
 * Единый источник цены для входа, стопов, тейков и трейлинга: Jupiter — основной (отстаёт на ~5 с), DexScreener —
 * запасной (~45 с, замер 24.11.2026), если у Jupiter цены нет или она старая.
 *
 * Возраст цены Jupiter = возраст ответа в CDN + время от последней сделки монеты: в каждый запрос добавляется SOL,
 * им торгуют каждый слот, так что его blockId — самый свежий слот ответа, а разница в слотах с blockId монеты,
 * умноженная на длину слота, — сколько монетой не торговали. Длина слота калибруется по SOL между ответами.
 */
class PriceFeed(
    private val sources: PriceSources,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private val cache = HashMap<String, LivePrice>()
    private var slotMs = DEFAULT_SLOT_MS
    private var slotCalibrated = false
    private var solAnchor: Pair<Long, Long>? = null // слот SOL → время данных ответа, мс

    /** Текущая оценка длины слота, мс (для ленты и тестов). */
    suspend fun slotDurationMs(): Double = lock.withLock { slotMs }

    /**
     * Цены Jupiter одним запросом на всё (пачки по 49 монет + SOL), с кешем на [JUPITER_CACHE_MS]. Монеты, у
     * которых Jupiter цены не дал, в ответе нет. Сбой Jupiter — пустой ответ, не исключение.
     */
    suspend fun jupiterPrices(mints: Collection<String>): Map<String, LivePrice> {
        val wanted = mints.filter { it.isNotBlank() && it != SOL_MINT }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val now = nowMs()
        val out = HashMap<String, LivePrice>()
        val missing = ArrayList<String>()
        lock.withLock {
            for (m in wanted) {
                val cached = cache[m]
                if (cached != null && now - cached.fetchedAtMs < JUPITER_CACHE_MS) out[m] = cached else missing += m
            }
        }
        for (chunk in missing.chunked(JUPITER_BATCH - 1)) {
            val batch = sources.jupiter(chunk + SOL_MINT) ?: continue
            val fresh = lock.withLock { absorb(batch) }
            for (m in chunk) fresh[m]?.let { out[m] = it }
        }
        return out
    }

    /** Цена DexScreener по самой ликвидной паре монеты; null — нет ответа. */
    suspend fun dexScreenerPrice(mint: String): LivePrice? =
        sources.dexScreener(mint)?.takeIf { it.priceUsd > 0.0 && it.priceUsd.isFinite() }
            ?.let { LivePrice(it.priceUsd, PriceSource.DEXSCREENER, it.fetchedAtMs, it.ageMs) }

    /**
     * Лучшая цена монеты не старше [maxAgeMs]: Jupiter, а если его цены нет или она старая — DexScreener.
     * null — свежей цены нет ни там, ни там (вход в такой момент запрещён).
     */
    suspend fun freshPrice(mint: String, maxAgeMs: Long): LivePrice? {
        jupiterPrices(listOf(mint))[mint]?.takeIf { it.isFresh(nowMs(), maxAgeMs) }?.let { return it }
        return dexScreenerPrice(mint)?.takeIf { it.isFresh(nowMs(), maxAgeMs) }
    }

    /** То же для нескольких монет: Jupiter одним запросом, недостающие — DexScreener по одной. */
    suspend fun freshPrices(mints: Collection<String>, maxAgeMs: Long): Map<String, LivePrice> {
        val wanted = mints.distinct()
        val out = HashMap<String, LivePrice>()
        jupiterPrices(wanted).forEach { (m, p) -> if (p.isFresh(nowMs(), maxAgeMs)) out[m] = p }
        for (m in wanted) {
            if (m in out) continue
            dexScreenerPrice(m)?.takeIf { it.isFresh(nowMs(), maxAgeMs) }?.let { out[m] = it }
        }
        return out
    }

    /** Разбирает ответ Jupiter (под [lock]): калибровка слота по SOL, возраст каждой цены, кеш. */
    private fun absorb(batch: JupiterPriceBatch): Map<String, LivePrice> {
        // Момент, когда Jupiter собрал ответ: CDN мог продержать его до 5 с.
        val dataTimeMs = batch.fetchedAtMs - batch.cdnAgeMs
        val solSlot = batch.tokens[SOL_MINT]?.blockId
        if (solSlot != null) calibrate(solSlot, dataTimeMs)
        val result = HashMap<String, LivePrice>()
        for ((mint, p) in batch.tokens) {
            if (mint == SOL_MINT) continue
            val price = p.usdPrice?.takeIf { it > 0.0 && it.isFinite() } ?: continue
            val lagSlots = if (solSlot != null && p.blockId != null) (solSlot - p.blockId).coerceAtLeast(0L) else 0L
            val live = LivePrice(price, PriceSource.JUPITER, batch.fetchedAtMs, batch.cdnAgeMs + (lagSlots * slotMs).toLong())
            cache[mint] = live
            result[mint] = live
        }
        if (cache.size > PRICE_CACHE_LIMIT) cache.clear()
        return result
    }

    /** Длина слота по двум ответам: сколько слотов SOL прошло за время между ними. */
    private fun calibrate(solSlot: Long, atMs: Long) {
        val anchor = solAnchor
        if (anchor == null || solSlot < anchor.first) {
            solAnchor = solSlot to atMs
            return
        }
        val slots = solSlot - anchor.first
        val elapsed = atMs - anchor.second
        if (slots <= 0L || elapsed < SLOT_CALIBRATION_MIN_MS) return
        val measured = (elapsed.toDouble() / slots).coerceIn(100.0, 800.0)
        slotMs = if (slotCalibrated) slotMs * 0.7 + measured * 0.3 else measured
        slotCalibrated = true
        solAnchor = solSlot to atMs
    }
}
