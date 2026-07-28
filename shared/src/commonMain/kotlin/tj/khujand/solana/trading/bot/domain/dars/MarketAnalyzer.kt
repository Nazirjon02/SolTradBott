package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Информационный анализ монеты по методике «Dars» — БЕЗ торговли (порт MarketAnalyzer из MRX,
 * адаптированный под спотовый лонговый DEX-бот).
 *
 * Отвечает на три вопроса, которые методичка задаёт перед каждым входом:
 *  1. Какой тренд на старшем таймфрейме? (Урок 2 — работаем только по нему; бот лонговый → нужен UP)
 *  2. В какой фазе рынок и что говорят 4 признака Урока 1 (скорость, размер баров, где закрываются
 *     бары, объём) — есть ли одна доминирующая сторона?
 *  3. Где примерно зона входа, стоп и цель — и что мешает войти прямо сейчас (Урок 4)?
 *
 * Это «второе мнение» к боту: торговый [DarsEntryEngine] молча возвращает SKIP, а анализатор
 * объясняет, ПОЧЕМУ входа нет. Считает на тех же примитивах ([LegSegmenter], [TrendAnalyzer],
 * [LevelDetector], [TriangleDetector], [AccumulationGate]) и на том же [DarsConfig], поэтому
 * картина совпадает с реальными решениями движка.
 */

/** Фаза рынка — как её описывает методичка. */
enum class MarketPhase(val label: String) {
    IMPULSE("Импульс"),
    CORRECTION("Коррекция"),
    ACCUMULATION("Накопление"),
    SHOCK("Резкое движение"),
    FLAT("Боковик / нет тренда"),
}

/** Насколько сетап готов ко входу. */
enum class Readiness(val label: String, val emoji: String) {
    READY("Готов ко входу", "🟢"),
    WAIT("Формируется — ждём", "🟡"),
    IGNORE("Вход игнорируется", "🔴"),
}

/**
 * Разбор ноги по 4 признакам Урока 1. Числа — отношения к «обычному» бару графика
 * (1.0 = как обычно), лейблы — словесная шкала методички.
 */
data class LegCharacter(
    /** Скорость движения цены относительно средней (1.0 = средняя). */
    val speed: Double,
    /** Средний размер тела бара относительно среднего по графику. */
    val barSize: Double,
    /** Сила закрытий по направлению ноги, 0..1. */
    val closeLocation: Double,
    /** Средний объём относительно среднего по графику. */
    val volume: Double,
) {
    val speedLabel: String get() = tier(speed, "высокая", "средняя", "низкая")
    val barSizeLabel: String get() = tier(barSize, "большие", "средние", "маленькие")
    val volumeLabel: String get() = tier(volume, "высокий", "средний", "низкий")

    /** Где закрываются бары — формулировка Урока 1. */
    val closeLabel: String get() = when {
        closeLocation >= 0.66 -> "на своих экстремумах"
        closeLocation >= 0.40 -> "на серединах"
        else -> "около открытия"
    }

    private fun tier(v: Double, high: String, mid: String, low: String) = when {
        v >= 1.2 -> high
        v >= 0.8 -> mid
        else -> low
    }
}

/**
 * Примерная зона входа, стоп и цель. Не приказ на вход, а ориентир (бот спотовый → всегда лонг).
 */
data class EntryPlan(
    /** Нижняя граница зоны входа (минимум коррекции). */
    val zoneLow: Double,
    /** Верхняя граница зоны входа. */
    val zoneHigh: Double,
    /** Цена, от которой посчитаны стоп, цель и R:R. */
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val riskReward: Double,
    /** Что должно произойти, чтобы вход стал валидным (Урок 3/5). */
    val trigger: String,
) {
    /** Ход до стопа в % от цены входа — понятнее абсолютных чисел на незнакомой монете. */
    val riskPercent: Double
        get() = if (entryPrice > 0.0) abs(entryPrice - stopLoss) / entryPrice * 100.0 else 0.0

    /** Ход до цели в % от цены входа. */
    val rewardPercent: Double
        get() = if (entryPrice > 0.0) abs(takeProfit - entryPrice) / entryPrice * 100.0 else 0.0
}

