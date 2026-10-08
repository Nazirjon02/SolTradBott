package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.core.Indicators
import tj.khujand.solana.trading.bot.domain.dars.TrendAnalyzer
import tj.khujand.solana.trading.bot.domain.dars.TrendDirection
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/**
 * «Пробой + откат» — связка Momentum → Pullback → Volume confirmation (см. STRATEGY_RESEARCH.md §3).
 *
 * Не покупаем сам пробой (бот опаздывает к нему на цикл сканера, а после пробоя на объёме цена
 * возвращается к уровню в ~57% случаев — Bulkowski). Пробой лишь «взводит» сетап, вход — на откате:
 *  1) пробой: закрытие выше максимума [StrategyConfig.bpBreakoutLookback] баров на объёме
 *     ≥ [StrategyConfig.bpBreakoutVolumeMult] × средний, закрытие в верхней части бара (Turtles, Minervini);
 *  2) откат: ≥ 2 баров после пика, минимум удержал уровень пробоя (ретест), объём «высох» —
 *     средний ≤ [StrategyConfig.bpPullbackVolumeRatio] × объём импульса, без всплеска продаж;
 *  3) триггер: последний бар бычий, выше максимума предыдущего (buy-stop как у Raschke),
 *     закрытие ≥ 60% диапазона, объём ≥ [StrategyConfig.bpTriggerVolumeMult] × средний объём отката;
 *  4) стоп под минимумом отката − 0.5 ATR; отказ, если стоп дальше SL% конфига (сетап не тугой)
 *     или до пика импульса меньше 1R (мало запаса хода);
 *  5) тренд старшего ТФ восходящий (fail-closed: нет данных — нет входа). Старший ТФ тянется
 *     только когда сетап на рабочем ТФ уже найден — экономим лимит GeckoTerminal.
 *
 * Последняя свеча может быть ещё формирующейся — тогда триггер работает как стоп-ордер на покупку
 * выше максимума предыдущего бара. Выходы — общие для всех стратегий (R-лестница, раннер,
 * ATR-трейлинг в [tj.khujand.solana.trading.bot.core.engine.TradeMonitor]).
 *
 * @param htfSource источник свечей старшего ТФ (пул, таймфрейм "15m", лимит). Инъекция — для тестов.
 */
class BreakoutPullbackStrategy(
    override val config: StrategyConfig,
    private val htfSource: suspend (pool: String, timeframe: String, limit: Int) -> List<Candle> = { pool, tf, limit ->
        val (gtTf, agg) = DexClient.parseTimeframe(tf)
        GeckoTerminalApi.getCandles(pool, gtTf, agg, limit)
    },
) : Strategy {

    override val name: String = StrategyType.BREAKOUT_PULLBACK.displayName

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? {
        val entry = candidate.priceUsd
        if (entry <= 0.0) return null

        val setup = BreakoutPullbackDetector.detect(candles, config) as? BreakoutPullbackResult.Setup ?: return null

        if (config.bpRequireHtfTrend) {
            val htf = higherTfCandles.ifEmpty { htfSource(candidate.pairAddress, config.bpHigherTf, HTF_LIMIT) }
            if (htf.size < MIN_HTF_BARS) return null // тренд неизвестен — не торгуем вслепую
            if (TrendAnalyzer.trend(htf, HTF_PIVOT_PCT) != TrendDirection.UP) return null
        }

        return Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = setup.confidence,
            reason = "Пробой+откат: ${setup.reason}",
            entryPrice = entry,
            stopLoss = entry * (1 - setup.stopFrac),
            takeProfit = config.takeProfitPrice(entry),
        )
    }

    private companion object {
        const val HTF_LIMIT = 100
        const val MIN_HTF_BARS = 6
        const val HTF_PIVOT_PCT = 1.0
    }
}

/** Результат поиска сетапа: найден (с параметрами входа) или отклонён с причиной. */
sealed interface BreakoutPullbackResult {
    data class Reject(val reason: String) : BreakoutPullbackResult

    /**
     * @param stopFrac расстояние до стопа как доля цены триггера.
     * @param roomR запас хода до пика импульса в R.
     */
    data class Setup(
        val pivot: Double,
        val peak: Double,
        val pullbackLow: Double,
        val stopFrac: Double,
        val roomR: Double,
        val confidence: Double,
        val reason: String,
    ) : BreakoutPullbackResult
}

