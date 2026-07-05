package tj.khujand.solana.trading.bot.domain.dars

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
)

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
