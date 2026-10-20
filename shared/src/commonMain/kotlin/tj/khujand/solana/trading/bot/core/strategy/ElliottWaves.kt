package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.math.abs

/*
 * Разметка волн по Frost & Prechter, «Elliott Wave Principle» (Wiley). Порт из MRX.
 *
 * Здесь только геометрия: где волны начинаются и кончаются, на что каждая делится на младшей
 * степени и какой фигурой выглядит. Что из этого торговать, решает ElliottStrategy.
 */

/**
 * Точка разворота — конец одной волны и начало следующей.
 *
 * [confirmedAt] — бар, на котором разворот стал фактом: цена отошла от экстремума на порог
 * степени. До этого бара точка — только текущий экстремум незаконченной волны, и решение по
 * ней перерисовывалось бы. -1 — точка ещё не подтверждена (конец текущей волны).
 */
data class ElliottPoint(
    val index: Int,
    val price: Double,
    val isHigh: Boolean,
    val confirmedAt: Int = -1,
)

/** Волны одной степени: подтверждённые развороты и куда дошла текущая, ещё идущая волна. */
data class ElliottSwings(
    val points: List<ElliottPoint>,
    /** Экстремум текущей волны. null — данных не хватило даже на первую волну. */
    val running: ElliottPoint?,
)

/**
 * Как волна выглядит на младшей степени (гл. 1 книги).
 *
 * Движущая волна делится на 5 (импульс или диагональ), коррекция — на 3. Семейства по числу
 * ног: 5, 9, 13 — импульс (9 — импульс с растяжением), 3, 7, 11 — коррекция (7 — двойная
 * тройка). Одна нога — волна на этом масштабе не делится, её строение просто не видно.
 */
enum class ElliottShape(val title: String, val sideways: Boolean = false) {
    SIMPLE("не делится"),
    IMPULSE("импульс"),
    EXTENDED("импульс с растяжением"),
    DIAGONAL("диагональ"),
    THREE("тройка"),
    ZIGZAG("зигзаг"),
    /** Боковые коррекции — им противопоставлен резкий зигзаг в ориентире чередования. */
    FLAT("плоская", sideways = true),
    TRIANGLE("треугольник", sideways = true),
    COMBINATION("комбинация", sideways = true),
    FIVE_AGAINST("пятёрка против тренда"),
    IRREGULAR("правила импульса нарушены"),
}

/** Строение одной волны: её развороты младшей степени (от начала до конца) и фигура. */
data class ElliottStructure(val points: List<ElliottPoint>, val shape: ElliottShape) {
    val legs: Int get() = points.size - 1

    /**
     * Зона волны 4 младшей степени: от конца (iii) до конца (iv). Ориентир глубины (гл. 2):
     * следующая коррекция чаще всего заканчивается в этой зоне. null — пятёрка не видна.
     */
    val fourthSpan: ClosedFloatingPointRange<Double>?
        get() {
            if (legs != 5 || (shape != ElliottShape.IMPULSE && shape != ElliottShape.DIAGONAL)) return null
            val a = points[3].price
            val b = points[4].price
            return minOf(a, b)..maxOf(a, b)
        }

    val text: String get() = "${shape.title} ($legs)"
}

/**
 * Волны одной степени по свечам.
 *
 * Разворот засчитывается, когда цена отошла от экстремума текущей волны на [thresholdAt] —
 * порог степени в цене (у стратегии это доля ATR на баре экстремума). Всё, что меньше, —
 * волны младшей степени внутри текущей. Бар, который в одном диапазоне и обновил экстремум,
 * и отошёл от него на порог, разворотом не считается: порядок цен внутри бара неизвестен.
 */
internal fun elliottSwings(candles: List<Candle>, thresholdAt: (Int) -> Double): ElliottSwings {
    if (candles.size < 2) return ElliottSwings(emptyList(), null)
    val points = mutableListOf<ElliottPoint>()
    var dir = 0
    var hi = 0
    var lo = 0
    var ext = 0

    for (i in 1 until candles.size) {
        val c = candles[i]
        if (dir == 0) {
            if (c.high > candles[hi].high) hi = i
            if (c.low < candles[lo].low) lo = i
            val down = hi < i && candles[hi].high - c.low >= thresholdAt(hi)
            val up = lo < i && c.high - candles[lo].low >= thresholdAt(lo)
            // Первая волна — от того крайнего значения, которое было раньше.
            if (down && (!up || hi < lo)) {
                points += ElliottPoint(hi, candles[hi].high, isHigh = true, confirmedAt = i)
                dir = -1
                ext = (hi + 1..i).minBy { candles[it].low }
            } else if (up) {
                points += ElliottPoint(lo, candles[lo].low, isHigh = false, confirmedAt = i)
                dir = 1
                ext = (lo + 1..i).maxBy { candles[it].high }
            } else continue
        }
        if (dir > 0) {
            if (c.high > candles[ext].high) ext = i
            else if (ext != i && candles[ext].high - c.low >= thresholdAt(ext)) {
                points += ElliottPoint(ext, candles[ext].high, isHigh = true, confirmedAt = i)
                dir = -1
                ext = i
            }
        } else {
            if (c.low < candles[ext].low) ext = i
            else if (ext != i && c.high - candles[ext].low >= thresholdAt(ext)) {
                points += ElliottPoint(ext, candles[ext].low, isHigh = false, confirmedAt = i)
                dir = 1
                ext = i
            }
        }
    }
    val running = when {
        dir > 0 -> ElliottPoint(ext, candles[ext].high, isHigh = true)
        dir < 0 -> ElliottPoint(ext, candles[ext].low, isHigh = false)
        else -> null
    }
    return ElliottSwings(points, running)
}