/**
 * Когда и по каким данным сделан разбор. Без этого отчёт нельзя проверить: «тренд вверх»
 * по только что закрытому бару и по бару часовой давности — разные утверждения.
 */
data class AnalysisTiming(
    /** Момент расчёта, epoch millis. */
    val analyzedAt: Long,
    /** Время открытия последнего бара рабочего ТФ, epoch millis. */
    val lastBarTime: Long,
    /** Момент закрытия текущего бара рабочего ТФ, epoch millis. */
    val nextBarAt: Long,
    /** Сколько баров рабочего ТФ участвовало в разборе. */
    val bars: Int,
    /** Сколько баров старшего ТФ участвовало в определении тренда. */
    val trendBars: Int,
    /** Тренд взят со старшего ТФ (true) или со структуры рабочего — истории не хватило (false). */
    val trendFromHigherTimeframe: Boolean,
) {
    /** Сколько осталось до закрытия текущего бара, мс. 0 — бар уже должен был закрыться. */
    val msToBarClose: Long get() = (nextBarAt - analyzedAt).coerceAtLeast(0L)

    /** Насколько данные отстали от момента расчёта, мс. */
    val dataAgeMs: Long get() = (analyzedAt - lastBarTime).coerceAtLeast(0L)
}

/**
 * Рыночный фон из метрик сканера (DexScreener): суточная динамика, ликвидность, оборот,
 * давление покупок. Разбор структуры этого не видит, а на «стоит ли вообще смотреть монету» влияет.
 */
data class MarketContext(
    val priceUsd: Double,
    val changeM5Percent: Double,
    val changeH1Percent: Double,
    val liquidityUsd: Double,
    val marketCap: Double,
    val volumeH1Usd: Double,
    val buysH1: Int,
    val sellsH1: Int,
    val tokenAgeMinutes: Long,
) {
    /** Доля покупок в объёме сделок за час, 0..1; null — сделок не было. */
    val buyRatioH1: Double? get() {
        val total = buysH1 + sellsH1
        return if (total > 0) buysH1.toDouble() / total else null
    }
}

/** Итог разбора одной монеты. */
data class CoinAnalysis(
    val symbol: String,
    /** Адрес смарт-контракта (mint) монеты — для копирования и открытия в explorer. */
    val mint: String = "",
    /** URL реальной иконки монеты (лого токена), null — иконки нет. */
    val iconUrl: String? = null,
    val price: Double,
    val trend: TrendDirection,
    /** Таймфрейм тренда, человекочитаемо ("15m", "1h"). */
    val trendTimeframe: String,
    /** Рабочий таймфрейм входа, человекочитаемо ("1m", "5m"). */
    val entryTimeframe: String,
    val phase: MarketPhase,
    val readiness: Readiness,
    /** Общий балл 0..100 — по нему сортируем «лучшую монету». */
    val score: Int,
    /** Урок 1: насколько текущий импульс сильнее прошлого, 0..1. */
    val dominance: Double,
    /** Урок 1: насколько присутствует вторая сторона, 0..1 (чем ниже — тем лучше). */
    val opposition: Double,
    val impulse: LegCharacter?,
    val correction: LegCharacter?,
    /** Название сработавшего сетапа, если он есть. */
    val setup: String?,
    val entry: EntryPlan?,
    val support: Level?,
    val resistance: Level?,
    /** Урок 4: что запрещает вход прямо сейчас. Пусто = запретов нет. */
    val blockers: List<String>,
    /** Что видно на графике — человеческим языком. */
    val notes: List<String>,
    /** Время расчёта и глубина данных. */
    val timing: AnalysisTiming,
    /** Суточный фон из сканера. */
    val market: MarketContext,
    /** Сколько баров прошло с конца последней коррекции. null — структура не читается. */
    val barsSinceCorrection: Int? = null,
) {
    /** «1h → 5m» — направление берём со старшего ТФ, вход ищем на рабочем (Урок 2). */
    val timeframeLabel: String get() = "$trendTimeframe → $entryTimeframe"
}

