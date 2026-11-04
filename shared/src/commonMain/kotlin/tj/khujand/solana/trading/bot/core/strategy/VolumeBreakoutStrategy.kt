package tj.khujand.solana.trading.bot.core.strategy

import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.Indicators
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * «Пробой на объёме» — сетап «первый откат → ретест максимума → пробой на всплеске объёма»
 * (Volume Breakout из плана этапа 2):
 *  1) импульс: от минимума окна [StrategyConfig.vbLookbackBars] баров до максимума ≥ [StrategyConfig.vbMinImpulsePct]%;
 *  2) первый откат от максимума ≥ [StrategyConfig.vbMinPullbackPct]%, но не глубже
 *     [StrategyConfig.vbMaxPullbackRetrace] импульса — минимум отката выше начала импульса;
 *  3) ретест: два бара перед пробоем закрылись не ниже чем на [StrategyConfig.vbNearHighPct]% под
 *     максимумом — цена «подползла» к уровню, а не выстрелила со дна отката;
 *  4) пробой: закрытый бар закрылся выше максимума, бычий, в верхней половине диапазона, на объёме
 *     ≥ [StrategyConfig.vbVolumeMult] × средний за предыдущие бары;
 *  5) не догоняем: цена выше максимума не больше чем на [StrategyConfig.vbMaxExtensionPct]%;
 *  6) стоп — под базой перед пробоем (минимум трёх баров) − 0.25 ATR; дальше Stop Loss конфига — пропуск.
 *
 * В отличие от «Пробоя + отката» вход — на самом пробое, а не на откате после него. Свечи — только
 * закрытые: формирующийся бар GeckoTerminal отбрасывается, пробой «фитилём» внутри бара, который
 * закроется под уровнем, сигналом не считается. Пробой баром раньше ещё годится, если цена удержалась
 * над максимумом — цикл сканера (~30 с) мог не застать закрытие бара.
 * Цена входа — закрытие последнего бара: она свежее цены кандидата из кеша сканера (до 5 минут), а
 * разбег живой цены с ней больше 3% отменит вход в StrategyManager — убежавший пробой не догоняем.
 *
 * @param nowMs текущее время — по нему определяется, закрыт ли последний бар.
 */
