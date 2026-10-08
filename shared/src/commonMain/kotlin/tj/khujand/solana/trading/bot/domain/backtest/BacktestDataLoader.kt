package tj.khujand.solana.trading.bot.domain.backtest

import kotlinx.coroutines.delay
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.GtPool
import tj.khujand.solana.trading.bot.exchange.dex.GtPoolList
import tj.khujand.solana.trading.bot.exchange.dex.OhlcvPage

/**
 * Загрузка истории для бэктеста с GeckoTerminal: список монет (trending / new / top) и свечи
 * за N дней постранично назад (до 1000 свечей на запрос).
 *
 * Бесплатный лимит GeckoTerminal ~30 запросов в минуту: запросы идут не чаще [minRequestIntervalMs],
 * на ответ 429 — пауза [rateLimitBackoffMs] и повтор (до [MAX_RETRIES] раз).
 * Источники и часы инъектируются — для тестов без сети и ожидания.
 */
class BacktestDataLoader(
    private val pageSource: suspend (pool: String, tf: String, agg: Int, limit: Int, beforeSec: Long?) -> OhlcvPage =
        { pool, tf, agg, limit, before -> GeckoTerminalApi.getCandlesPage(pool, tf, agg, limit, before) },
    private val poolSource: suspend (list: GtPoolList, page: Int) -> Pair<List<GtPool>, Int> =
        { list, page -> GeckoTerminalApi.getPools(list, page) },
    private val minRequestIntervalMs: Long = 2_500L,
    private val rateLimitBackoffMs: Long = 30_000L,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var lastRequestAtMs = 0L

    /** Сколько запросов уйдёт на одну монету (для оценки времени в UI). */
    fun requestsPerCoin(entryTf: String, higherTf: String?, days: Int): Int {
        fun pages(tf: String) = ((days * 86_400_000L / timeframeMs(tf)) / PAGE_LIMIT + 1).toInt()
        return pages(entryTf) + (higherTf?.let { pages(it) } ?: 0)
    }

    /**
     * Монеты из списка GeckoTerminal: без мажоров (SOL/USDC/USDT как базовый токен), по одному пулу
     * на токен (первый в выдаче — самый ликвидный/трендовый).
     */
    suspend fun loadCoins(list: GtPoolList, count: Int): List<BacktestCoin> {
        val coins = LinkedHashMap<String, BacktestCoin>()
        var page = 1
        while (coins.size < count && page <= MAX_POOL_PAGES) {
            val (pools, status) = request({ it.second }) { poolSource(list, page) }
            if (status != 200 || pools.isEmpty()) break
            for (p in pools) {
                if (p.baseMint.isBlank() || p.baseMint in MAJOR_MINTS || p.baseMint in coins) continue
                coins[p.baseMint] = p.toCoin()
                if (coins.size >= count) break
            }
            page++
        }
        return coins.values.toList()
    }

    /** История монеты за [days] дней: рабочий ТФ и (если задан) старший ТФ. */
    suspend fun loadSeries(coin: BacktestCoin, entryTf: String, higherTf: String?, days: Int): CoinSeries {
        val cutoffMs = nowMs() - days * 86_400_000L
        return CoinSeries(
            coin = coin,
            candles = loadHistory(coin.pool, entryTf, cutoffMs),
            higherTf = higherTf?.let { loadHistory(coin.pool, it, cutoffMs) } ?: emptyList(),
        )
    }

    /** Свечи пула с момента [cutoffMs] до сейчас: страницы назад по before_timestamp. */
    internal suspend fun loadHistory(pool: String, timeframe: String, cutoffMs: Long): List<Candle> {
        val (tf, agg) = DexClient.parseTimeframe(timeframe)
        val byTime = HashMap<Long, Candle>()
        var beforeSec: Long? = null
        while (true) {
            val page = request({ it.status }) { pageSource(pool, tf, agg, PAGE_LIMIT, beforeSec) }
            if (page.status != 200 || page.candles.isEmpty()) break
            page.candles.forEach { byTime[it.openTimeMs] = it }
            val earliestMs = page.candles.first().openTimeMs
            // Страница неполная — история пула кончилась; дошли до начала окна — хватит.
            if (page.candles.size < PAGE_LIMIT || earliestMs <= cutoffMs) break
            val nextBefore = earliestMs / 1000
            if (nextBefore == beforeSec) break // защита от зацикливания
            beforeSec = nextBefore
        }
        return byTime.values.filter { it.openTimeMs >= cutoffMs }.sortedBy { it.openTimeMs }
    }

    /** Запрос с паузой между вызовами и повтором на 429 ([statusOf] достаёт HTTP-код из результата). */
    private suspend fun <T> request(statusOf: (T) -> Int, call: suspend () -> T): T {
        var attempt = 0
        while (true) {
            val wait = lastRequestAtMs + minRequestIntervalMs - nowMs()
            if (wait > 0) sleep(wait)
            lastRequestAtMs = nowMs()
            val result = call()
            if (statusOf(result) != 429 || attempt >= MAX_RETRIES) return result
            attempt++
            sleep(rateLimitBackoffMs)
        }
    }

    private fun GtPool.toCoin() = BacktestCoin(
        pool = address,
        mint = baseMint,
        symbol = name.substringBefore(" / ").trim().ifBlank { baseMint.take(6) },
        name = name,
        dexId = dexId,
        createdAtMs = createdAtMs,
        priceNow = priceUsd,
        marketCapNow = marketCapUsd ?: fdvUsd,
        liquidityNow = reserveUsd,
    )

    companion object {
        /** Максимум свечей в одном ответе GeckoTerminal. */
        const val PAGE_LIMIT = 1000

        private const val MAX_POOL_PAGES = 10
        private const val MAX_RETRIES = 3

        /** SOL, USDC, USDT — не мемкоины, в выборку не берём. */
        private val MAJOR_MINTS = setOf(
            "So11111111111111111111111111111111111111112",
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
        )
    }
}