/**
 * Развороты младшей степени внутри одной волны — от её начала [from] до конца [to].
 *
 * Тот же алгоритм, что [elliottSwings], но направление известно заранее: волна от минимума
 * идёт вверх. Концы закреплены: внутри волны её начало — крайняя точка с одной стороны,
 * конец — с другой (так их нашёл разворот старшей степени).
 */
internal fun elliottSubWaves(
    candles: List<Candle>,
    from: ElliottPoint,
    to: ElliottPoint,
    thresholdAt: (Int) -> Double,
): List<ElliottPoint> {
    val inner = mutableListOf<ElliottPoint>()
    var dir = if (from.isHigh) -1 else 1
    var ext = from.index
    for (i in from.index + 1..to.index) {
        val c = candles[i]
        if (dir > 0) {
            if (c.high > candles[ext].high) ext = i
            else if (ext != i && candles[ext].high - c.low >= thresholdAt(ext)) {
                inner += ElliottPoint(ext, candles[ext].high, isHigh = true, confirmedAt = i)
                dir = -1
                ext = i
            }
        } else {
            if (c.low < candles[ext].low) ext = i
            else if (ext != i && c.high - candles[ext].low >= thresholdAt(ext)) {
                inner += ElliottPoint(ext, candles[ext].low, isHigh = false, confirmedAt = i)
                dir = 1
                ext = i
            }
        }
    }
    // Волна кончилась встречным движением, которое ещё не стало разворотом: его экстремум —
    // тоже точка младшей степени (кроме бара самого конца, где он вырождается в ноль).
    val towardEnd = if (to.isHigh) 1 else -1
    if (dir != towardEnd && ext != to.index && ext != from.index) {
        inner += ElliottPoint(ext, if (dir > 0) candles[ext].high else candles[ext].low, isHigh = dir > 0)
    }

    // Чередование вершин и впадин: две подряд одного вида склеиваются в более крайнюю.
    // Начало и конец крайние по построению, поэтому они всегда остаются.
    val out = mutableListOf(from)
    for (p in inner.filter { it.index in from.index + 1 until to.index } + to) {
        val last = out.last()
        if (last.isHigh == p.isHigh) {
            val better = if (p.isHigh) p.price >= last.price else p.price <= last.price
            if (better && last !== from) out[out.lastIndex] = p
        } else out += p
    }
    return out
}

/** Движущая волна (1, 3, 5): ждём пятёрку, растянутую пятёрку или диагональ. */
internal fun classifyMotive(points: List<ElliottPoint>): ElliottShape {
    val legs = points.size - 1
    return when {
        legs <= 1 -> ElliottShape.SIMPLE
        legs % 4 == 3 -> ElliottShape.THREE
        legs == 5 -> impulseOrDiagonal(points)
        else -> ElliottShape.EXTENDED
    }
}

/**
 * Корректирующая волна (2, 4): ждём тройку.
 *
 * Тройка — зигзаг или плоская: у плоской волна B возвращается почти к началу A (≥ 90 %), у
 * зигзага — нет. 7 и 11 ног — двойная и тройная тройка. Пятёрка с конца в крайней точке —
 * это либо расширяющийся треугольник, либо импульс против тренда, то есть не коррекция.
 * Сужающийся треугольник кончается не в крайней точке и на этой разметке виден не будет.
 */
internal fun classifyCorrective(points: List<ElliottPoint>): ElliottShape {
    val legs = points.size - 1
    return when {
        legs <= 1 -> ElliottShape.SIMPLE
        legs == 3 -> if (leg(points, 2) >= 0.9 * leg(points, 1)) ElliottShape.FLAT else ElliottShape.ZIGZAG
        legs % 4 == 3 -> ElliottShape.COMBINATION
        legs == 5 && leg(points, 3) > leg(points, 1) && leg(points, 5) > leg(points, 3) &&
            leg(points, 4) > leg(points, 2) -> ElliottShape.TRIANGLE
        else -> ElliottShape.FIVE_AGAINST
    }
}

/**
 * Три правила импульса (гл. 1): волна 2 не уходит за начало 1, волна 3 уходит дальше конца 1
 * и не бывает самой короткой. Волна 4 на территории волны 1 — уже не импульс, а диагональ.
 */
private fun impulseOrDiagonal(p: List<ElliottPoint>): ElliottShape {
    val s = if (p[0].isHigh) -1.0 else 1.0
    val rule2 = s * (p[2].price - p[0].price) > 0.0
    val beyond = s * (p[3].price - p[1].price) > 0.0
    val w1 = leg(p, 1)
    val w3 = leg(p, 3)
    val w5 = leg(p, 5)
    val notShortest = !(w3 < w1 && w3 < w5)
    if (!rule2 || !beyond || !notShortest) return ElliottShape.IRREGULAR
    val overlap = s * (p[4].price - p[1].price) <= 0.0
    return if (overlap) ElliottShape.DIAGONAL else ElliottShape.IMPULSE
}

/** Длина k-й ноги (с единицы) в цене. */
private fun leg(points: List<ElliottPoint>, k: Int): Double = abs(points[k].price - points[k - 1].price)