/** Чистый детектор сетапа по свечам рабочего ТФ (без сети) — покрывается тестами. */
object BreakoutPullbackDetector {

    /** Минимум баров отката после пика (не считая триггер): один бар — это ещё не откат. */
    private const val MIN_PULLBACK_BARS = 2

    /** Закрытие бара пробоя и триггера — в верхних 40% диапазона (как подтверждение в Dars). */
    private const val STRONG_CLOSE_POS = 0.6

    /** Допуск ретеста: минимум отката может проколоть уровень пробоя не глубже 0.5 ATR. */
    private const val RETEST_ATR_TOLERANCE = 0.5

    /** Стоп — на 0.5 ATR ниже минимума отката (за шумом). */
    private const val STOP_ATR_BUFFER = 0.5

    /** Минимальный запас хода до пика импульса, в R. */
    private const val MIN_ROOM_R = 1.0

    /** Потолок отношения объёмов (для отката без объёма) — чтобы баллы и текст оставались конечными. */
    private const val MAX_VOL_RATIO = 99.0

    fun detect(candles: List<Candle>, config: StrategyConfig): BreakoutPullbackResult {
        val lookback = config.bpBreakoutLookback.coerceAtLeast(5)
        val n = candles.size
        val minBars = lookback + MIN_PULLBACK_BARS + 3
        if (n < minBars) return reject("мало свечей ($n<$minBars)")

        val trigger = candles[n - 1]
        val prev = candles[n - 2]

        // 1. Последний пробой на объёме: после него должно остаться место для отката.
        val lastBreakoutIdx = n - 2 - MIN_PULLBACK_BARS
        val firstBreakoutIdx = maxOf(lookback, n - 1 - config.bpMaxBarsSinceBreakout)
        val b = (lastBreakoutIdx downTo firstBreakoutIdx).firstOrNull {
            isVolumeBreakout(candles, it, lookback, config.bpBreakoutVolumeMult)
        } ?: return reject("нет пробоя на объёме за ${config.bpMaxBarsSinceBreakout} баров")

        // Уровень пробоя — вершина базы ДО серии пробойных баров (у 2-го, 3-го бара серии
        // окно Donchian уже включает сам импульс, и «уровень» оказался бы завышен).
        var b0 = b
        while (b0 - 1 >= lookback && closesAbovePivot(candles, b0 - 1, lookback)) b0--
        val pivot = maxHigh(candles, b0 - lookback, b0 - 1)

        // 2. Пик после начала пробоя и откат от него (бар-триггер в откат не входит).
        val h = (b0..n - 2).maxBy { candles[it].high }
        val peak = candles[h].high
        val impulse = candles.subList(b0, h + 1)
        val pullback = candles.subList(h + 1, n - 1)
        if (pullback.size < MIN_PULLBACK_BARS) return reject("отката ещё нет (${pullback.size} бар после пика)")
        val pullbackLow = pullback.minOf { it.low }

        val atr = Indicators.atr(candles, config.atrPeriod).lastOrNull()
            ?: return reject("мало свечей для ATR")
        if (atr <= 0.0) return reject("нулевой ATR")

        // 3. Ретест удержал уровень пробоя; глубже — пробой ложный (цена вернулась в базу).
        if (pullbackLow < pivot - RETEST_ATR_TOLERANCE * atr) {
            return reject("откат ушёл под уровень пробоя — пробой ложный")
        }

        // 4. Объём «высыхает» в откате, и нет всплеска продаж против нас.
        val impulseVol = impulse.map { it.volume }.average()
        val pullbackVol = pullback.map { it.volume }.average()
        if (impulseVol <= 0.0) return reject("нет объёма в импульсе")
        val dryRatio = pullbackVol / impulseVol
        if (dryRatio > config.bpPullbackVolumeRatio) {
            return reject("объём в откате не снизился (${pct(dryRatio)}% объёма импульса)")
        }
        val impulseMaxVol = impulse.maxOf { it.volume }
        if (pullback.any { !it.isBullish && it.volume > impulseMaxVol }) {
            return reject("всплеск объёма продаж в откате")
        }

        // 5. Бар-триггер: разворот вверх с сильным закрытием и возвратом объёма.
        if (!trigger.isBullish || trigger.close <= prev.high) return reject("нет триггера: цена не выше максимума прошлого бара")
        if (trigger.closePosition < STRONG_CLOSE_POS) return reject("слабое закрытие триггера")
        // Откат без объёма вовсе (бывает на тонких пулах) — любой объём триггера считаем всплеском.
        val triggerVolRatio = when {
            pullbackVol > 0.0 -> trigger.volume / pullbackVol
            trigger.volume > 0.0 -> MAX_VOL_RATIO
            else -> 0.0
        }.coerceAtMost(MAX_VOL_RATIO)
        if (triggerVolRatio < config.bpTriggerVolumeMult) {
            return reject("объём триггера ×${fmt(triggerVolRatio)} < ×${fmt(config.bpTriggerVolumeMult)}")
        }

        // 6. Стоп под структурой и запас хода до пика.
        val entry = trigger.close
        val stop = pullbackLow - STOP_ATR_BUFFER * atr
        if (stop <= 0.0 || stop >= entry) return reject("некорректный стоп")
        val risk = entry - stop
        val stopFrac = risk / entry
        if (stopFrac * 100 > config.stopLossPercent) {
            return reject("стоп −${pct(stopFrac)}% дальше SL ${fmt(config.stopLossPercent)}% — сетап не тугой")
        }
        val roomR = (peak - entry) / risk
        if (roomR < MIN_ROOM_R) return reject("до пика импульса ${fmt(roomR)}R < ${fmt(MIN_ROOM_R)}R")

        val breakoutVolRatio = candles[b].volume / avgVolume(candles, b - lookback, b - 1)
        val dryScore = (1 - dryRatio / config.bpPullbackVolumeRatio).coerceIn(0.0, 1.0)
        val roomScore = ((roomR - MIN_ROOM_R) / 2).coerceIn(0.0, 1.0)
        val triggerScore = ((triggerVolRatio - config.bpTriggerVolumeMult) / config.bpTriggerVolumeMult).coerceIn(0.0, 1.0)
        val confidence = (0.6 + 0.15 * dryScore + 0.1 * roomScore + 0.1 * triggerScore).coerceAtMost(0.95)

        return BreakoutPullbackResult.Setup(
            pivot = pivot,
            peak = peak,
            pullbackLow = pullbackLow,
            stopFrac = stopFrac,
            roomR = roomR,
            confidence = confidence,
            reason = "пробой ×${fmt(breakoutVolRatio)} объёма ${n - 1 - b0} бар назад, " +
                "откат ${pullback.size} бар (объём ${pct(dryRatio)}% импульса), ретест удержал уровень, " +
                "триггер ×${fmt(triggerVolRatio)}, стоп −${pct(stopFrac)}%, до пика ${fmt(roomR)}R",
        )
    }

    /** Бар [i] закрылся выше максимума [lookback] предыдущих баров (пробой Donchian). */
    private fun closesAbovePivot(c: List<Candle>, i: Int, lookback: Int): Boolean =
        c[i].close > maxHigh(c, i - lookback, i - 1)

    private fun isVolumeBreakout(c: List<Candle>, i: Int, lookback: Int, volMult: Double): Boolean {
        if (!closesAbovePivot(c, i, lookback)) return false
        val bar = c[i]
        if (!bar.isBullish || bar.closePosition < STRONG_CLOSE_POS) return false
        val avgVol = avgVolume(c, i - lookback, i - 1)
        return avgVol > 0.0 && bar.volume >= volMult * avgVol
    }

    private fun maxHigh(c: List<Candle>, from: Int, to: Int): Double = (from..to).maxOf { c[it].high }

    private fun avgVolume(c: List<Candle>, from: Int, to: Int): Double = (from..to).map { c[it].volume }.average()

    private fun reject(reason: String) = BreakoutPullbackResult.Reject(reason)

    private fun pct(frac: Double): String = fmt(frac * 100)

    private fun fmt(v: Double): String = (kotlin.math.round(v * 10) / 10.0).toString()
}
