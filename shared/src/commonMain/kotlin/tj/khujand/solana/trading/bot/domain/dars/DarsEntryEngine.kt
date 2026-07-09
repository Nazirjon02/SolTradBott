package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair

/** Буфер под минимумом коррекции для структурного стопа (0.5%), чтобы не выбивало ровно по low. */
private const val STOP_BUFFER = 0.005

/**
 * Оркестратор входа по методике «Dars». Тянет свечи старшего и рабочего ТФ,
 * применяет тренд-гейт и уровни (Урок 2), затем включённые сетапы:
 * импульс/коррекция (Урок 1), ложный пробой (Урок 3), треугольник (Урок 5).
 *
 * Бот только покупает, поэтому все сетапы ищут вход в ЛОНГ (продолжение роста).
 *
 * @param candleSource внедряемый источник свечей (по умолчанию GeckoTerminal) — удобно для тестов.
 */
class DarsEntryEngine(
    private val candleSource: suspend (pool: String, timeframe: String, aggregate: Int, limit: Int) -> List<Candle> =
        { pool, tf, agg, limit -> GeckoTerminalApi.getCandles(pool, tf, agg, limit) },
) {

    suspend fun evaluate(token: TokenPair, settings: FilterSettings): DarsSignal {
        val pool = token.pairAddress
        if (pool.isNullOrBlank()) return DarsSignal.reject("нет адреса пула")
        val cfg = configFrom(settings)

        return try {
            evaluateInternal(pool, cfg)
        } catch (e: Exception) {
            DarsSignal.reject("ошибка анализа Dars: ${e.message}")
        }
    }

    private suspend fun evaluateInternal(pool: String, cfg: DarsConfig): DarsSignal {
        // Рабочий ТФ — на нём ищем точку входа.
        val etf = candleSource(pool, cfg.entryTf, cfg.entryTfAggregate, cfg.candleLimit)
        if (etf.size < 12) return DarsSignal.reject("мало свечей рабочего ТФ (${etf.size})")

        // Старший ТФ тянем ОДИН раз — он нужен и для тренда, и для уровней (Урок 2).
        val needHtf = cfg.useTrendLevels || cfg.requireHtfTrend
        val htf = if (needHtf) candleSource(pool, cfg.higherTf, cfg.higherTfAggregate, cfg.candleLimit) else emptyList()

        // Тренд старшего ТФ (Урок 2). Бот лонговый → нужен UP.
        val trend = when {
            !needHtf -> TrendDirection.UP
            htf.size < 6 -> {
                // Нет данных старшего ТФ → тренд неизвестен. Fail-closed: не торгуем вслепую.
                if (cfg.failClosed) return DarsSignal.reject("мало свечей старшего ТФ (${htf.size}) — тренд неизвестен")
                TrendDirection.UP
            }
            else -> TrendAnalyzer.trend(htf, cfg.swingPivotPct)
        }

        if (cfg.useTrendLevels && cfg.requireHtfTrend && trend != TrendDirection.UP) {
            return DarsSignal.reject("тренд старшего ТФ не восходящий ($trend) — лонг запрещён")
        }

        val legs = LegSegmenter.segment(etf, cfg.swingPivotPct)
        if (legs.size < cfg.minLegs) return DarsSignal.reject("мало ног на рабочем ТФ (${legs.size}<${cfg.minLegs})")

        // Уровни поддержки/сопротивления считаем на СТАРШЕМ ТФ (Урок 2). Если старшего ТФ нет
        // (отключён или мало данных) — на рабочем, чтобы TP/фильтры не остались пустыми.
        val levels = if (cfg.useTrendLevels && htf.size >= 6) LevelDetector.detect(htf, cfg.swingPivotPct)
        else LevelDetector.detect(etf, cfg.swingPivotPct)
        val price = etf.last().close

        // «Не покупаем у сопротивления» (Урок 4).
        if (cfg.useTrendLevels && cfg.rejectAtResistance) {
            val res = LevelDetector.nearestResistance(levels, price)
            if (res != null && price > 0) {
                val distPct = (res.price - price) / price * 100.0
                if (distPct <= cfg.resistanceProximityPct) {
                    return DarsSignal.reject("цена у сопротивления (${distPct.format1()}% до уровня)")
                }
            }
        }

        // Перебор сетапов по приоритету; собираем причины отказа.
        val rejects = ArrayList<String>()

        if (cfg.useImpulseCorrection) {
            val r = ImpulseCorrectionAnalyzer.analyze(etf, legs, cfg)
            if (r.passed) return withTarget(r, levels, price, legs, etf)
            rejects += r.reasons.map { "имп/корр: $it" }
        }
        if (cfg.useFalseBreakout) {
            val r = FalseBreakoutDetector.detect(etf, levels, cfg)
            if (r.passed) return withTarget(r, levels, price, legs, etf)
            rejects += r.reasons.map { "лож.пробой: $it" }
        }
        if (cfg.useTriangle) {
            val r = TriangleDetector.detect(etf, legs, cfg)
            if (r.passed) return withTarget(r, levels, price, legs, etf)
            rejects += r.reasons.map { "треуг.: $it" }
        }

        return DarsSignal(false, trend, null, 0, rejects.ifEmpty { listOf("ни один сетап не включён") })
    }

    private fun configFrom(s: FilterSettings) = DarsConfig(
        higherTf = s.darsHigherTf,
        higherTfAggregate = s.darsHigherTfAggregate,
        entryTf = s.darsEntryTf,
        entryTfAggregate = s.darsEntryTfAggregate,
        candleLimit = s.darsCandleLimit.coerceIn(30, 1000),
        swingPivotPct = s.darsSwingPivotPct,
        requireHtfTrend = s.darsRequireHtfTrend,
        dominanceRatio = s.darsDominanceRatio.coerceAtLeast(1.0),
        minCorrectionLenPct = s.darsMinCorrectionLenPct.coerceIn(0.0, 100.0),
        rejectAtResistance = s.darsRejectAtResistance,
        resistanceProximityPct = s.darsResistanceProximityPct.coerceAtLeast(0.0),
        minLegs = s.darsMinLegs.coerceAtLeast(2),
        failClosed = s.darsFailClosed,
        useImpulseCorrection = s.darsUseImpulseCorrection,
        useTrendLevels = s.darsUseTrendLevels,
        useFalseBreakout = s.darsUseFalseBreakout,
        useTriangle = s.darsUseTriangle,
    )

    /**
     * Обогащает прошедший сигнал целью тейк-профита и структурным стопом.
     *  - TP у ближайшего сопротивления сверху (Урок 2): «цель фиксируем у следующего уровня».
     *    Если сопротивления выше нет (пробой к новым максимумам) — стратегия возьмёт механический TP%.
     *  - Стоп под минимумом последней коррекции + буфер (стоп «за структурой», а не механический SL%).
     *    Если структуру не определить — стратегия возьмёт механический SL%.
     */
    private fun withTarget(
        sig: DarsSignal,
        levels: List<Level>,
        price: Double,
        legs: List<Leg>,
        etf: List<Candle>,
    ): DarsSignal {
        var out = sig

        // Структурный стоп: под минимумом последней коррекции (нога вниз), с небольшим буфером.
        val swingLow = legs.lastOrNull { it.direction == TrendDirection.DOWN }?.endPrice
            ?: etf.takeLast(10).minOfOrNull { it.low }
        if (swingLow != null && price > 0.0 && swingLow < price) {
            val structuralStop = swingLow * (1.0 - STOP_BUFFER)
            val sf = (price - structuralStop) / price
            if (sf > 0.0) out = out.copy(
                stopFrac = sf,
                reasons = out.reasons + "стоп под структурой ${fmtPrice(structuralStop)} (−${(sf * 100).format1()}%)",
            )
        }

        // TP у ближайшего сопротивления сверху.
        val frac = LevelDetector.takeProfitFrac(levels, price)
        val res = if (frac != null) LevelDetector.nearestResistance(levels, price) else null
        if (frac != null && res != null) out = out.copy(
            targetFrac = frac,
            reasons = out.reasons + "TP у сопротивления ${fmtPrice(res.price)} (+${(frac * 100).format1()}%)",
        )

        return out
    }

    private fun fmtPrice(v: Double): String =
        if (v >= 0.01) ((v * 1_000_000).toLong() / 1_000_000.0).toString() else v.toString()

    private fun Double.format1(): String {
        val r = (this * 10).toLong() / 10.0
        return r.toString()
    }
}
