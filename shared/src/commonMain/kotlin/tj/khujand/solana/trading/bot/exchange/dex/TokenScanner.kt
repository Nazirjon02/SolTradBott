package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Фильтры сканера — подмножество колонок таблицы strategy.
 * Каждая стратегия сканирует со своими фильтрами.
 */
data class ScanFilters(
    val minLiquidityUsd: Double = 10_000.0,
    val minMarketCap: Double = 50_000.0,
    val maxMarketCap: Double = 10_000_000.0,
    val minTokenAgeMinutes: Long = 30,
    val maxTokenAgeMinutes: Long = 43_200,
    val maxCandidates: Int = 10,
    /**
     * Master-выключатель фильтров ликвидность/MC/возраст. false → жёсткий отбор не применяется
     * (стратегия «Вход по баллу» без фильтров). Грубый отсев пула ([scanRejection]) и cap [maxCandidates]
     * остаются в силе.
     */
    val filtersEnabled: Boolean = true,
) {
    fun passes(c: TokenCandidate): Boolean = !filtersEnabled || (
        c.liquidityUsd >= minLiquidityUsd &&
            c.marketCap in minMarketCap..maxMarketCap &&
            c.tokenAgeMinutes in minTokenAgeMinutes..maxTokenAgeMinutes
        )
}

/**
 * Ответ на пакетный запрос пар монет.
 * @param pairs найденные пары (лучшая пара каждой монеты).
 * @param answered монеты, по которым источник ответил: если монеты из [answered] нет среди [pairs], пары у
 *   неё больше нет; монет вне [answered] просто не удалось спросить (сбой сети) — о них ничего не известно.
 */
data class PairsResult(val pairs: List<TokenPair>, val answered: Set<String>)

/** Откуда сканер берёт монеты и их метрики. Интерфейс — чтобы тесты подставляли ответы без сети. */
interface ScanSources {
    /** Адреса монет (mint), которые источники показывают сейчас. Сбой источника — он просто ничего не даёт. */
    suspend fun discoverMints(): List<String>

    /** Лучшие пары этих монет со свежими метриками. */
    suspend fun pairsFor(mints: List<String>): PairsResult
}

/**
 * Боевые источники:
 *  - реклама DexScreener — свежие бусты и профили по очереди (платное размещение: монету продвигают);
 *  - трендовые пулы GeckoTerminal за час — монеты, которыми торгуют прямо сейчас, без оплаты за показ.
 * Метрики всех монет — пакетным запросом DexScreener (до 30 адресов за раз).
 */
class DexScanSources(private val client: DexClient) : ScanSources {
    // Меняется только из TokenScanner.scan, а он идёт под замком — гонки нет.
    private var boostsNext = true

    override suspend fun discoverMints(): List<String> {
        val found = LinkedHashSet<String>()
        val boostsFirst = boostsNext
        boostsNext = !boostsNext
        val ds = client.dexScreener
        found += if (boostsFirst) ds.latestBoostMints().ifEmpty { ds.latestProfileMints() }
        else ds.latestProfileMints().ifEmpty { ds.latestBoostMints() }

        // Очередь к GeckoTerminal общая со свечами: долго ждать не стоит — список возьмём в следующий раз.
        val (pools, _) = GeckoTerminalApi.getPools(
            GtPoolList.TRENDING, page = 1, duration = TRENDING_WINDOW, maxWaitMs = TRENDING_MAX_WAIT_MS,
        )
        pools.mapNotNullTo(found) { it.baseMint.takeIf { mint -> mint.isNotBlank() } }
        return found.toList()
    }

    override suspend fun pairsFor(mints: List<String>): PairsResult = client.dexScreener.getBestPairs("solana", mints)

    private companion object {
        const val TRENDING_WINDOW = "1h"
        const val TRENDING_MAX_WAIT_MS = 20_000L
    }
}

