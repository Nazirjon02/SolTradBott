package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair

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

        // Тренд старшего ТФ (Урок 2). Бот лонговый → нужен UP.
        val trend = if (cfg.useTrendLevels || cfg.requireHtfTrend) {
            val htf = candleSource(pool, cfg.higherTf, cfg.higherTfAggregate, cfg.candleLimit)
            if (htf.size < 6) {
                if (cfg.failClosed) return DarsSignal.reject("мало свечей старшего ТФ (${htf.size})")
                TrendDirection.UP  // не блокируем, если разрешён fail-open
            } else {
                TrendAnalyzer.trend(htf, cfg.swingPivotPct)
            }
        } else TrendDirection.UP

        if (cfg.useTrendLevels && cfg.requireHtfTrend && trend != TrendDirection.UP) {
            return DarsSignal.reject("тренд старшего ТФ не восходящий ($trend) — лонг запрещён")
        }

        val legs = LegSegmenter.segment(etf, cfg.swingPivotPct)
        if (legs.size < cfg.minLegs) return DarsSignal.reject("мало ног на рабочем ТФ (${legs.size}<${cfg.minLegs})")

        val levels = LevelDetector.detect(etf, cfg.swingPivotPct)
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
            val r = ImpulseCorrectionAnalyzer.analyze(legs, cfg)
            if (r.passed) return r
            rejects += r.reasons.map { "имп/корр: $it" }
        }
        if (cfg.useFalseBreakout) {
            val r = FalseBreakoutDetector.detect(etf, levels, cfg)
            if (r.passed) return r
            rejects += r.reasons.map { "лож.пробой: $it" }
        }
        if (cfg.useTriangle) {
            val r = TriangleDetector.detect(etf, legs, cfg)
            if (r.passed) return r
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

    private fun Double.format1(): String {
        val r = (this * 10).toLong() / 10.0
        return r.toString()
    }
}