/**
 * Чистая функция разбора: свечи → отчёт. Отделена от сети ([MarketAnalyzerService]),
 * чтобы гонять на синтетических свечах в тестах без биржи.
 */
object MarketAnalysis {

    private const val MIN_CANDLES = 12
    private const val HTF_MIN_BARS = 6
    private const val MAX_BARS_SINCE_CORRECTION = 12
    private const val PRICE_STOP_BUFFER = 0.005   // 0.5% под минимумом коррекции, как в движке
    private const val TARGET_RR = 2.0             // механическая цель, если уровня впереди нет

    fun analyze(
        symbol: String,
        candles: List<Candle>,        // рабочий ТФ
        trendCandles: List<Candle>,   // старший ТФ
        market: MarketContext,
        cfg: DarsConfig,
        /** Адрес смарт-контракта (mint) — прокидывается в отчёт для копирования/иконки. */
        mint: String = "",
        /** URL реальной иконки монеты, null — иконки нет. */
        iconUrl: String? = null,
        /** Момент расчёта. Параметр, а не Clock внутри, — чтобы тесты были детерминированными. */
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): CoinAnalysis {
        val price = candles.lastOrNull()?.close ?: market.priceUsd
        val notes = mutableListOf<String>()
        val blockers = mutableListOf<String>()

        val entryTf = tfLabel(cfg.entryTf, cfg.entryTfAggregate)
        val trendTf = tfLabel(cfg.higherTf, cfg.higherTfAggregate)
        val entryBarMs = barMillis(cfg.entryTf, cfg.entryTfAggregate)
        val lastBarTime = candles.lastOrNull()?.openTimeMs ?: 0L
        val trendFromHigher = trendCandles.size >= HTF_MIN_BARS
        val timing = AnalysisTiming(
            analyzedAt = now,
            lastBarTime = lastBarTime,
            nextBarAt = if (lastBarTime > 0L) lastBarTime + entryBarMs else 0L,
            bars = candles.size,
            trendBars = trendCandles.size,
            trendFromHigherTimeframe = trendFromHigher,
        )

        fun result(
            trend: TrendDirection,
            phase: MarketPhase,
            readiness: Readiness,
            dominance: Double = 0.0,
            opposition: Double = 0.0,
            impulse: LegCharacter? = null,
            correction: LegCharacter? = null,
            setup: String? = null,
            entry: EntryPlan? = null,
            support: Level? = null,
            resistance: Level? = null,
            barsSince: Int? = null,
        ) = CoinAnalysis(
            symbol = symbol, mint = mint, iconUrl = iconUrl, price = price, trend = trend,
            trendTimeframe = trendTf, entryTimeframe = entryTf,
            phase = phase, readiness = readiness,
            score = scoreOf(trend, dominance, opposition, setup, entry, blockers),
            dominance = dominance, opposition = opposition,
            impulse = impulse, correction = correction, setup = setup, entry = entry,
            support = support, resistance = resistance,
            blockers = blockers, notes = notes, timing = timing, market = market,
            barsSinceCorrection = barsSince,
        )

        // Данных мало — честно говорим об этом, а не выдаём случайный вердикт.
        if (candles.size < MIN_CANDLES) {
            blockers += "Недостаточно истории (${candles.size} баров $entryTf, нужно $MIN_CANDLES+)"
            return result(TrendDirection.FLAT, MarketPhase.FLAT, Readiness.IGNORE)
        }

        // ─── Урок 2: тренд старшего таймфрейма (бот лонговый → нужен UP) ───
        val trend = if (trendFromHigher) TrendAnalyzer.trend(trendCandles, cfg.swingPivotPct)
                    else TrendAnalyzer.trend(candles, cfg.swingPivotPct)
        val trendSource = if (trendFromHigher) trendTf else "структуре $entryTf (старшего ТФ не хватило)"
        notes += when (trend) {
            TrendDirection.UP -> "Тренд по $trendSource — восходящий ▲, работаем от покупок"
            TrendDirection.DOWN -> "Тренд по $trendSource — нисходящий ▼, лонг по методике не берём"
            TrendDirection.FLAT -> "Чёткого тренда по $trendSource нет — по методике это не наш рынок"
        }
        notes += "Разбор на $entryTf: ${candles.size} баров ≈ ${formatSpan(candles.size * entryBarMs)} истории"

        // Уровни (Урок 2) — на старшем ТФ, если он есть; иначе на рабочем.
        val levelSource = if (trendFromHigher) trendCandles else candles
        val levels = LevelDetector.detect(levelSource, cfg.swingPivotPct)
        val support = LevelDetector.nearestSupport(levels, price)
        val resistance = LevelDetector.nearestResistance(levels, price)

        // Лонг-бот: любой тренд, кроме восходящего, — не наш рынок.
        if (trend != TrendDirection.UP) {
            blockers += "Тренд $trendTf не восходящий ($trend) — по Уроку 2 лонг не рассматриваем"
            return result(trend, MarketPhase.FLAT, Readiness.IGNORE, support = support, resistance = resistance)
        }

        // ─── Структура: ноги → импульсы и коррекции ───
        // Текущая коррекция — последняя нога вниз, а текущий импульс — нога вверх ПЕРЕД ней
        // (как в [ImpulseCorrectionAnalyzer]). Пара «импульс→коррекция» берётся именно так,
        // а не «последний импульс», потому что подтверждающий бар разворота образует свежую
        // короткую ногу вверх ПОСЛЕ коррекции — её нельзя путать с самим импульсом.
        val legs = LegSegmenter.segment(candles, cfg.swingPivotPct)
        val corrIdx = legs.indexOfLast { it.direction == TrendDirection.DOWN }
        if (legs.size < cfg.minLegs || corrIdx < 1 || legs[corrIdx - 1].direction != TrendDirection.UP) {
            blockers += "Структура $entryTf не читается — мало подтверждённых свингов (${legs.size} ног)"
            return result(trend, MarketPhase.FLAT, Readiness.IGNORE, support = support, resistance = resistance)
        }
        val curCorrection = legs[corrIdx]
        val curImpulse = legs[corrIdx - 1]
        val prevCorrIdx = (0 until corrIdx).lastOrNull { legs[it].direction == TrendDirection.DOWN }
        val prevCorrection = prevCorrIdx?.let { legs[it] }
        val prevImpulse = prevCorrIdx?.let { idx -> legs.getOrNull(idx - 1)?.takeIf { it.direction == TrendDirection.UP } }

        // Базы для нормировки признаков — «обычный» бар на этом графике.
        val baseRange = candles.takeLast(50).map { it.range }.average().takeIf { it > 0.0 } ?: 1.0
        val baseBody = candles.takeLast(50).map { it.body }.average().takeIf { it > 0.0 } ?: 1.0
        val baseVolume = candles.takeLast(50).map { it.volume }.average().takeIf { it > 0.0 } ?: 1.0

        val impulseChar = characterOf(curImpulse, baseRange, baseBody, baseVolume)
        val correctionChar = characterOf(curCorrection, baseRange, baseBody, baseVolume)

        // ─── Урок 1: доминирование и вторая сторона ───
        // Доминирование = текущий импульс сильнее прошлого (тренд ещё «дышит»). Если прошлого
        // импульса в окне нет — судим по силе самого импульса относительно коррекции.
        val dominance = if (prevImpulse != null) listOf(
            ratioScore(curImpulse.speed, prevImpulse.speed),
            ratioScore(curImpulse.avgBarSize, prevImpulse.avgBarSize),
            curImpulse.closeStrength,
            ratioScore(curImpulse.avgVolume, prevImpulse.avgVolume),
        ).average() else listOf(
            ratioScore(curImpulse.speed, curCorrection.speed),
            curImpulse.closeStrength,
        ).average()
        val opposition = listOf(
            ratioScore(curCorrection.speed, curImpulse.speed),
            ratioScore(curCorrection.avgBarSize, curImpulse.avgBarSize),
            curCorrection.closeStrength,
            ratioScore(curCorrection.avgVolume, curImpulse.avgVolume),
        ).average()

        notes += "Импульс: скорость ${impulseChar.speedLabel}, бары ${impulseChar.barSizeLabel}, " +
            "закрытия ${impulseChar.closeLabel}, объём ${impulseChar.volumeLabel}"
        notes += "Коррекция: скорость ${correctionChar.speedLabel}, бары ${correctionChar.barSizeLabel}, " +
            "закрытия ${correctionChar.closeLabel}, объём ${correctionChar.volumeLabel}"

        // ─── Урок 1/4: те же гейты, что и у движка, но собираем ВСЕ запреты сразу ───
        if (curImpulse.speed < curCorrection.speed * cfg.dominanceRatio) {
            blockers += "Импульс не доминирует — не быстрее коррекции в ${fmt2(cfg.dominanceRatio)}×"
        }
        if (curCorrection.maxVolume > curImpulse.maxVolume * 1.2) {
            blockers += "Внутри коррекции аномальный объём против нас (вторая сторона активна)"
        }
        if (prevCorrection != null &&
            (curCorrection.speed > prevCorrection.speed * 1.3 || curCorrection.sizeAbs > prevCorrection.sizeAbs * 1.3)
        ) {
            blockers += "Текущая коррекция сильнее предыдущей — характер поведения цены изменился"
        }
        val lenRatio = if (curImpulse.bars <= 0) 0.0 else curCorrection.bars.toDouble() / curImpulse.bars * 100.0
        if (lenRatio < cfg.minCorrectionLenPct) {
            blockers += "Коррекция короткая — ${lenRatio.toInt()}% длины импульса (нужно ≥ ${cfg.minCorrectionLenPct.toInt()}%)"
        }
        val retrace = if (curImpulse.sizeAbs > 0.0) curCorrection.sizeAbs / curImpulse.sizeAbs else 0.0
        if (retrace > 1.0) {
            blockers += "Коррекция глубже импульса (${(retrace * 100).toInt()}%) — это слом тренда, а не откат"
        }
        if (cfg.rejectAtResistance && resistance != null && price > 0.0) {
            val distPct = (resistance.price - price) / price * 100.0
            if (distPct <= cfg.resistanceProximityPct) {
                blockers += "Цена у сопротивления ${fmtPrice(resistance.price)} (${fmt1(distPct)}%) — не покупаем у сопротивления"
            }
        }

        // ─── Фаза рынка ───
        val shockPending = AccumulationGate.blocksEntry(candles)
        val phase = when {
            shockPending -> MarketPhase.SHOCK
            isAccumulating(candles) -> MarketPhase.ACCUMULATION
            legs.last().direction == TrendDirection.UP -> MarketPhase.IMPULSE
            else -> MarketPhase.CORRECTION
        }
        if (shockPending) {
            blockers += "Было резкое движение без накопления — по Уроку 4 ждём консолидации"
        }

        // ─── Сетап (Урок 3 / Урок 5) ───
        val signalBar = candles.last()
        val avgRangeRecent = candles.takeLast(20).map { it.range }.average().coerceAtLeast(1e-12)
        val aggressive = signalBar.range >= avgRangeRecent * 1.1 && signalBar.closePosition >= 0.66
        val triangle = TriangleDetector.detect(candles, legs, cfg).passed
        val brokeOut = signalBar.close > curImpulse.endPrice
        val setup = when {
            triangle && brokeOut && aggressive -> "Треугольник — пробой (Урок 5)"
            triangle -> "Треугольник — сужение волн, ждём пробой (Урок 5)"
            aggressive && phase == MarketPhase.CORRECTION -> "Ложный пробой — агрессивный бар (Урок 3)"
            phase == MarketPhase.CORRECTION -> "Откат к уровню — ждём агрессивный бар (Урок 3)"
            else -> null
        }
        if (triangle) notes += "Коррекционные волны сужаются — рисуется треугольник, обычно пробой на 3–4 касание"

        // ─── Свежесть (Урок 3/4: не догоняем ушедшее движение) ───
        val barsSince = (candles.size - 1) - curCorrection.endIndex
        notes += "Коррекция закончилась $barsSince баров $entryTf назад " +
            "(≈ ${formatSpan(barsSince.toLong() * entryBarMs)}) — сетапу столько времени"
        if (barsSince > MAX_BARS_SINCE_CORRECTION) {
            blockers += "Движение ушло — $barsSince баров $entryTf с конца коррекции (макс $MAX_BARS_SINCE_CORRECTION)"
        }

        // ─── Примерная зона входа ───
        val entry = buildEntryPlan(price, curImpulse, curCorrection, levels, aggressive, triangle)
        if (entry != null && entry.riskReward < 1.0) {
            blockers += "До ближайшего уровня риск/прибыль ${fmt2(entry.riskReward)} — цель слишком близко"
        }

        val readiness = when {
            blockers.isNotEmpty() -> Readiness.IGNORE
            entry == null -> Readiness.WAIT
            aggressive && (triangle && brokeOut || phase == MarketPhase.CORRECTION) -> Readiness.READY
            else -> Readiness.WAIT
        }

        return result(
            trend, phase, readiness,
            dominance = dominance, opposition = opposition,
            impulse = impulseChar, correction = correctionChar,
            setup = setup, entry = entry, support = support, resistance = resistance,
            barsSince = barsSince,
        )
    }