class VolumeBreakoutStrategy(
    override val config: StrategyConfig,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : Strategy {

    override val name: String = StrategyType.VOLUME_BREAKOUT.displayName

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? = evaluate(candidate, candles, higherTfCandles).signal

    override suspend fun evaluate(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Evaluation {
        val closed = closedOnly(candles)
        val total = VolumeBreakoutDetector.STAGES
        val setup = when (val r = VolumeBreakoutDetector.detect(closed, config)) {
            is VolumeBreakoutResult.Reject -> return Evaluation.stoppedAt(r.stage, total, r.reason, r.level)
            is VolumeBreakoutResult.Setup -> r
        }
        val entry = closed.last().close
        if (entry <= 0.0) return Evaluation.stoppedAt(total, total, "нет цены входа", setup.previousHigh)
        val signal = Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = setup.confidence,
            reason = "Пробой на объёме: ${setup.reason}",
            entryPrice = entry,
            stopLoss = entry * (1 - setup.stopFrac),
            takeProfit = config.takeProfitPrice(entry),
        )
        return Evaluation.ready(signal, total, entryLevel = setup.previousHigh)
    }

    /** Без формирующегося бара: его закрытие ещё неизвестно — пробой фитилём может закрыться под уровнем. */
    private fun closedOnly(candles: List<Candle>): List<Candle> {
        val last = candles.lastOrNull() ?: return candles
        return if (last.openTimeMs + barMs(config.timeframe) > nowMs()) candles.dropLast(1) else candles
    }

    /** Длительность свечи ТФ ("1m", "5m", "1h", …) в миллисекундах. */
    private fun barMs(timeframe: String): Long {
        val (unit, value) = DexClient.parseTimeframe(timeframe)
        val base = when (unit) {
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            else -> 60_000L
        }
        return base * value
    }
}

/** Результат поиска сетапа «Пробоя на объёме»: найден (с параметрами входа) или отклонён с причиной. */
sealed interface VolumeBreakoutResult {
    /**
     * @param stage шаг детектора (с 1, см. [VolumeBreakoutDetector.STAGES]), на котором сетап не сложился.
     * @param level прежний максимум, если до него дошли, — уровень, пробой которого ждём.
     */
    data class Reject(val reason: String, val stage: Int = 1, val level: Double? = null) : VolumeBreakoutResult

    /**
     * @param previousHigh пробитый максимум; [pullbackLow] — минимум первого отката.
     * @param stopFrac расстояние до стопа как доля цены входа (закрытия последнего бара).
     * @param volumeRatio объём бара пробоя к среднему объёму перед ним.
     * @param barsAgo 0 — пробил последний закрытый бар, 1 — предыдущий (цена удержалась над максимумом).
     */
    data class Setup(
        val previousHigh: Double,
        val pullbackLow: Double,
        val stopFrac: Double,
        val volumeRatio: Double,
        val barsAgo: Int,
        val confidence: Double,
        val reason: String,
    ) : VolumeBreakoutResult
}

/** Чистый детектор сетапа по ЗАКРЫТЫМ свечам рабочего ТФ (без сети) — покрывается тестами. */
object VolumeBreakoutDetector {

    /**
     * Шаги детектора по порядку (для доски наблюдения): история, импульс, откат, ретест, закрытие над
     * максимумом, сила и объём пробоя, удержание без погони, стоп.
     */
    const val STAGES = 8

    /** Пробой ищем на последнем закрытом баре и на предыдущем (если цена удержалась над максимумом). */
    private const val MAX_BARS_SINCE_BREAKOUT = 1

    /** Сколько баров перед пробоем должны закрыться у максимума (ретест). */
    private const val RETEST_BARS = 2

    /** Стоп — под минимумом стольких баров перед пробоем (база). */
    private const val STOP_BASE_BARS = 3

    /** …и ещё на 0.25 ATR ниже — за шумом. */
    private const val STOP_ATR_BUFFER = 0.25

    /** Средний объём «до пробоя» — по стольким барам. */
    private const val VOLUME_AVG_BARS = 20

    /** Закрытие бара пробоя — в верхней половине диапазона: длинный верхний фитиль — пробой продали. */
    private const val MIN_CLOSE_POS = 0.5

    /** Потолок отношения объёмов (база без объёма) — чтобы баллы и текст оставались конечными. */
    private const val MAX_VOL_RATIO = 99.0

    fun detect(candles: List<Candle>, config: StrategyConfig): VolumeBreakoutResult {
        val minBars = maxOf(config.atrPeriod + 2, STOP_BASE_BARS + RETEST_BARS + 4)
        if (candles.size < minBars) return reject("мало свечей (${candles.size}<$minBars)")
        // Причина отказа — по самому свежему бару: она и объясняет, почему входа нет сейчас.
        var latestReject: VolumeBreakoutResult.Reject? = null
        for (ago in 0..MAX_BARS_SINCE_BREAKOUT) {
            when (val r = detectAt(candles, candles.lastIndex - ago, config)) {
                is VolumeBreakoutResult.Setup -> return r
                is VolumeBreakoutResult.Reject -> if (latestReject == null) latestReject = r
            }
        }
        return latestReject ?: reject("сетапа нет")
    }

    /** Сетап с пробоем на баре [b] (вход — по закрытию последнего бара). */
    private fun detectAt(candles: List<Candle>, b: Int, config: StrategyConfig): VolumeBreakoutResult {
        // Шаг и уровень — для доски наблюдения: на чём остановились и какой максимум ждёт пробоя.
        var stage = 1
        var level: Double? = null
        fun reject(reason: String) = VolumeBreakoutResult.Reject(reason, stage, level)

        val bar = candles[b]
        val start = maxOf(0, b - config.vbLookbackBars.coerceAtLeast(10))
        if (b - start < STOP_BASE_BARS + RETEST_BARS + 2) return reject("мало истории перед пробоем")

        // 1. Прежний максимум окна (до бара пробоя) и импульс к нему от минимума окна.
        stage = 2
        val h = (start until b).maxBy { candles[it].high }
        val high = candles[h].high
        level = high
        if (b - 1 - h < RETEST_BARS) return reject("максимум у самого пробоя — отката и ретеста не было")
        val l = (start..h).minBy { candles[it].low }
        val impulseLow = candles[l].low
        if (l == h || impulseLow <= 0.0) return reject("нет импульса к максимуму")
        val impulsePct = (high / impulseLow - 1) * 100
        if (impulsePct < config.vbMinImpulsePct) {
            return reject("импульс +${fmt(impulsePct)}% < +${fmt(config.vbMinImpulsePct)}%")
        }

        // 2. Первый откат: заметный, но минимум выше начала импульса.
        stage = 3
        val afterPeak = candles.subList(h + 1, b)
        val pullbackLow = afterPeak.minOf { it.low }
        val pullbackPct = (1 - pullbackLow / high) * 100
        if (pullbackPct < config.vbMinPullbackPct) {
            return reject("отката не было: −${fmt(pullbackPct)}% от максимума < ${fmt(config.vbMinPullbackPct)}%")
        }
        val retrace = (high - pullbackLow) / (high - impulseLow)
        if (retrace > config.vbMaxPullbackRetrace) {
            return reject("откат ${fmt2(retrace)} импульса глубже ${fmt2(config.vbMaxPullbackRetrace)}")
        }

        // 3. Ретест: перед пробоем цена закрывалась у самого максимума.
        stage = 4
        val nearFloor = high * (1 - config.vbNearHighPct / 100)
        if (candles.subList(b - RETEST_BARS, b).any { it.close < nearFloor }) {
            return reject("перед пробоем цена не вернулась к максимуму (закрытия ниже ${fmt(config.vbNearHighPct)}% под ним)")
        }

        // 4. Бар пробоя: закрытие выше максимума, сильное, на всплеске объёма.
        stage = 5
        if (bar.close <= high) return reject("закрытие не выше прежнего максимума")
        stage = 6
        if (!bar.isBullish || bar.closePosition < MIN_CLOSE_POS) return reject("слабое закрытие бара пробоя")
        val avgVol = candles.subList(maxOf(start, b - VOLUME_AVG_BARS), b).map { it.volume }.average()
        val volRatio = when {
            avgVol > 0.0 -> bar.volume / avgVol
            bar.volume > 0.0 -> MAX_VOL_RATIO
            else -> 0.0
        }.coerceAtMost(MAX_VOL_RATIO)
        if (volRatio < config.vbVolumeMult) {
            return reject("объём пробоя ×${fmt(volRatio)} < ×${fmt(config.vbVolumeMult)} среднего")
        }

        // 5. Пробой был баром раньше — цена должна была удержаться над максимумом. Убежавшую не догоняем.
        stage = 7
        if (candles.subList(b + 1, candles.size).any { it.close <= high }) {
            return reject("после пробоя цена закрылась под максимумом")
        }
        val entry = candles.last().close
        val extensionPct = (entry / high - 1) * 100
        if (extensionPct > config.vbMaxExtensionPct) {
            return reject("цена ушла на +${fmt(extensionPct)}% над максимумом — не догоняем")
        }

        // 6. Стоп под базой перед пробоем.
        stage = 8
        val atr = Indicators.atr(candles.subList(0, b + 1), config.atrPeriod).lastOrNull() ?: 0.0
        val baseLow = candles.subList(b - STOP_BASE_BARS, b).minOf { it.low }
        val stop = baseLow - STOP_ATR_BUFFER * atr
        if (stop <= 0.0 || stop >= entry) return reject("некорректный стоп")
        val stopFrac = (entry - stop) / entry
        if (stopFrac * 100 > config.stopLossPercent) {
            return reject("стоп −${pct(stopFrac)}% дальше SL ${fmt(config.stopLossPercent)}% — база не тугая")
        }

        // Уверенность: сила всплеска объёма, «высохший» объём в базе, тугой стоп.
        val impulseVol = candles.subList(l, h + 1).map { it.volume }.average()
        val baseVol = afterPeak.map { it.volume }.average()
        val dryScore = if (impulseVol > 0.0) (1 - baseVol / impulseVol).coerceIn(0.0, 1.0) else 0.0
        val volScore = ((volRatio - config.vbVolumeMult) / config.vbVolumeMult).coerceIn(0.0, 1.0)
        val tightScore = (1 - stopFrac * 100 / config.stopLossPercent).coerceIn(0.0, 1.0)
        val confidence = (0.6 + 0.15 * volScore + 0.1 * dryScore + 0.1 * tightScore).coerceAtMost(0.95)
        val ago = candles.lastIndex - b

        return VolumeBreakoutResult.Setup(
            previousHigh = high,
            pullbackLow = pullbackLow,
            stopFrac = stopFrac,
            volumeRatio = volRatio,
            barsAgo = ago,
            confidence = confidence,
            reason = "импульс +${fmt(impulsePct)}%, откат −${fmt(pullbackPct)}% (${fmt2(retrace)} импульса), " +
                "ретест максимума, пробой ×${fmt(volRatio)} объёма" + (if (ago > 0) " баром раньше" else "") +
                ", над максимумом +${fmt(extensionPct)}%, стоп −${pct(stopFrac)}% под базой",
        )
    }

    /** Отказ до разбора бара пробоя (мало свечей) — шаг 1. */
    private fun reject(reason: String) = VolumeBreakoutResult.Reject(reason, stage = 1)

    private fun pct(frac: Double): String = fmt(frac * 100)

    private fun fmt(v: Double): String = (kotlin.math.round(v * 10) / 10.0).toString()

    private fun fmt2(v: Double): String = (kotlin.math.round(v * 1000) / 1000.0).toString()
}
