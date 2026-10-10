package tj.khujand.solana.trading.bot.domain.dars

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import kotlin.time.Clock

/**
 * Ход скана: что уже посчитано и какие монеты сейчас лучшие.
 *
 * Разбор десятков монет из-за лимита GeckoTerminal занимает минуты, и всё это время держать
 * пустой экран нельзя — результаты отдаются по мере готовности, а не одним куском в конце.
 */
data class AnalysisProgress(
    /** Сколько монет уже разобрано (включая упавшие). */
    val done: Int,
    /** Сколько монет всего в скане. */
    val total: Int,
    /** Последняя разобранная монета — видно, что процесс живой. */
    val lastSymbol: String?,
    /** Разобранные монеты по убыванию балла. Пополняется по ходу скана. */
    val best: List<CoinAnalysis>,
    /** Сколько монет пропущено из-за ошибок сети. */
    val failed: Int,
    val finished: Boolean,
) {
    /** Доля выполненного, 0..1 — для полоски прогресса. */
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/**
 * Сетевая обёртка над [MarketAnalysis] (аналог MarketAnalyzerService из MRX): тянет свечи
 * рабочего и старшего ТФ с GeckoTerminal, берёт рыночный фон из строки сканера и отдаёт
 * отчёты по монетам, отсортированные по баллу (лучшая — первой).
 *
 * Последний скан живёт здесь, а не в экране ([scan]): экран вкладки пересоздаётся при каждом
 * переходе, а скан из-за лимита GeckoTerminal слишком дорогой, чтобы терять его и считать заново.
 *
 * @param candleSource внедряемый источник свечей (по умолчанию GeckoTerminal) — удобно для тестов.
 * @param resolver ищет монету вне сканера по mint или тикеру (DexScreener). null — не нашлась.
 */
class MarketAnalyzerService(
    private val resolver: suspend (query: String) -> TokenCandidate? = { null },
    private val candleSource: suspend (pool: String, timeframe: String, aggregate: Int, limit: Int) -> List<Candle> =
        { pool, tf, agg, limit -> GeckoTerminalApi.getCandles(pool, tf, agg, limit) },
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null

    private val _scan = MutableStateFlow<AnalysisProgress?>(null)

    /** Последний (или идущий сейчас) скан. null — скан ещё не запускали. */
    val scan: StateFlow<AnalysisProgress?> = _scan.asStateFlow()

    /**
     * Свечи последних разборов: график карточки берёт их отсюда и НЕ ходит в GeckoTerminal
     * заново. Ключ — mint: индексы разметки ([CoinAnalysis.chart]) — позиции именно в этом списке.
     * [MutableStateFlow], а не обычная мапа: пишут параллельные корутины скана, читает UI-поток.
     */
    private val candleCache = MutableStateFlow<Map<String, CachedCandles>>(emptyMap())

    private data class CachedCandles(val candles: List<Candle>, val at: Long)

    private fun putCandles(mint: String, candles: List<Candle>) {
        if (mint.isBlank() || candles.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        candleCache.update { old ->
            // Заодно выбрасываем протухшее и держим потолок: иначе после нескольких сканов
            // «Все» в памяти лежали бы десятки тысяч свечей, из которых смотрят пару монет.
            val fresh = old.filterValues { now - it.at <= CANDLE_TTL_MS } + (mint to CachedCandles(candles, now))
            if (fresh.size <= MAX_CACHED) fresh
            else fresh.entries.sortedByDescending { it.value.at }.take(MAX_CACHED).associate { it.key to it.value }
        }
    }

    /**
     * Свечи, по которым считался последний разбор монеты, или null, если их нет или они устарели.
     * null — нормальная ситуация, график тогда просто не рисуется: подтягивать свечи ради
     * картинки значило бы тратить лимит GeckoTerminal на каждое открытие карточки.
     */
    fun cachedCandles(mint: String): List<Candle>? {
        val entry = candleCache.value[mint] ?: return null
        val age = Clock.System.now().toEpochMilliseconds() - entry.at
        return if (age <= CANDLE_TTL_MS) entry.candles else null
    }

    /**
     * Разбор одной монеты. Свечи тянутся параллельно; при недоступности сети список пуст.
     * В кэш графика не пишет: так разбор зовут стратегия «Вход по баллу» и бэктест — на каждом
     * баре, и картинка им не нужна.
     */
    suspend fun analyze(candidate: TokenCandidate, cfg: DarsConfig): CoinAnalysis = load(candidate, cfg).second

    /** Разбор для экрана: то же, что [analyze], плюс свечи в кэш графика карточки. */
    private suspend fun analyzeForChart(candidate: TokenCandidate, cfg: DarsConfig): CoinAnalysis {
        val (candles, report) = load(candidate, cfg)
        putCandles(candidate.mint, candles)
        return report
    }

    private suspend fun load(candidate: TokenCandidate, cfg: DarsConfig): Pair<List<Candle>, CoinAnalysis> =
        coroutineScope {
            val pool = candidate.pairAddress
            val etfJob = async { candleSource(pool, cfg.entryTf, cfg.entryTfAggregate, cfg.candleLimit) }
            val htfJob = async {
                if (cfg.useTrendLevels || cfg.requireHtfTrend)
                    candleSource(pool, cfg.higherTf, cfg.higherTfAggregate, cfg.candleLimit)
                else emptyList()
            }
            val candles = etfJob.await()
            candles to MarketAnalysis.analyze(
                symbol = candidate.symbol.ifBlank { candidate.name }.ifBlank { "?" },
                candles = candles,
                trendCandles = htfJob.await(),
                market = candidate.toMarketContext(),
                cfg = cfg,
                mint = candidate.mint,
                iconUrl = tokenIconUrl(candidate.mint),
            )
        }

    /**
     * Разбор монеты по вводу пользователя: mint или тикер. Сначала ищем среди [known]
     * (кандидаты сканера — с их метриками DexScreener), иначе спрашиваем [resolver].
     *
     * @return null — монета не найдена. Сетевые ошибки свечей не бросаются: разбор
     * с пустыми свечами честно скажет «недостаточно истории».
     */
    suspend fun analyzeQuery(query: String, cfg: DarsConfig, known: List<TokenCandidate>): CoinAnalysis? {
        val q = query.trim().removePrefix("$")
        if (q.isEmpty()) return null
        val candidate = known.firstOrNull { it.mint == q }
            ?: known.firstOrNull { it.symbol.equals(q, ignoreCase = true) }
            ?: resolver(q)
            ?: return null
        return analyzeForChart(candidate, cfg)
    }

    /**
     * Разбирает кандидатов сканера и отдаёт результаты по ходу разбора.
     *
     * Монеты считаются небольшими пачками ([concurrency]): GeckoTerminal ограничен ~30 req/min,
     * а каждый разбор — это 2 запроса свечей, поэтому параллелизм держим скромным. Один сбойный
     * пул не должен ронять весь скан — упавшие монеты считаются пропущенными.
     */
    fun analyzeStream(
        candidates: List<TokenCandidate>,
        cfg: DarsConfig,
        concurrency: Int = 3,
    ): Flow<AnalysisProgress> = channelFlow {
        if (candidates.isEmpty()) {
            send(AnalysisProgress(0, 0, null, emptyList(), 0, finished = true))
            return@channelFlow
        }
        // Первый кадр — до всякой сети: экран сразу знает, сколько монет впереди.
        send(AnalysisProgress(0, candidates.size, null, emptyList(), 0, finished = false))

        val gate = Semaphore(concurrency.coerceAtLeast(1))
        val lock = Mutex()
        val results = mutableListOf<CoinAnalysis>()
        var done = 0
        var failed = 0

        candidates.map { candidate ->
            launch {
                val report = gate.withPermit {
                    try {
                        analyzeForChart(candidate, cfg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                // Счётчики и выдача под общим замком: монеты финишируют вразнобой,
                // а кадр прогресса должен быть согласованным.
                lock.withLock {
                    done++
                    if (report != null) results += report else failed++
                    send(
                        AnalysisProgress(
                            done = done,
                            total = candidates.size,
                            lastSymbol = candidate.symbol.ifBlank { candidate.name },
                            best = results.sortedByDescending { it.score },
                            failed = failed,
                            finished = done == candidates.size,
                        )
                    )
                }
            }
        }.joinAll()
    }
        // Кадры прогресса летят чаще, чем нужно экрану, — важен только последний.
        .buffer(Channel.CONFLATED)
        .flowOn(Dispatchers.Default)

    /** Разбор одним куском — для вызовов, которым нечего показывать по ходу (тесты, сервер). */
    suspend fun analyzeAll(
        candidates: List<TokenCandidate>,
        cfg: DarsConfig,
        concurrency: Int = 3,
    ): List<CoinAnalysis> = analyzeStream(candidates, cfg, concurrency).last().best

    /**
     * Запускает скан в собственном scope сервиса и публикует ход в [scan]. Скан переживает
     * уход с вкладки «Анализ»; новый запуск отменяет предыдущий. Пока не пришла первая монета,
     * в [scan] остаются прошлые результаты — мигать пустотой на каждом обновлении незачем.
     */
    fun startScan(candidates: List<TokenCandidate>, cfg: DarsConfig, concurrency: Int = 3) {
        scanJob?.cancel()
        val previous = _scan.value?.best.orEmpty()
        scanJob = scope.launch {
            analyzeStream(candidates, cfg, concurrency).collect { p ->
                _scan.value = if (p.best.isEmpty() && !p.finished) p.copy(best = previous) else p
            }
        }
    }

    /** Гашение сервиса вместе с рантаймом. */
    fun close() {
        scope.cancel()
    }

    private companion object {
        /** Сколько живут свечи в кэше: дольше разбор уже не про «сейчас». */
        const val CANDLE_TTL_MS = 30 * 60_000L
        /** Потолок числа монет со свечами в памяти. */
        const val MAX_CACHED = 120
    }
}

/**
 * URL реальной иконки токена по адресу смарт-контракта (mint). Используем CDN DexScreener —
 * тот же, что рисует лого монет на самом сайте. Пусто/404 → в UI показываем запасную иконку.
 */
fun tokenIconUrl(mint: String): String? =
    if (mint.isBlank()) null else "https://dd.dexscreener.com/ds-data/tokens/solana/$mint.png"

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