    // ─── Вспомогательное ───────────────────────────────────────────────────────

    private fun characterOf(leg: Leg, baseRange: Double, baseBody: Double, baseVolume: Double) = LegCharacter(
        speed = leg.speed / baseRange,
        barSize = leg.avgBarSize / baseBody,
        closeLocation = leg.closeStrength,
        volume = leg.avgVolume / baseVolume,
    )

    /**
     * Зона входа, стоп и цель (Урок 2: цель — следующий уровень; бот лонговый).
     * Зона — от минимума коррекции вверх в сторону импульса. Стоп — под минимумом с буфером.
     * Цель — ближайшее сопротивление сверху, а если его нет — механическая по [TARGET_RR].
     */
    private fun buildEntryPlan(
        price: Double,
        curImpulse: Leg,
        curCorrection: Leg,
        levels: List<Level>,
        aggressive: Boolean,
        triangle: Boolean,
    ): EntryPlan? {
        val extreme = curCorrection.endPrice           // минимум коррекции
        val swingDepth = abs(curImpulse.endPrice - extreme)
        if (swingDepth <= 0.0) return null

        val stopLoss = extreme * (1.0 - PRICE_STOP_BUFFER)

        // Зона входа — треть свинга вверх от минимума коррекции.
        val band = swingDepth * 0.35
        val zoneLow = extreme
        val zoneHigh = extreme + band

        // Вход считаем от текущей цены, если она уже в зоне, иначе от середины зоны.
        val entryPrice = if (price in zoneLow..zoneHigh) price else (zoneLow + zoneHigh) / 2.0
        val risk = entryPrice - stopLoss
        if (risk <= 0.0) return null

        val mechanical = entryPrice + TARGET_RR * risk
        val levelPrice = LevelDetector.nearestResistance(levels, entryPrice)?.price
        val takeProfit = when {
            levelPrice == null -> mechanical
            levelPrice >= mechanical -> mechanical
            else -> levelPrice * (1.0 - PRICE_STOP_BUFFER)   // уровень на пути — целимся чуть перед ним
        }

        val trigger = when {
            triangle -> "Пробой треугольника импульсным баром с закрытием у максимума"
            aggressive -> "Агрессивный бар уже сформирован — вход по его закрытию"
            else -> "Ложный пробой ${fmtPrice(extreme)} и возврат: нужен импульсный бар с закрытием у максимума"
        }

        return EntryPlan(
            zoneLow = zoneLow, zoneHigh = zoneHigh, entryPrice = entryPrice,
            stopLoss = stopLoss, takeProfit = takeProfit,
            riskReward = if (risk > 0.0) (takeProfit - entryPrice) / risk else 0.0,
            trigger = trigger,
        )
    }

