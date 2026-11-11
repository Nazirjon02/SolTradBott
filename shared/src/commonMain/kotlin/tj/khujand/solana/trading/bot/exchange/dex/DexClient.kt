package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.time.Clock

/**
 * Сколько живёт цена SOL в кеше. Без ключа Jupiter даёт 0.5 запроса/с на все свои API сразу,
 * а цену SOL спрашивают и проверка входа, и исполнение, и обновление баланса.
 */
private const val SOL_PRICE_TTL_MS = 30_000L

/**
 * Единый фасад DEX-слоя (аналог BybitClient в MRX).
 *
 * Собирает все внешние API Solana-экосистемы в одну точку, чтобы движок,
 * стратегии и монитор сделок не знали про конкретные HTTP-клиенты:
 *  - DexScreener — цены, пары, поиск новых токенов;
 *  - Jupiter     — котировки и свопы (REAL-режим);
 *  - GeckoTerminal — OHLCV-свечи;
 *  - Solana RPC  — баланс кошелька, отправка/подтверждение транзакций.
 */
class DexClient(
    rpcUrl: String = "https://api.mainnet-beta.solana.com",
) {
    val dexScreener = DexScreenerApi()
    val jupiter = JupiterApi()
    val rpc = SolanaRpcClient(rpcUrl = rpcUrl)

    // ─── Цены ────────────────────────────────────────────────────────────────

    suspend fun getTokenPriceUsd(mint: String): Double? =
        dexScreener.getTokenPriceUsd("solana", mint)

    // Гонка двух одновременных запросов безвредна: оба положат свежую цену.
    private var solPriceCache: Pair<Double, Long>? = null

    /** Цена SOL в USD (Jupiter) с кешем на [SOL_PRICE_TTL_MS]; null — Jupiter не ответил. */
    suspend fun getSolPriceUsd(): Double? {
        val now = Clock.System.now().toEpochMilliseconds()
        solPriceCache?.let { (price, at) -> if (now - at < SOL_PRICE_TTL_MS) return price }
        val fresh = jupiter.getSolPriceUsd() ?: return null
        solPriceCache = fresh to now
        return fresh
    }

    suspend fun getPairDetails(pairAddress: String): TokenPair? =
        dexScreener.getTokenDetails(pairAddress)

    // ─── Свечи (OHLCV) ───────────────────────────────────────────────────────

    /**
     * Закрытые бары пула по возрастанию времени: стратегии решают по закрытым свечам, а один ответ
     * GeckoTerminal служит до закрытия следующего бара (см. [CandleStore]).
     * @param timeframe строка вида "1m", "5m", "15m", "1h", "4h", "1d" —
     * разворачивается в пару (timeframe, aggregate) GeckoTerminal.
     * @param live график сделки: с незакрытым баром и обновлением раз в 30 с (только с ключом CoinGecko,
     *   без него — те же закрытые бары).
     */
    suspend fun getCandles(
        poolAddress: String,
        timeframe: String,
        limit: Int = 200,
        live: Boolean = false,
    ): List<Candle> {
        val (tf, aggregate) = parseTimeframe(timeframe)
        return GeckoTerminalApi.getCandles(poolAddress, tf, aggregate, limit, live)
    }

    // ─── Кошелёк ─────────────────────────────────────────────────────────────

    suspend fun getSolBalance(publicKey: String): Double? =
        rpc.getBalanceLamports(publicKey)?.let { it / 1_000_000_000.0 }

    fun close() {
        dexScreener.close()
        jupiter.close()
        rpc.close()
    }

    companion object {
        /** "1m" → minute/1, "5m" → minute/5, "15m" → minute/15, "1h" → hour/1, "4h" → hour/4, "1d" → day/1. */
        fun parseTimeframe(timeframe: String): Pair<String, Int> {
            val normalized = timeframe.trim().lowercase()
            val value = normalized.dropLast(1).toIntOrNull() ?: 1
            return when {
                normalized.endsWith("m") -> "minute" to value
                normalized.endsWith("h") -> "hour" to value
                normalized.endsWith("d") -> "day" to value
                else -> "minute" to 1
            }
        }
    }
}