/**
 * Сканер монет: общий пул наблюдения для всех стратегий.
 *
 *  - Раз в [DISCOVERY_INTERVAL_MS] источники ([ScanSources]) дают новые монеты; монета остаётся в пуле
 *    [RETENTION_MS] после того, как источник показал её в последний раз. Раньше пул жил 5 минут и целиком
 *    менялся при смене источника — «почти готовая» монета пропадала из-под стратегии.
 *  - Не реже чем раз в [REFRESH_INTERVAL_MS] метрики всего пула (цена, ликвидность, объём) обновляются
 *    одним-двумя пакетными запросами. Раньше стратегии видели цену до 5 минут давности.
 *  - Каждая стратегия получает из пула монеты под СВОИ фильтры — лучшие по баллу, не больше
 *    [ScanFilters.maxCandidates]. Раньше список обрезался по дате создания, а по баллу сортировался уже после.
 *  - Монета, чей пул осушили или которой больше нет у источника метрик, выбывает сразу ([scanRejection]).
 *
 * Пул публикуется в [TokenCache] (UI, Telegram, сервер читают его оттуда) и после перезапуска поднимается из него.
 */
class TokenScanner(
    private val cache: TokenCache,
    private val sources: ScanSources,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(client: DexClient, cache: TokenCache) : this(cache, DexScanSources(client))

    /** @param lastSeenAt когда источник показывал монету в последний раз — от него идёт срок наблюдения. */
    private class Member(var candidate: TokenCandidate, var lastSeenAt: Long)

    // Сканируют несколько стратегий параллельно: пул и запросы к источникам — под одним замком.
    private val lock = Mutex()
    private val pool = LinkedHashMap<String, Member>()
    private var lastDiscoveryAt = 0L
    private var lastRefreshAt = 0L
    private var restored = false

    /**
     * Кандидаты под фильтры стратегии, лучшие по баллу первыми.
     * @param force true — опросить источники сейчас, не дожидаясь срока.
     * @param exclude монеты, которые стратегии не нужны (уже торговались, покупка в полёте). В число лучших они не
     *   входят — их места достаются следующим по баллу. Раньше такие монеты занимали места в десятке и отбрасывались
     *   уже после отбора: из десяти кандидатов стратегия разбирала шесть.
     */
    suspend fun scan(filters: ScanFilters, force: Boolean = false, exclude: Set<String> = emptySet()): List<TokenCandidate> = lock.withLock {
        val now = nowMs()
        restoreOnce(now)
        try {
            // Пустой пул (источники ничего годного не дали) переспрашиваем чаще обычного, но не на каждый скан:
            // иначе три стратегии дёргали бы источники каждые десять секунд.
            val discoveryEvery = if (pool.isEmpty()) DISCOVERY_RETRY_MS else DISCOVERY_INTERVAL_MS
            when {
                force || now - lastDiscoveryAt >= discoveryEvery -> discover(now)
                now - lastRefreshAt >= REFRESH_INTERVAL_MS -> refresh(now, emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Сбой источника не должен ронять цикл стратегии: работаем с тем, что уже в пуле.
            println("TokenScanner: ошибка обновления пула — ${e.message}")
        }
        pool.values.map { it.candidate }
            .filter { it.mint !in exclude && filters.passes(it) }
            .sortedByDescending { it.score }
            .take(filters.maxCandidates.coerceAtLeast(1))
    }

    /** После перезапуска пул поднимается из БД — список наблюдения не начинается с нуля. */
    private fun restoreOnce(now: Long) {
        if (restored) return
        restored = true
        cache.all().filter { now - it.scannedAt <= RETENTION_MS }.forEach { pool[it.mint] = Member(it, it.scannedAt) }
    }

    /** Опрос источников: уже известным монетам продлевается срок, новые проходят отсев вместе с обновлением пула. */
    private suspend fun discover(now: Long) {
        lastDiscoveryAt = now
        val found = sources.discoverMints().filter { it !in MAJOR_MINTS }.distinct()
        found.forEach { pool[it]?.lastSeenAt = now }
        val fresh = found.filter { it !in pool }
        val asked = refresh(now, fresh)
        // Новые монеты были, а источник метрик не ответил ни по одной — попробуем скоро, а не через 5 минут.
        if (fresh.isNotEmpty() && !asked) lastDiscoveryAt = now - DISCOVERY_INTERVAL_MS + DISCOVERY_RETRY_MS
    }

    /**
     * Свежие метрики пула и новых монет [fresh] одним проходом.
     * @return false — источник метрик не ответил вовсе (пул остался как был).
     */
    private suspend fun refresh(now: Long, fresh: List<String>): Boolean {
        lastRefreshAt = now
        val mints = pool.keys.toList() + fresh
        if (mints.isEmpty()) return true
        val result = sources.pairsFor(mints)
        if (result.answered.isEmpty()) return false

        // Только пары, где монета — базовая: в выдаче бывают пулы, где она котируемая, и там цена и метрики чужие.
        val best = HashMap<String, TokenPair>()
        for (pair in result.pairs) {
            val mint = pair.baseToken?.address ?: continue
            if (pair.chainId != "solana") continue
            val current = best[mint]
            if (current == null || (pair.liquidity?.usd ?: 0.0) > (current.liquidity?.usd ?: 0.0)) best[mint] = pair
        }

        val removed = ArrayList<String>()
        for (mint in mints) {
            if (mint !in result.answered) continue // не спросили (сбой сети) — монету не трогаем
            val pair = best[mint]
            // Без даты создания пары возраст не проверить — такие монеты сканер не берёт.
            val candidate = pair?.takeIf { it.pairCreatedAt != null && scanRejection(it) == null }?.toTokenCandidate(now)
            if (candidate == null) {
                if (pool.remove(mint) != null) removed += mint
                continue
            }
            val member = pool[mint]
            if (member == null) pool[mint] = Member(candidate, now) else member.candidate = candidate
        }

        // Срок наблюдения вышел.
        val expired = pool.filterValues { now - it.lastSeenAt > RETENTION_MS }.keys.toList()
        expired.forEach { pool.remove(it) }
        removed += expired

        // Потолок пула: дольше всех не показывавшиеся источником (а среди них — слабейшие по баллу) уходят первыми.
        if (pool.size > POOL_LIMIT) {
            val overflow = pool.entries
                .sortedWith(compareBy<Map.Entry<String, Member>> { it.value.lastSeenAt }.thenBy { it.value.candidate.score })
                .take(pool.size - POOL_LIMIT)
                .map { it.key }
            overflow.forEach { pool.remove(it) }
            removed += overflow
        }

        cache.remove(removed)
        cache.put(pool.values.map { it.candidate })
        cache.cleanup()
        return true
    }

    companion object {
        /** Как часто опрашиваются источники новых монет. */
        const val DISCOVERY_INTERVAL_MS = 5 * 60_000L

        /** Метрики пула не старше этого: стратегии сканируют раз в ~30 с, со сдвигом друг от друга. */
        const val REFRESH_INTERVAL_MS = 20_000L

        /** Сколько монета остаётся под наблюдением после последнего показа источником. */
        const val RETENTION_MS = 2 * 60 * 60_000L

        /** Потолок пула: два пакетных запроса метрик на обновление. */
        const val POOL_LIMIT = 60

        /** Повтор опроса источников, когда прошлый ничего не дал (пул пуст или метрики не ответили). */
        private const val DISCOVERY_RETRY_MS = 60_000L

        /** SOL, USDC, USDT — не мемкоины: в трендовых пулах они бывают базовой монетой. */
        private val MAJOR_MINTS = setOf(
            "So11111111111111111111111111111111111111112",
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
        )
    }
}

/** Пул с ликвидностью ниже этого сканеру не нужен: он пуст или уже осушён. */
internal const val MIN_POOL_LIQUIDITY_USD = 3_000.0

/** Капитализация больше стольких ликвидностей — из пула не выйти без огромного слиппеджа. */
private const val MAX_FDV_TO_LIQUIDITY = 100.0

/** Продаж за сутки больше покупок во столько раз — монету сливают. */
private const val MAX_SELLS_TO_BUYS_H24 = 3.0

/**
 * Грубый отсев пула (чистая функция): причина, по которой монету не стоит показывать стратегиям, или null.
 *
 * Объём, во много раз превышающий ликвидность, и ноль покупок за последние 5 минут причиной отказа НЕ
 * являются. Первое — обычное дело для монеты в первые часы жизни, второе — для монеты в накоплении, которое
 * стратегии и ищут. Раньше оба правила были, и сканер не показывал ни самые активные монеты, ни самые спокойные.
 */
internal fun scanRejection(pair: TokenPair): String? {
    val liquidity = pair.liquidity?.usd ?: return "нет данных о ликвидности"
    val valuation = pair.fdv ?: pair.marketCap ?: return "нет данных о капитализации"
    if (liquidity < MIN_POOL_LIQUIDITY_USD) return "ликвидность пула ниже $${MIN_POOL_LIQUIDITY_USD.toInt()}"
    if (valuation / liquidity > MAX_FDV_TO_LIQUIDITY) return "капитализация больше ${MAX_FDV_TO_LIQUIDITY.toInt()} ликвидностей"
    val buys = pair.txns?.h24?.buys ?: 0
    val sells = pair.txns?.h24?.sells ?: 0
    if (buys > 0 && sells.toDouble() / buys > MAX_SELLS_TO_BUYS_H24) return "продаж за сутки втрое больше покупок"
    return null
}

/**
 * Пара DexScreener → кандидат в формате кеша сканера. Общая для сканера и поиска монеты
 * на «Анализе», чтобы найденная вручную монета разбиралась на тех же метриках.
 *
 * null — у пары нет mint или адреса пула. Нет даты создания — возраст 0.
 */
fun TokenPair.toTokenCandidate(now: Long): TokenCandidate? {
    val mint = baseToken?.address ?: return null
    val pool = pairAddress ?: return null
    val ageMinutes = pairCreatedAt?.let { ((now - normalizeCreatedAt(it)) / 60_000L).coerceAtLeast(0) } ?: 0L
    val liquidityUsd = liquidity?.usd ?: 0.0
    val volumeH1 = volume?.h1 ?: 0.0
    val buysH1 = txns?.h1?.buys ?: 0
    val sellsH1 = txns?.h1?.sells ?: 0
    val ratio = if (sellsH1 == 0) buysH1.toDouble() else buysH1.toDouble() / sellsH1
    return TokenCandidate(
        mint = mint,
        symbol = baseToken.symbol ?: "?",
        name = baseToken.name ?: "",
        pairAddress = pool,
        dexId = dexId ?: "",
        priceUsd = priceUsd?.toDoubleOrNull() ?: 0.0,
        liquidityUsd = liquidityUsd,
        marketCap = marketCap ?: fdv ?: 0.0,
        volumeH1Usd = volumeH1,
        buysH1 = buysH1,
        sellsH1 = sellsH1,
        priceChangeM5 = priceChange?.m5 ?: 0.0,
        priceChangeH1 = priceChange?.h1 ?: 0.0,
        tokenAgeMinutes = ageMinutes,
        score = computeScanScore(liquidityUsd, volumeH1, ratio, priceChange?.h1 ?: 0.0),
        scannedAt = now,
    )
}

/** DexScreener иногда отдаёт createdAt в секундах — нормализуем в миллисекунды. */
private fun normalizeCreatedAt(value: Long): Long =
    if (value < 100_000_000_000L) value * 1000 else value

/**
 * Скоринг кандидата 0..100: ликвидность + объём + давление покупок + стабильность.
 *
 * Урок 4: НЕ поощряем памп. Резкое изменение цены за час (в любую сторону) СНИЖАЕТ балл —
 * предпочитаем токены в накоплении/консолидации, а не на вершине V-разворота. Раньше здесь
 * был momentumScore, который, наоборот, тянул наверх уже пампнутые монеты.
 *
 * @param changeH1 изменение цены за час в процентах (например 50.0 = +50%).
 */
internal fun computeScanScore(
    liquidity: Double,
    volumeH1: Double,
    buySellRatio: Double,
    changeH1: Double,
): Double {
    val liqScore = (liquidity / 100_000.0).coerceAtMost(1.0) * 25
    val volScore = (volumeH1 / 50_000.0).coerceAtMost(1.0) * 25
    val ratioScore = ((buySellRatio - 1.0) / 2.0).coerceIn(0.0, 1.0) * 25
    val stabilityScore = (1.0 - abs(changeH1) / 100.0).coerceIn(0.0, 1.0) * 25
    return liqScore + volScore + ratioScore + stabilityScore
}