    /**
     * Общий балл 0..100 для сортировки «лучшая монета сейчас». Наверх идут монеты с чистым
     * восходящим трендом, сильным импульсом, тихой коррекцией и без запретов Урока 4.
     */
    private fun scoreOf(
        trend: TrendDirection,
        dominance: Double,
        opposition: Double,
        setup: String?,
        entry: EntryPlan?,
        blockers: List<String>,
    ): Int {
        if (trend != TrendDirection.UP) return 0
        var s = 20.0                                    // есть восходящий тренд — уже база
        s += dominance * 30.0                           // сила доминирующей стороны
        s += (1.0 - opposition) * 20.0                  // отсутствие второй стороны
        if (setup != null) s += 15.0                    // сетап читается
        if (entry != null) s += (entry.riskReward / 3.0).coerceAtMost(1.0) * 15.0
        s -= blockers.size * 12.0                        // каждый запрет Урока 4 бьёт по баллу
        return s.coerceIn(0.0, 100.0).toInt()
    }

    /** Последние бары идут в сжатом диапазоне — рынок накапливает. */
    private fun isAccumulating(candles: List<Candle>): Boolean {
        val n = 8
        if (candles.size < n + 20) return false
        val recent = candles.takeLast(n).map { it.range }.average()
        val before = candles.subList(candles.size - n - 20, candles.size - n).map { it.range }.average()
        if (before <= 0.0) return false
        return recent <= before * 0.6
    }

