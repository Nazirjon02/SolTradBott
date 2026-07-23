package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.time.Clock
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/**
 * Кандидат сканера — строка кеша токенов (аналог TopSymbolCache в MRX).
 * Хранится в таблице token_cache, чтобы сервер/UI/Telegram читали один список.
 */
data class TokenCandidate(
    val mint: String,
    val symbol: String,
    val name: String,
    val pairAddress: String,
    val dexId: String,
    val priceUsd: Double,
    val liquidityUsd: Double,
    val marketCap: Double,
    val volumeH1Usd: Double,
    val buysH1: Int,
    val sellsH1: Int,
    val priceChangeM5: Double,
    val priceChangeH1: Double,
    val tokenAgeMinutes: Long,
    val score: Double,
    val scannedAt: Long,
)

/**
 * Кеш кандидатов сканера в БД. TTL по умолчанию 5 минут:
 * пока записи свежие — сканер не дёргает DexScreener заново.
 */
class TokenCache(
    private val db: DrxDatabase,
    private val ttlMs: Long = 5 * 60_000L,
) {
    fun all(): List<TokenCandidate> =
        db.tokenCacheQueries.getAll().executeAsList().map { row ->
            TokenCandidate(
                mint = row.mint,
                symbol = row.symbol,
                name = row.name,
                pairAddress = row.pair_address,
                dexId = row.dex_id,
                priceUsd = row.price_usd,
                liquidityUsd = row.liquidity_usd,
                marketCap = row.market_cap,
                volumeH1Usd = row.volume_h1_usd,
                buysH1 = row.buys_h1.toInt(),
                sellsH1 = row.sells_h1.toInt(),
                priceChangeM5 = row.price_change_m5,
                priceChangeH1 = row.price_change_h1,
                tokenAgeMinutes = row.token_age_minutes,
                score = row.score,
                scannedAt = row.scanned_at,
            )
        }

    /** Свежие записи (не старше TTL). Пустой список = кеш протух, нужен рескан. */
    fun fresh(): List<TokenCandidate> {
        val cutoff = now() - ttlMs
        return all().filter { it.scannedAt >= cutoff }
    }

    fun put(candidates: List<TokenCandidate>) {
        candidates.forEach { c ->
            db.tokenCacheQueries.upsert(
                mint = c.mint,
                symbol = c.symbol,
                name = c.name,
                pair_address = c.pairAddress,
                dex_id = c.dexId,
                price_usd = c.priceUsd,
                liquidity_usd = c.liquidityUsd,
                market_cap = c.marketCap,
                volume_h1_usd = c.volumeH1Usd,
                buys_h1 = c.buysH1.toLong(),
                sells_h1 = c.sellsH1.toLong(),
                price_change_m5 = c.priceChangeM5,
                price_change_h1 = c.priceChangeH1,
                token_age_minutes = c.tokenAgeMinutes,
                score = c.score,
                scanned_at = c.scannedAt,
            )
        }
    }

    /** Чистка протухших записей (старше 24ч), чтобы кеш не рос бесконечно. */
    fun cleanup(maxAgeMs: Long = 24 * 60 * 60_000L) {
        db.tokenCacheQueries.deleteOlderThan(now() - maxAgeMs)
    }

    fun clear() = db.tokenCacheQueries.deleteAll()

    private fun now() = Clock.System.now().toEpochMilliseconds()
}
