package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import kotlin.math.abs

/** Направление тренда/ноги. */
enum class TrendDirection { UP, DOWN, FLAT }

/** Тип ноги: импульс (по тренду) или коррекция (против тренда). */
enum class LegType { IMPULSE, CORRECTION }

/** Глубина коррекции относительно импульса (Урок 1: боковая / 50% / 100%). */
enum class CorrectionType { SIDEWAYS, HALF, FULL, DEEP }

/** Сетап входа из методички. */
enum class DarsSetup { IMPULSE_CORRECTION, FALSE_BREAKOUT, TRIANGLE }

/**
 * Нога (свинг) — движение цены между двумя соседними пивотами.
 * Хранит 4 признака методики: скорость, размер баров, где закрываются бары, объём.
 */
data class Leg(
    val startIndex: Int,
    val endIndex: Int,
    val startPrice: Double,
    val endPrice: Double,
    val direction: TrendDirection,   // UP или DOWN
    val bars: Int,                   // «скорость/длина» — сколько баров заняла нога
    val maxVolume: Double,
    val avgVolume: Double,
    val avgBarSize: Double,          // средний размер тела бара
    val closeStrength: Double,       // средняя «сила закрытия» по направлению (0..1)
) {
    /** Величина хода по цене. */
    val sizeAbs: Double get() = abs(endPrice - startPrice)

    /** Средняя скорость = размер хода на один бар. */
    val speed: Double get() = if (bars <= 0) 0.0 else sizeAbs / bars
}

/**
 * Параметры анализа Dars (собираются из FilterSettings в [DarsEntryEngine]).
 * Отделяют модуль анализа от большого класса настроек сети.
 */
data class DarsConfig(
    val higherTf: String,
    val higherTfAggregate: Int,
    val entryTf: String,
    val entryTfAggregate: Int,
    val candleLimit: Int,
    val swingPivotPct: Double,
    val requireHtfTrend: Boolean,
    val dominanceRatio: Double,
    val minCorrectionLenPct: Double,
    val rejectAtResistance: Boolean,
    val resistanceProximityPct: Double,
    val minLegs: Int,
    val failClosed: Boolean,
    val useImpulseCorrection: Boolean,
    val useTrendLevels: Boolean,
    val useFalseBreakout: Boolean,
    val useTriangle: Boolean,
) {
    companion object {
        /**
         * Единый мост FilterSettings → DarsConfig: один и тот же конфиг питает и торговый
         * движок [DarsEntryEngine], и информационный анализатор [MarketAnalysis], поэтому
         * «второе мнение» считается на тех же порогах, что и реальный вход.
         */
        fun from(s: FilterSettings) = DarsConfig(
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
    }
}

/** Ценовой уровень (поддержка/сопротивление). */
data class Level(
    val price: Double,
    val kind: String,      // "swing" | "big_bar" | "reversal" | "mirror"
    val touches: Int = 1,
)

/** Итог анализа Dars по одной монете. */
data class DarsSignal(
    val passed: Boolean,
    val direction: TrendDirection = TrendDirection.FLAT,
    val setup: DarsSetup? = null,
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
    /**
     * Цель тейк-профита как доля хода от цены входа до ближайшего сопротивления сверху
     * (Урок 2: «цель фиксируем у следующего уровня»). null — уровня сверху нет
     * (пробой к новым максимумам), тогда стратегия берёт механический TP%.
     */
    val targetFrac: Double? = null,
    /**
     * Дистанция структурного стопа как доля ВНИЗ от цены входа до уровня под минимумом
     * последней коррекции (стоп «за структурой», а не механический SL%). null — структуру
     * определить не удалось, тогда стратегия берёт механический стоп-лосс%.
     */
    val stopFrac: Double? = null,
) {
    /** Короткое человекочитаемое описание для логов/Telegram. */
    fun describe(): String = buildString {
        append(if (passed) "PASS" else "SKIP")
        setup?.let { append(" $it") }
        append(" $direction")
        if (reasons.isNotEmpty()) append(": ${reasons.joinToString("; ")}")
    }

    companion object {
        fun reject(vararg reasons: String) = DarsSignal(false, reasons = reasons.toList())
        fun reject(reasons: List<String>) = DarsSignal(false, reasons = reasons)
        fun pass(setup: DarsSetup, direction: TrendDirection, score: Int, vararg reasons: String) =
            DarsSignal(true, direction, setup, score, reasons.toList())
    }
}
