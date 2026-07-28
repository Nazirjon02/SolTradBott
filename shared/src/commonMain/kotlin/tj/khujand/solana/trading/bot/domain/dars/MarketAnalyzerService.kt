package tj.khujand.solana.trading.bot.domain.dars

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * Сетевая обёртка над [MarketAnalysis] (аналог MarketAnalyzerService из MRX): тянет свечи
 * рабочего и старшего ТФ с GeckoTerminal, берёт рыночный фон из строки сканера и отдаёт
 * отчёты по монетам, отсортированные по баллу (лучшая — первой).
 *
 * @param candleSource внедряемый источник свечей (по умолчанию GeckoTerminal) — удобно для тестов.
 */
class MarketAnalyzerService(
    private val candleSource: suspend (pool: String, timeframe: String, aggregate: Int, limit: Int) -> List<Candle> =
        { pool, tf, agg, limit -> GeckoTerminalApi.getCandles(pool, tf, agg, limit) },
) {

    /** Разбор одной монеты. Свечи тянутся параллельно; при недоступности сети список пуст. */
    suspend fun analyze(candidate: TokenCandidate, cfg: DarsConfig): CoinAnalysis = coroutineScope {
        val pool = candidate.pairAddress
        val etfJob = async { candleSource(pool, cfg.entryTf, cfg.entryTfAggregate, cfg.candleLimit) }
        val htfJob = async {
            if (cfg.useTrendLevels || cfg.requireHtfTrend)
                candleSource(pool, cfg.higherTf, cfg.higherTfAggregate, cfg.candleLimit)
            else emptyList()
        }
        MarketAnalysis.analyze(
            symbol = candidate.symbol.ifBlank { candidate.name }.ifBlank { "?" },
            candles = etfJob.await(),
            trendCandles = htfJob.await(),
            market = candidate.toMarketContext(),
            cfg = cfg,
        )
    }

    /**
     * Разбирает список кандидатов сканера и возвращает отчёты, отсортированные по баллу.
     *
     * Монеты считаются небольшими пачками ([concurrency]): GeckoTerminal ограничен ~30 req/min,
     * а каждый разбор — это 2 запроса свечей, поэтому параллелизм держим скромным. Один сбойный
     * пул не должен ронять весь скан — упавшие монеты просто пропускаются.
     */
    suspend fun analyzeAll(
        candidates: List<TokenCandidate>,
        cfg: DarsConfig,
        concurrency: Int = 3,
        onProgress: (suspend (done: Int, total: Int) -> Unit)? = null,
    ): List<CoinAnalysis> = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope emptyList()
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        val lock = Mutex()
        var done = 0
        val total = candidates.size

        candidates.map { candidate ->
            async {
                val report = gate.withPermit {
                    try {
                        analyze(candidate, cfg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                lock.withLock {
                    done++
                    onProgress?.invoke(done, total)
                }
                report
            }
        }.awaitAll()
            .filterNotNull()
            .sortedByDescending { it.score }
    }
}

/** Рыночный фон для анализатора из строки кеша сканера (метрики DexScreener). */
fun TokenCandidate.toMarketContext() = MarketContext(
    priceUsd = priceUsd,
    changeM5Percent = priceChangeM5,
    changeH1Percent = priceChangeH1,
    liquidityUsd = liquidityUsd,
    marketCap = marketCap,
    volumeH1Usd = volumeH1Usd,
    buysH1 = buysH1,
    sellsH1 = sellsH1,
    tokenAgeMinutes = tokenAgeMinutes,
)
