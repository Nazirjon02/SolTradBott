package tj.khujand.solana.trading.bot.domain.backtest

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.yield
import tj.khujand.solana.trading.bot.core.engine.ExitAction
import tj.khujand.solana.trading.bot.core.engine.PositionState
import tj.khujand.solana.trading.bot.core.engine.atrFraction
import tj.khujand.solana.trading.bot.core.engine.decideExit
import tj.khujand.solana.trading.bot.core.strategy.BreakoutPullbackStrategy
import tj.khujand.solana.trading.bot.core.strategy.DarsStrategy
import tj.khujand.solana.trading.bot.core.strategy.MAX_ENTRY_PRICE_DRIFT
import tj.khujand.solana.trading.bot.core.strategy.MIN_CONFIDENCE
import tj.khujand.solana.trading.bot.core.strategy.MomentumStrategy
import tj.khujand.solana.trading.bot.core.strategy.RsiEmaStrategy
import tj.khujand.solana.trading.bot.core.strategy.ScoreStrategy
import tj.khujand.solana.trading.bot.core.strategy.Strategy
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.core.strategy.rangeAllowsEntry
import tj.khujand.solana.trading.bot.domain.dars.DarsEntryEngine
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalyzerService
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * Свечи «на момент времени» для стратегий, которые сами запрашивают свечи (Dars, «Вход по баллу»,
 * старший ТФ «Пробой + откат»). Бэктест перед каждым баром кладёт сюда только уже закрытые свечи.
 */
class CandleSlices(private val entryTf: Pair<String, Int>) {
    var entry: List<Candle> = emptyList()
    var higher: List<Candle> = emptyList()

    fun forTimeframe(timeframe: String, aggregate: Int): List<Candle> =
        if (timeframe == entryTf.first && aggregate == entryTf.second) entry else higher
}

/** Старший ТФ, свечи которого нужны стратегии в бэктесте (null — не нужен). */
fun StrategyConfig.backtestHigherTf(): String? = when (type) {
    StrategyType.BREAKOUT_PULLBACK.name -> bpHigherTf.takeIf { bpRequireHtfTrend }
    StrategyType.DARS.name, StrategyType.SCORE.name -> darsHigherTf
    else -> null
}

/** Длительность свечи ТФ ("1m", "5m", "1h", …) в миллисекундах. */
fun timeframeMs(timeframe: String): Long {
    val (unit, value) = DexClient.parseTimeframe(timeframe)
    val base = when (unit) {
        "hour" -> 3_600_000L
        "day" -> 86_400_000L
        else -> 60_000L
    }
    return base * value
}

/**
 * Стратегия для бэктеста: та же реализация, что в боте, но свечи берёт из [slices] (без сети
 * и без заглядывания в будущее).
 */
fun backtestStrategy(config: StrategyConfig, slices: CandleSlices): Strategy {
    val source: suspend (String, String, Int, Int) -> List<Candle> = { _, tf, agg, limit ->
        slices.forTimeframe(tf, agg).takeLast(limit)
    }
    return when (config.type) {
        StrategyType.BREAKOUT_PULLBACK.name -> BreakoutPullbackStrategy(config, htfSource = { _, _, limit -> slices.higher.takeLast(limit) })
        StrategyType.MOMENTUM.name -> MomentumStrategy(config)
        StrategyType.RSI_EMA.name -> RsiEmaStrategy(config)
        StrategyType.SCORE.name -> ScoreStrategy(config, analyzer = MarketAnalyzerService(candleSource = source))
        else -> DarsStrategy(config, engine = DarsEntryEngine(candleSource = source))
    }
}

/**
 * Бэктест стратегии по истории свечей — с теми же правилами, что у живого бота:
 *  - вход: стратегия видит только закрытые свечи (рабочий и старший ТФ), фильтры сканера по оценённым
 *    в прошлом метрикам, общий порог уверенности, range-фильтр; исполнение — по открытию следующей
 *    свечи с проверкой разбега цены (как [tj.khujand.solana.trading.bot.core.strategy.StrategyManager]);
 *  - выходы: общая [decideExit] (её же использует TradeMonitor), цена внутри свечи идёт по пути
 *    open→low→high→close для бычьей и open→high→low→close для медвежьей; стоп и тейк исполняются
 *    по своему уровню, при гэпе через уровень — по открытию.
 *
 * Упрощения: ликвидность-стоп не моделируется (исторической ликвидности нет), сделки разных монет
 * считаются независимо (без лимита позиций и кулдауна).
 */
object Backtester {

    /** Сколько первых баров пропускаем: стратегиям нужна история. */
    private const val WARMUP_BARS = 10

    /** Защита от зацикливания: несколько ступеней лестницы на одной цене. */
    private const val MAX_ACTIONS_PER_PRICE = 4

