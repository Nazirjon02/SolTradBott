package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException

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
)

/**
 * Сканер новых мемкоинов (аналог TopSymbolCache-скана в MRX, источник — DexScreener).
 *
 * Пайплайн: DexScreener (boosts/profiles) → фильтры стратегии → скоринг →
 * запись в TokenCache. Стратегии и UI читают кандидатов из кеша.
 */
class TokenScanner(
    private val client: DexClient,
    private val cache: TokenCache,
) {
    /**
     * Скан с фильтрами стратегии. Если кеш свежий — возвращает его без запросов к API.
     * @param force true = игнорировать TTL кеша и сканировать заново.
     */
    suspend fun scan(filters: ScanFilters, force: Boolean = false): List<TokenCandidate> {
        if (!force) {
            val cached = cache.fresh()
            if (cached.isNotEmpty()) return cached.filterBy(filters)
        }

        val now = Clock.System.now().toEpochMilliseconds()

        // Первичный отбор DexScreener — мягкие пороги, жёсткая фильтрация ниже.
        val legacySettings = FilterSettings(
            chains = listOf("solana"),
            liquidityMinUsd = filters.minLiquidityUsd.coerceAtMost(1_000.0),
            pairMaxAgeHours = filters.maxTokenAgeMinutes / 60.0,
            useVolumeH24 = false,
            useVolumeM5 = false,
            useMinBuysToSellsRatioM5 = false,
            useMinPriceChangeM5Pct = false,
            excludeRugPull = true,
            maxTokensPerTick = 30,
        )
        val pairs = try {
            client.dexScreener.getNewTokens(legacySettings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("TokenScanner: ошибка DexScreener — ${e.message}")
            return cache.fresh().filterBy(filters)
        }

        val candidates = mutableListOf<TokenCandidate>()
        for (pair in pairs) {
            val mint = pair.baseToken?.address ?: continue
            val pairAddress = pair.pairAddress ?: continue
            val ageMinutes = pair.pairCreatedAt?.let { created ->
                ((now - normalizeCreatedAt(created)) / 60_000L).coerceAtLeast(0)
            } ?: continue

            val liquidity = pair.liquidity?.usd ?: 0.0
            val marketCap = pair.marketCap ?: pair.fdv ?: 0.0
            val volumeH1 = pair.volume?.h1 ?: 0.0
            val buysH1 = pair.txns?.h1?.buys ?: 0
            val sellsH1 = pair.txns?.h1?.sells ?: 0
            val ratio = if (sellsH1 == 0) buysH1.toDouble() else buysH1.toDouble() / sellsH1

            val passes = liquidity >= filters.minLiquidityUsd &&
                marketCap in filters.minMarketCap..filters.maxMarketCap &&
                ageMinutes in filters.minTokenAgeMinutes..filters.maxTokenAgeMinutes
            if (!passes) continue

            candidates += TokenCandidate(
                mint = mint,
                symbol = pair.baseToken?.symbol ?: "?",
                name = pair.baseToken?.name ?: "",
                pairAddress = pairAddress,
                dexId = pair.dexId ?: "",
                priceUsd = pair.priceUsd?.toDoubleOrNull() ?: 0.0,
                liquidityUsd = liquidity,
                marketCap = marketCap,
                volumeH1Usd = volumeH1,
                buysH1 = buysH1,
                sellsH1 = sellsH1,
                priceChangeM5 = pair.priceChange?.m5 ?: 0.0,
                priceChangeH1 = pair.priceChange?.h1 ?: 0.0,
                tokenAgeMinutes = ageMinutes,
                score = computeScanScore(liquidity, volumeH1, ratio, pair.priceChange?.h1 ?: 0.0),
                scannedAt = now,
            )
            if (candidates.size >= filters.maxCandidates) break
        }

        val sorted = candidates.sortedByDescending { it.score }
        cache.put(sorted)
        cache.cleanup()
        return sorted
    }

    /**
     * Кеш token_cache общий для всех стратегий, а наполняет его та, что сканировала первой,
     * СВОИМИ фильтрами. Поэтому здесь повторно применяем фильтры вызывающей стратегии
     * (ликвидность, market cap, возраст) к уже накопленным кандидатам.
     */
    private fun List<TokenCandidate>.filterBy(f: ScanFilters): List<TokenCandidate> = filter { c ->
        c.liquidityUsd >= f.minLiquidityUsd &&
            c.marketCap in f.minMarketCap..f.maxMarketCap &&
            c.tokenAgeMinutes in f.minTokenAgeMinutes..f.maxTokenAgeMinutes
    }

    /** DexScreener иногда отдаёт createdAt в секундах — нормализуем в миллисекунды. */
    private fun normalizeCreatedAt(value: Long): Long =
        if (value < 100_000_000_000L) value * 1000 else value
}

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