    private fun ratioScore(cur: Double, base: Double): Double {
        if (base <= 0.0) return if (cur > 0.0) 1.0 else 0.5
        return (cur / base / 2.0).coerceIn(0.0, 1.0)   // cur==base → 0.5, cur==2×base → 1.0
    }

    private fun barMillis(tf: String, agg: Int): Long {
        val unit = when (tf.lowercase()) {
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            else -> 60_000L
        }
        return unit * agg.coerceAtLeast(1)
    }

    private fun tfLabel(tf: String, agg: Int): String = when (tf.lowercase()) {
        "minute" -> "${agg}m"
        "hour" -> "${agg}h"
        "day" -> "${agg}d"
        else -> "$tf×$agg"
    }

    /** «40 сек», «25 мин», «3 ч 15 мин», «2 д 4 ч» — округление до двух значащих единиц. */
    fun formatSpan(ms: Long): String {
        if (ms <= 0L) return "0 мин"
        val totalMin = ms / 60_000L
        if (totalMin < 1L) return "${ms / 1000L} сек"
        val days = totalMin / 1440L
        val hours = (totalMin % 1440L) / 60L
        val minutes = totalMin % 60L
        return when {
            days > 0L && hours > 0L -> "$days д $hours ч"
            days > 0L -> "$days д"
            hours > 0L && minutes > 0L -> "$hours ч $minutes мин"
            hours > 0L -> "$hours ч"
            else -> "$minutes мин"
        }
    }

    private fun fmt1(v: Double): String {
        val r = (v * 10).toLong()
        return "${r / 10}.${abs(r % 10)}"
    }

    private fun fmt2(v: Double): String {
        val r = (v * 100).toLong()
        return "${r / 100}.${abs(r % 100).toString().padStart(2, '0')}"
    }

    /** Цена с разумным числом знаков: у дешёвых мемкоинов нужно больше точности. */
    fun fmtPrice(v: Double): String {
        val decimals = when {
            v >= 1000 -> 2
            v >= 1 -> 4
            v >= 0.01 -> 6
            else -> 9
        }
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = kotlin.math.round(abs(v) * scale).toLong()
        val sign = if (v < 0) "-" else ""
        val frac = (scaled % scale).toString().padStart(decimals, '0')
        return "$sign${scaled / scale}.$frac"
    }
}