    suspend fun run(
        config: StrategyConfig,
        series: List<CoinSeries>,
        settings: BacktestSettings,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BacktestReport {
        val trades = ArrayList<BacktestTrade>()
        var bars = 0
        var withData = 0
        series.forEachIndexed { idx, s ->
            yield() // точка отмены: прогон десятков монет может идти заметное время
            if (s.candles.size > WARMUP_BARS + 1) {
                withData++
                bars += s.candles.size
                trades += runCoin(config, s, settings)
            }
            onProgress(idx + 1, series.size)
        }
        return BacktestReport(
            strategyName = config.name,
            coins = series.size,
            coinsWithData = withData,
            bars = bars,
            trades = trades,
            stats = BacktestStats.from(trades),
        )
    }

    internal suspend fun runCoin(config: StrategyConfig, s: CoinSeries, settings: BacktestSettings): List<BacktestTrade> {
        val candles = s.candles
        val tfMs = timeframeMs(config.timeframe)
        val htfMs = config.backtestHigherTf()?.let { timeframeMs(it) } ?: 0L
        val window = config.darsCandleLimit.coerceAtLeast(50)
        val slices = CandleSlices(DexClient.parseTimeframe(config.timeframe))
        val strategy = backtestStrategy(config, slices)
        val costFrac = settings.costPerSidePercent / 100
        val trades = ArrayList<BacktestTrade>()

        var htfClosed = 0 // сколько свечей старшего ТФ уже закрылось к текущему бару
        var i = WARMUP_BARS
        while (i < candles.size - 1) {
            val barCloseMs = candles[i].openTimeMs + tfMs
            while (htfClosed < s.higherTf.size && s.higherTf[htfClosed].openTimeMs + htfMs <= barCloseMs) htfClosed++
            slices.entry = candles.subList(maxOf(0, i - window + 1), i + 1)
            slices.higher = s.higherTf.subList(maxOf(0, htfClosed - window), htfClosed)

            val candidate = candidateAt(s.coin, candles, i, tfMs, barCloseMs)
            val signal = if (passesScanFilters(config, candidate)) strategy.analyze(candidate, slices.entry, emptyList()) else null
            val accepted = signal != null &&
                (strategy.selfGated || signal.confidence >= MIN_CONFIDENCE) &&
                config.rangeAllowsEntry(slices.entry, signal.entryPrice)
            if (signal == null || !accepted) {
                i++
                continue
            }

            // Исполнение по открытию следующей свечи; убежавшую цену не догоняем (как в боте).
            val next = candles[i + 1]
            val fill = next.open
            if (signal.entryPrice <= 0.0 || fill <= 0.0 ||
                abs(fill - signal.entryPrice) / signal.entryPrice > MAX_ENTRY_PRICE_DRIFT
            ) {
                i++
                continue
            }
            val k = fill / signal.entryPrice
            val stop = signal.stopLoss * k
            if (stop <= 0.0 || stop >= fill) {
                i++
                continue
            }
            val start = PositionState(
                entryPrice = fill,
                initialStop = stop,
                stopLoss = stop,
                takeProfit = signal.takeProfit * k,
                peakPrice = fill,
                tp1Done = false,
                tp2Done = false,
                entryLiquidityUsd = 0.0,
                entryAtrFrac = atrFraction(slices.entry, config.atrPeriod, fill),
                openedAtMs = next.openTimeMs,
            )
            val sim = simulate(start, config, candles, i + 1, tfMs)
            trades += toTrade(s.coin, start, sim, costFrac, signal.reason)
            if (settings.oneTradePerCoin) break
            i = sim.exitIndex + 1
        }
        return trades
    }

    /** Результат сопровождения позиции: фиксации и индекс свечи, где позиция закрылась полностью. */
    internal data class SimResult(val fills: List<BacktestFill>, val exitIndex: Int)

    /**
     * Сопровождение позиции по свечам начиная с [fromIndex] (свеча входа: вход по её открытию).
     * Каждая точка пути цены прогоняется через [decideExit]; несколько действий на одной цене
     * (TP1 и сразу TP2) исполняются подряд.
     */
    internal fun simulate(
        start: PositionState,
        config: StrategyConfig,
        candles: List<Candle>,
        fromIndex: Int,
        tfMs: Long,
    ): SimResult {
        var p = start
        var remaining = 1.0
        val fills = ArrayList<BacktestFill>()
        for (j in fromIndex until candles.size) {
            val bar = candles[j]
            val path = if (bar.isBullish) listOf(bar.open, bar.low, bar.high, bar.close)
            else listOf(bar.open, bar.high, bar.low, bar.close)
            for ((k, price) in path.withIndex()) {
                val timeMs = when (k) {
                    0 -> bar.openTimeMs
                    3 -> bar.openTimeMs + tfMs - 1
                    else -> bar.openTimeMs + tfMs / 2
                }
                // Стоп/тейк — по своему уровню (цена прошла через него внутри свечи), при гэпе
                // на открытии — по цене открытия; рыночные выходы (тайм-стоп) — по текущей цене.
                fun fillAt(level: Double?): Double = if (level == null || k == 0) price else level
                var actions = 0
                while (actions++ < MAX_ACTIONS_PER_PRICE) {
                    val d = decideExit(p, config, price, liquidity = null, nowMs = timeMs)
                    p = p.copy(peakPrice = d.peak, stopLoss = maxOf(p.stopLoss, d.stop))
                    when (val a = d.action) {
                        ExitAction.Hold -> break
                        is ExitAction.CloseAll -> {
                            fills += BacktestFill(timeMs, fillAt(a.level), remaining, a.reason)
                            return SimResult(fills, j)
                        }
                        is ExitAction.ClosePart -> {
                            val portion = minOf(a.portionOfOriginal / 100, remaining)
                            fills += BacktestFill(timeMs, fillAt(a.level), portion, a.reason)
                            remaining -= portion
                            if (remaining <= 1e-9) return SimResult(fills, j)
                            p = p.copy(tp1Done = p.tp1Done || a.tp1, tp2Done = p.tp2Done || a.tp2)
                            val stopAfter = a.stopAfter
                            if (stopAfter != null && stopAfter > p.stopLoss) p = p.copy(stopLoss = stopAfter)
                        }
                    }
                }
            }
        }
        // История кончилась раньше позиции — закрываем остаток по последней цене.
        val last = candles.last()
        fills += BacktestFill(last.openTimeMs + tfMs - 1, last.close, remaining, "Конец данных")
        return SimResult(fills, candles.lastIndex)
    }

    /** Итог сделки в R и % с издержками [costFrac] на вход и на каждую фиксацию. */
    internal fun toTrade(
        coin: BacktestCoin,
        start: PositionState,
        sim: SimResult,
        costFrac: Double,
        signalReason: String,
    ): BacktestTrade {
        val entryCost = start.entryPrice * (1 + costFrac)
        val risk = start.entryPrice - start.initialStop
        val pnlPerUnit = sim.fills.sumOf { it.portion * (it.price * (1 - costFrac) - entryCost) }
        return BacktestTrade(
            symbol = coin.symbol,
            pool = coin.pool,
            entryTimeMs = start.openedAtMs,
            exitTimeMs = sim.fills.last().timeMs,
            entryPrice = start.entryPrice,
            initialStop = start.initialStop,
            fills = sim.fills,
            rMultiple = if (risk > 0) pnlPerUnit / risk else 0.0,
            returnPct = pnlPerUnit / entryCost * 100,
            exitReason = sim.fills.last().reason,
            signalReason = signalReason,
        )
    }

    /**
     * Кандидат сканера «на момент» свечи [i]. Исторических метрик нет, поэтому оцениваем от текущих:
     * капитализация ∝ цене (supply постоянен), ликвидность CPMM-пула ∝ √цены (если LP не менялись).
     */
    internal fun candidateAt(coin: BacktestCoin, candles: List<Candle>, i: Int, tfMs: Long, nowMs: Long): TokenCandidate {
        val price = candles[i].close
        val ratio = if (coin.priceNow > 0) price / coin.priceNow else 1.0
        val barsPerHour = (3_600_000L / tfMs).toInt().coerceAtLeast(1)
        fun changeOverMs(ms: Long): Double {
            val back = (ms / tfMs).toInt().coerceAtLeast(1)
            val j = i - back
            return if (j < 0 || candles[j].close <= 0) 0.0 else (price / candles[j].close - 1) * 100
        }
        return TokenCandidate(
            mint = coin.mint,
            symbol = coin.symbol,
            name = coin.name,
            pairAddress = coin.pool,
            dexId = coin.dexId,
            priceUsd = price,
            liquidityUsd = coin.liquidityNow * sqrt(ratio),
            marketCap = coin.marketCapNow * ratio,
            volumeH1Usd = candles.subList(maxOf(0, i - barsPerHour + 1), i + 1).sumOf { it.volume },
            buysH1 = 0,
            sellsH1 = 0,
            priceChangeM5 = changeOverMs(5 * 60_000L),
            priceChangeH1 = changeOverMs(60 * 60_000L),
            tokenAgeMinutes = if (coin.createdAtMs > 0) ((nowMs - coin.createdAtMs) / 60_000).coerceAtLeast(0) else 0,
            score = 0.0,
            scannedAt = nowMs,
        )
    }

    /** Фильтры сканера стратегии (ликвидность / капитализация / возраст), как в TokenScanner. */
    internal fun passesScanFilters(config: StrategyConfig, c: TokenCandidate): Boolean {
        val f = config.scanFilters()
        return !f.filtersEnabled || (
            c.liquidityUsd >= f.minLiquidityUsd &&
                c.marketCap in f.minMarketCap..f.maxMarketCap &&
                c.tokenAgeMinutes in f.minTokenAgeMinutes..f.maxTokenAgeMinutes
            )
    }
}
