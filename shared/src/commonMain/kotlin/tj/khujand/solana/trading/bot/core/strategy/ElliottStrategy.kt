package tj.khujand.solana.trading.bot.core.strategy

import kotlin.math.abs
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.Indicators
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalysis
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/** Какую волну берём: три места, где книга даёт и точку слома счёта (стоп), и цель. */
enum class ElliottSetup(val title: String) {
    /** После волн 1-2: вход на старте 3-й, стоп за началом 1-й (правило 1), цель 1.618 × 1-й. */
    WAVE3("вход в волну 3"),
    /** После 1-2-3-4: вход на старте 5-й, стоп за концом 1-й (правило 3), цель = 1-й. */
    WAVE5("вход в волну 5"),
    /**
     * После законченной пятёрки: против неё, цель — зона волны 4 (ориентир глубины). На споте
     * торгуется только после нисходящей пятёрки — отскок вверх.
     */
    CORRECTION("коррекция после пятёрки"),
}

/** Насколько строго проверяем, что волны делятся так, как велит книга. */
enum class ElliottStructureMode {
    /** Движущие — только пятёрки, коррекции — только тройки. */
    STRICT,
    /** То же, но волна, которая на младшей степени не делится, не отвергается. */
    SOFT,
    OFF;

    companion object {
        fun from(s: String?): ElliottStructureMode =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: SOFT
    }
}

/** От какой точки счёта ставится стоп. */
enum class ElliottStopAnchor {
    /** Точка, за которой счёт ломается по правилу книги: начало волны 1 / конец волны 1. */
    RULE,
    /** Конец последней коррекции (волны 2 / волны 4) — плотнее, но не правило. */
    PIVOT;

    companion object {
        fun from(s: String?): ElliottStopAnchor =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: RULE
    }
}

/** Когда входим в волну 3. */
enum class ElliottWave3Entry {
    /** Сразу, как подтвердился конец волны 2: раньше и с бо́льшим запасом до цели. */
    TURN,
    /** На закрытии за концом волны 1 («точка признания»): позже, но счёт уже подтверждён. */
    BREAK;

    companion object {
        fun from(s: String?): ElliottWave3Entry =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: BREAK
    }
}

/**
 * Что проверяет стратегия. Правила книги отделены от ориентиров: правило нарушено — счёт
 * неверен, сделки нет; ориентиры (Фибоначчи, объём, чередование, канал, глубина) лишь
 * повышают вероятность и собраны в одну проверку «столько-то из стольких».
 */
enum class ElliottCheck(val title: String) {
    COUNT("Счёт волн"),
    RULE_W2("Волна 2 не за началом волны 1"),
    RULE_W3_BEYOND("Волна 3 дальше конца волны 1"),
    RULE_W3_SHORTEST("Волна 3 не самая короткая"),
    RULE_W4_OVERLAP("Волна 4 не заходит на волну 1"),
    W5_BEYOND("Волна 5 дальше конца волны 3"),
    STRUCTURE("Строение волн"),
    GUIDELINES("Ориентиры книги"),
    SIDE_ALLOWED("Только покупка (спот)"),
    HIGHER_DEGREE("Старшая степень"),
    FRESH("Окно входа"),
    REWARD_RISK("Цель против риска"),
    STOP_DISTANCE("Стоп не дальше Stop Loss"),
}

/** Одна проверка разбора: код, пройдена ли и с какими числами. */
data class ElliottCheckResult(val check: ElliottCheck, val passed: Boolean, val detail: String)

/** Готовый вход — в ценах свечей (USD). */
data class ElliottEntry(
    val setup: ElliottSetup,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val confidence: Double,
    val reason: String,
)

/**
 * Разбор свечей: все проверки лучшего сетапа и вход, если они все пройдены.
 * [blocker] — считать было нечего (мало свечей, мало волн); тогда проверок нет.
 */
data class ElliottEvaluation(
    val checks: List<ElliottCheckResult>,
    val entry: ElliottEntry?,
    val blocker: String? = null,
) {
    val failed: List<ElliottCheckResult> get() = checks.filter { !it.passed }

    companion object {
        fun nothing(reason: String) = ElliottEvaluation(emptyList(), null, reason)
    }
}

/**
 * Направление текущей волны старшей степени (+1 вверх, −1 вниз). null — не применяется
 * (фильтр выключен или на старшем ТФ волн ещё нет). [unknown] — свечей старшего ТФ не
 * получили: вслепую не торгуем.
 */
data class ElliottHigherDegree(val dir: Int?, val note: String, val unknown: Boolean = false) {
    companion object {
        fun off() = ElliottHigherDegree(null, "выключено (старшая степень не требуется)")
        fun pending() = ElliottHigherDegree(null, "проверяется после остальных условий")
    }
}

private fun elliottCheck(check: ElliottCheck, passed: Boolean, detail: String) =
    ElliottCheckResult(check, passed, detail)

/** Один ориентир книги: применим ли на этой разметке, выполнен ли и с какими числами. */
private class Guideline(val applicable: Boolean, val passed: Boolean, val detail: String)

/**
 * Волны Эллиотта по Frost & Prechter, «Elliott Wave Principle: Key to Market Behavior» (Wiley).
 * Порт ElliottStrategy из MRX под спот Solana DEX.
 *
 * Рынок идёт пятью волнами по тренду и тремя против, и каждая волна делится на такие же волны
 * младшей степени. Отсюда устройство:
 *
 *  - **Степень.** Волна — движение между разворотами, а не серия свечей одного цвета. Разворот
 *    засчитывается, когда цена отошла от экстремума на [StrategyConfig.ewSwingAtr] × ATR; всё,
 *    что меньше, — волны младшей степени. Их строение проверяется тем же алгоритмом с меньшим
 *    порогом ([StrategyConfig.ewSubDegree]): движущая волна обязана быть пятёркой, коррекция — тройкой.
 *  - **Правила** (гл. 1) абсолютны: волна 2 не уходит за начало 1, волна 3 уходит дальше конца
 *    1 и не бывает самой короткой, волна 4 не заходит на территорию 1. Нарушено одно — счёт
 *    неверен, и сделки нет.
 *  - **Ориентиры** (гл. 2 и 4) — вероятности: глубина отката по Фибоначчи, объём (вторая волна
 *    кончается на низком объёме, третья — самая объёмная), чередование 2 и 4, канал, зона
 *    четвёртой волны младшей степени. Нужно [StrategyConfig.ewMinGuidelines] из применимых.
 *  - **Стоп** ставится туда, где счёт ломается по правилу: за начало волны 3 (её подволна (ii)
 *    не уходит за начало (i)) при входе на пробое, за начало волны 1 при входе на конце 2-й,
 *    за конец волны 1 у входа в 5-ю. Это книжный способ «знать, где ты неправ».
 *  - **Цель** — проекция по Фибоначчи (3-я ≈ 1.618 × 1-й, 5-я ≈ 1-й) или, для коррекции после
 *    пятёрки, зона волны 4.
 *
 * Вход — на закрытом баре: в 3-ю по умолчанию на первом закрытии за концом волны 1 («точка
 * признания»), в 5-ю и в коррекцию — на подтверждённом развороте в конце 4-й / 5-й; не позже
 * [StrategyConfig.ewEntryWindowBars] баров после повода. Вход на пробое выбран в MRX по прогону
 * ElliottLiveDiagnostics (топ-30 Bybit, 5000 баров H1, 2026-10): +0.06…0.07R на сделку против
 * −0.09…−0.11R у входа на конце 2-й.
 *
 * Отличия от MRX:
 *  - спот, только покупка: шортовые сетапы (3-я и 5-я в нисходящей пятёрке, коррекция после
 *    восходящей) разбираются, но отваливаются на [ElliottCheck.SIDE_ALLOWED];
 *  - GeckoTerminal отдаёт и формирующийся бар — он отбрасывается, разметка идёт только по закрытым
 *    свечам и не перерисовывается внутри бара (цена входа — текущая цена монеты);
 *  - старший ТФ тянется только для уже готового сетапа (общий лимит GeckoTerminal), а не получили
 *    его свечи — входа нет;
 *  - стоп по правилу на мемкоине бывает очень далеко: дальше Stop Loss из настроек — вход
 *    пропускается ([ElliottCheck.STOP_DISTANCE]).
 *
 * Класс без состояния: один экземпляр обслуживает все монеты сканера параллельно.
 *
 * @param htfSource источник свечей старшего ТФ (пул, таймфрейм, лимит). Инъекция — для тестов и бэктеста.
 * @param nowMs текущее время — по нему определяется, закрыт ли последний бар.
 */
class ElliottStrategy(
    override val config: StrategyConfig,
    private val htfSource: suspend (pool: String, timeframe: String, limit: Int) -> List<Candle> = { pool, tf, limit ->
        val (gtTf, agg) = DexClient.parseTimeframe(tf)
        GeckoTerminalApi.getCandles(pool, gtTf, agg, limit)
    },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : Strategy {

    override val name: String = StrategyType.ELLIOTT.displayName

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? {
        val e = evaluateFull(candidate, candles, higherTfCandles).entry ?: return null
        return Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = e.confidence,
            reason = e.reason,
            entryPrice = e.entry,
            stopLoss = e.stop,
            takeProfit = e.target,
        )
    }

    /**
     * Разбор монеты целиком: закрытые свечи рабочего ТФ, текущая цена кандидата и — только если
     * без неё сетап готов — старшая степень.
     */
    suspend fun evaluateFull(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): ElliottEvaluation {
        val closed = closedOnly(candles, config.timeframe)
        val price = candidate.priceUsd.takeIf { it > 0.0 } ?: candles.lastOrNull()?.close ?: 0.0
        if (!config.ewRequireHtf) return evaluate(closed, price, ElliottHigherDegree.off())

        // Старшая степень может только снять вход — без готового сетапа её не запрашиваем.
        val first = evaluate(closed, price, ElliottHigherDegree.pending())
        if (first.entry == null) return first
        val htf = higherTfCandles.ifEmpty { htfSource(candidate.pairAddress, config.ewHigherTf, HTF_LIMIT) }
        return evaluate(closed, price, higherDegree(closedOnly(htf, config.ewHigherTf)))
    }

    /**
     * Полный разбор по закрытым свечам [all] при текущей цене [price]: размечает волны, считает
     * каждый включённый сетап и отдаёт лучший — готовый к входу, а если такого нет, тот, что
     * ближе всех к готовности.
     *
     * Книга требует держать в голове и альтернативный счёт; здесь альтернативы — это другие
     * сетапы на тех же разворотах («1-2, идёт 3-я» против «1-2-3-4, идёт 5-я»), и каждый из них
     * проверяется своими правилами.
     */
    fun evaluate(all: List<Candle>, price: Double, higher: ElliottHigherDegree): ElliottEvaluation {
        val atrPeriod = config.atrPeriod
        // Столько свечей бот получает от GeckoTerminal: бэктест с длинной историей видит то же окно.
        val candles = if (all.size > LOOKBACK_BARS) all.takeLast(LOOKBACK_BARS) else all
        val minBars = atrPeriod + MIN_EXTRA_BARS
        if (candles.size < minBars) return ElliottEvaluation.nothing("мало свечей: ${candles.size} из $minBars")

        val atrs = Indicators.atr(candles, atrPeriod)
        if (atrs.isEmpty()) return ElliottEvaluation.nothing("ATR($atrPeriod) не посчитан")
        if (atrs.last() <= 0.0) return ElliottEvaluation.nothing("нулевой ATR — цена стоит")
        val swingAtr = config.ewSwingAtr
        val subDegree = config.ewSubDegree
        val thresholdAt = { i: Int -> swingAtr * atrAt(atrs, atrPeriod, i) }
        val swings = elliottSwings(candles, thresholdAt)
        val running = swings.running
        if (running == null || swings.points.size < 3) {
            return ElliottEvaluation.nothing("волн мало для счёта: разворотов ${swings.points.size}, нужно ≥ 3")
        }

        val setups = buildList {
            if (config.ewTradeWave3) add(ElliottSetup.WAVE3)
            if (config.ewTradeWave5) add(ElliottSetup.WAVE5)
            if (config.ewTradeCorrection) add(ElliottSetup.CORRECTION)
        }
        if (setups.isEmpty()) return ElliottEvaluation.nothing("ни один сетап не включён в настройках")

        val ctx = Ctx(
            candles = candles,
            points = swings.points,
            running = running,
            atr = atrs.last(),
            subAt = { i -> subDegree * thresholdAt(i) },
            price = if (price > 0.0) price else candles.last().close,
            higher = higher,
        )
        val results = setups.mapNotNull { evaluateSetup(it, ctx) }
        if (results.isEmpty()) {
            return ElliottEvaluation.nothing("волн мало для включённых сетапов: разворотов ${swings.points.size}")
        }
        // maxBy отдаёт первый из равных — порядок сетапов и есть приоритет: 3-я волна, по книге
        // самая сильная, идёт первой.
        val best = results.filter { it.entry != null }.maxByOrNull { it.entry!!.confidence }
            ?: results.maxBy { it.progress }
        return ElliottEvaluation(checks = best.checks, entry = best.entry)
    }

    /** Направление текущей волны старшей степени — по свечам старшего ТФ тем же разметчиком. */
    private fun higherDegree(trend: List<Candle>): ElliottHigherDegree {
        if (trend.isEmpty()) return ElliottHigherDegree(null, "свечи старшего ТФ ${config.ewHigherTf} не получены", unknown = true)
        val atrPeriod = config.atrPeriod
        val candles = trend.takeLast(LOOKBACK_BARS)
        if (candles.size < atrPeriod + MIN_EXTRA_BARS) {
            return ElliottHigherDegree(null, "свечей старшего ТФ мало (${candles.size}) — не применяется")
        }
        val atrs = Indicators.atr(candles, atrPeriod)
        if (atrs.isEmpty()) return ElliottHigherDegree(null, "ATR старшего ТФ не посчитан — не применяется")
        val running = elliottSwings(candles) { i -> config.ewSwingAtr * atrAt(atrs, atrPeriod, i) }.running
            ?: return ElliottHigherDegree(null, "на старшем ТФ волн ещё нет — не применяется")
        val dir = if (running.isHigh) 1 else -1
        return ElliottHigherDegree(dir, "текущая волна ${config.ewHigherTf} идёт ${if (dir > 0) "вверх" else "вниз"}")
    }

    /** Без формирующегося бара: его экстремум ещё не окончателен, и разметка по нему перерисовывалась бы. */
    private fun closedOnly(candles: List<Candle>, timeframe: String): List<Candle> {
        val last = candles.lastOrNull() ?: return candles
        return if (last.openTimeMs + barMs(timeframe) > nowMs()) candles.dropLast(1) else candles
    }

    /** Длительность свечи ТФ ("15m", "1h", …) в миллисекундах. */
    private fun barMs(timeframe: String): Long {
        val (unit, value) = DexClient.parseTimeframe(timeframe)
        val base = when (unit) {
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            else -> 60_000L
        }
        return base * value
    }

    private class Ctx(
        val candles: List<Candle>,
        val points: List<ElliottPoint>,
        val running: ElliottPoint,
        val atr: Double,
        /** Порог младшей степени в цене — он же допуск «рядом с линией / в зоне». */
        val subAt: (Int) -> Double,
        val price: Double,
        val higher: ElliottHigherDegree,
    )

    private class SetupResult(
        val checks: List<ElliottCheckResult>,
        val entry: ElliottEntry?,
    ) {
        val progress: Double get() = checks.count { it.passed }.toDouble() / checks.size
    }

    /**
     * Один сетап на последних разворотах. null — разворотов для него не хватает.
     *
     * `p[0..n-1]` — подтверждённые развороты, `p[n]` — конец текущей, ещё идущей волны.
     * `s` — направление пятёрки: +1, если она растёт. Сделка в 3-ю и 5-ю идёт по `s`,
     * коррекция — против.
     */
    private fun evaluateSetup(kind: ElliottSetup, ctx: Ctx): SetupResult? {
        val need = when (kind) {
            ElliottSetup.WAVE3 -> 3
            ElliottSetup.WAVE5 -> 5
            ElliottSetup.CORRECTION -> 6
        }
        if (ctx.points.size < need) return null
        val p = ctx.points.takeLast(need) + ctx.running
        val s = if (p[0].isHigh) -1 else 1
        val t = if (kind == ElliottSetup.CORRECTION) -s else s
        val long = t > 0
        val completed = need - 1
        val candles = ctx.candles

        fun len(k: Int) = abs(p[k].price - p[k - 1].price)
        fun beyond(a: Double, b: Double) = s * (a - b) > 0.0
        fun avgVolume(k: Int): Double {
            val from = p[k - 1].index + 1
            val to = p[k].index
            return if (to < from) candles[to].volume else (from..to).sumOf { candles[it].volume } / (to - from + 1)
        }

        // Строение каждой законченной волны на младшей степени.
        val structures = (1..completed).map { k ->
            val sub = elliottSubWaves(candles, p[k - 1], p[k], ctx.subAt)
            ElliottStructure(sub, if (k % 2 == 1) classifyMotive(sub) else classifyCorrective(sub))
        }
        fun shape(k: Int) = structures[k - 1].shape
        val retrace2 = len(2) / len(1)
        val retrace4 = if (completed >= 4) len(4) / len(3) else 0.0

        val labels = when (kind) {
            ElliottSetup.WAVE3 -> listOf("1", "2", "3")
            ElliottSetup.WAVE5 -> listOf("1", "2", "3", "4", "5")
            ElliottSetup.CORRECTION -> listOf("1", "2", "3", "4", "5", "A")
        }
        val checks = mutableListOf<ElliottCheckResult>()

        // ─── Счёт ───
        checks += elliottCheck(
            ElliottCheck.COUNT, true,
            "${kind.title}, ${if (long) "лонг" else "шорт"}: " +
                (1..completed).joinToString(" · ") { "${labels[it - 1]}: ${priceText(p[it - 1].price)}→${priceText(p[it].price)}" } +
                " · идёт ${labels[completed]}"
        )

        // ─── Правила импульса (гл. 1) ───
        checks += elliottCheck(
            ElliottCheck.RULE_W2, beyond(p[2].price, p[0].price),
            "конец 2-й ${priceText(p[2].price)}, начало 1-й ${priceText(p[0].price)} — откат ${pct(retrace2)} волны 1"
        )
        if (completed >= 4) {
            checks += elliottCheck(
                ElliottCheck.RULE_W3_BEYOND, beyond(p[3].price, p[1].price),
                "конец 3-й ${priceText(p[3].price)}, конец 1-й ${priceText(p[1].price)}"
            )
            checks += elliottCheck(
                ElliottCheck.RULE_W4_OVERLAP, beyond(p[4].price, p[1].price),
                "конец 4-й ${priceText(p[4].price)}, конец 1-й ${priceText(p[1].price)}"
            )
        }
        if (completed >= 5) {
            val w1 = len(1)
            val w3 = len(3)
            val w5 = len(5)
            checks += elliottCheck(
                ElliottCheck.RULE_W3_SHORTEST, !(w3 < w1 && w3 < w5),
                "волны 1 / 3 / 5: ${priceText(w1)} / ${priceText(w3)} / ${priceText(w5)}"
            )
            // Усечённая пятая бывает (после очень сильной 3-й), но редко; без выхода за 3-ю
            // нельзя отличить законченную пятёрку от затянувшейся 4-й.
            checks += elliottCheck(
                ElliottCheck.W5_BEYOND, beyond(p[5].price, p[3].price),
                "конец 5-й ${priceText(p[5].price)}, конец 3-й ${priceText(p[3].price)}"
            )
        }

        // ─── Строение: движущие — пятёрки, коррекции — тройки ───
        val mode = ElliottStructureMode.from(config.ewStructure)
        val accepted = (1..completed).map { k ->
            val sh = shape(k)
            when (k) {
                1, 5 -> acceptMotive(sh, mode, diagonal = true)   // начальная / конечная диагональ
                3 -> acceptMotive(sh, mode, diagonal = false)     // 3-я диагональю не бывает
                2 -> acceptCorrective(sh, mode, triangle = false) // треугольник не бывает волной 2
                else -> acceptCorrective(sh, mode, triangle = true)
            }
        }
        checks += elliottCheck(
            ElliottCheck.STRUCTURE, accepted.all { it },
            (if (mode == ElliottStructureMode.OFF) "проверка выключена · " else "") +
                (1..completed).joinToString(" · ") {
                    "${labels[it - 1]}: ${structures[it - 1].text} ${if (accepted[it - 1]) "✓" else "✗"}"
                }
        )

        // ─── Ориентиры (гл. 2 и 4) ───
        val guidelines = when (kind) {
            ElliottSetup.WAVE3 -> listOf(
                fibGuideline("откат 2-й", retrace2, config.ewW2RetraceMin, config.ewW2RetraceMax),
                volumeGuideline("объём 2-й", avgVolume(2) / avgVolume(1), "1-й", lower = true),
                Guideline(
                    applicable = true,
                    passed = shape(2) == ElliottShape.ZIGZAG || shape(2) == ElliottShape.SIMPLE,
                    detail = "2-я резкая: ${shape(2).title}"
                ),
                depthGuideline("2-й", structures[0], p[2], ctx)
            )
            ElliottSetup.WAVE5 -> listOf(
                fibGuideline("откат 4-й", retrace4, config.ewW4RetraceMin, config.ewW4RetraceMax),
                alternationGuideline(shape(2), shape(4), retrace2, retrace4),
                volumeGuideline("объём 3-й", avgVolume(3) / avgVolume(1), "1-й", lower = false),
                channelGuideline("4-я у линии 1–3 через 2", p[1], p[3], p[2], p[4], s, ctx),
                depthGuideline("4-й", structures[2], p[4], ctx)
            )
            ElliottSetup.CORRECTION -> listOf(
                equalityGuideline(len(1), len(3), len(5)),
                volumeGuideline("объём 5-й", avgVolume(5) / avgVolume(3), "3-й", lower = true),
                channelGuideline("5-я у линии 2–4 через 3", p[2], p[4], p[3], p[5], s, ctx),
                alternationGuideline(shape(2), shape(4), retrace2, retrace4)
            )
        }
        val applicable = guidelines.filter { it.applicable }
        val passedGuidelines = applicable.count { it.passed }
        val required = config.ewMinGuidelines.coerceAtMost(applicable.size)
        checks += elliottCheck(
            ElliottCheck.GUIDELINES, passedGuidelines >= required,
            "$passedGuidelines из ${applicable.size}, нужно ≥ $required: " +
                guidelines.joinToString(" · ") { g ->
                    "${if (!g.applicable) "—" else if (g.passed) "✓" else "✗"} ${g.detail}"
                }
        )

        // ─── Исполнение ───
        checks += elliottCheck(
            ElliottCheck.SIDE_ALLOWED, long,
            if (long) "лонг — покупка токена" else "шорт — на споте не торгуется"
        )
        checks += if (kind == ElliottSetup.CORRECTION) {
            elliottCheck(ElliottCheck.HIGHER_DEGREE, true, "не применяется: коррекция идёт против пятёрки по определению")
        } else {
            val h = ctx.higher
            elliottCheck(ElliottCheck.HIGHER_DEGREE, !h.unknown && (h.dir == null || h.dir == t), h.note)
        }
        // Момент входа: подтверждённый конец коррекции либо (BREAK) первое закрытие волны 3 за
        // концом волны 1 — «точка признания», где счёт «идёт 3-я» становится очевидным.
        val breakMode = kind == ElliottSetup.WAVE3 &&
            ElliottWave3Entry.from(config.ewWave3Entry) == ElliottWave3Entry.BREAK
        val turn = p[completed]
        val trigger: Int? = if (!breakMode) turn.confirmedAt
            else (turn.index + 1..candles.lastIndex).firstOrNull { beyond(candles[it].close, p[1].price) }
        val window = config.ewEntryWindowBars
        val ago = trigger?.let { candles.lastIndex - it }
        checks += elliottCheck(
            ElliottCheck.FRESH, ago != null && ago < window,
            when {
                ago == null -> "волна 3 ещё не закрылась за концом 1-й ${priceText(p[1].price)}"
                breakMode -> "пробой конца 1-й ${priceText(p[1].price)} $ago бар. назад, окно $window"
                else -> "конец ${labels[completed - 1]}-й подтверждён $ago бар. назад, окно $window"
            }
        )

        val entry = ctx.price
        val buffer = config.ewStopBufferAtr * ctx.atr
        val anchor = ElliottStopAnchor.from(config.ewStopAnchor)
        val (stopBase, stopNote) = when (kind) {
            // Когда 3-я уже идёт, её подволна (ii) не уходит за начало (i) — то есть за конец 2-й.
            ElliottSetup.WAVE3 -> when {
                breakMode -> p[2].price to "за началом 3-й — правило"
                anchor == ElliottStopAnchor.RULE -> p[0].price to "за началом 1-й — правило"
                else -> p[2].price to "за концом 2-й"
            }
            ElliottSetup.WAVE5 -> if (anchor == ElliottStopAnchor.RULE) p[1].price to "за концом 1-й — правило"
                                  else p[4].price to "за концом 4-й"
            ElliottSetup.CORRECTION -> p[5].price to "за концом 5-й"
        }
        val stop = stopBase - t * buffer
        val (target, targetNote) = when (kind) {
            ElliottSetup.WAVE3 -> {
                val mult = config.ewW3Target
                p[2].price + t * mult * len(1) to "${fmt(mult)} × 1-й от конца 2-й"
            }
            ElliottSetup.WAVE5 -> {
                val mult = config.ewW5Target
                // Правило «3-я не самая короткая»: если 3-я короче 1-й, 5-я обязана быть
                // короче 3-й — цель дальше этого счёт бы нарушила.
                val capped = len(3) < len(1) && len(3) < mult * len(1)
                val size = if (capped) len(3) else mult * len(1)
                p[4].price + t * size to
                    if (capped) "урезано правилом: 5-я не длиннее 3-й" else "${fmt(mult)} × 1-й от конца 4-й"
            }
            ElliottSetup.CORRECTION -> p[4].price to "зона волны 4"
        }
        val risk = t * (entry - stop)
        val reward = t * (target - entry)
        val rr = if (risk > 0.0) reward / risk else 0.0
        val minRr = config.ewMinRewardRisk
        checks += elliottCheck(
            ElliottCheck.REWARD_RISK, risk > 0.0 && reward > 0.0 && rr >= minRr,
            "вход ${priceText(entry)}, цель ${priceText(target)} ($targetNote), " +
                "стоп ${priceText(stop)} ($stopNote) — R:R ${fmt(rr)}, нужно ≥ ${fmt(minRr)}"
        )
        // Стоп по правилу на мемкоине бывает за десятки процентов: дальше Stop Loss из
        // настроек — сетап не для этой стратегии.
        val stopPct = if (entry > 0.0) risk / entry * 100 else 0.0
        checks += elliottCheck(
            ElliottCheck.STOP_DISTANCE, stop > 0.0 && risk > 0.0 && stopPct <= config.stopLossPercent,
            "стоп −${fmt(stopPct)}% от входа, Stop Loss ${fmt(config.stopLossPercent)}%"
        )

        val signal = if (checks.any { !it.passed }) null else {
            val share = if (applicable.isEmpty()) 0.0 else passedGuidelines.toDouble() / applicable.size
            ElliottEntry(
                setup = kind,
                entry = entry,
                stop = stop,
                target = target,
                confidence = (0.6 + 0.35 * share).coerceAtMost(0.95),
                reason = "Эллиотт: ${kind.title} ▲ — цель ${priceText(target)} ($targetNote), " +
                    "стоп ${priceText(stop)} ($stopNote), R:R ${fmt(rr)}, ориентиров $passedGuidelines/${applicable.size}",
            )
        }
        return SetupResult(checks, signal)
    }

    // ─── Ориентиры ────────────────────────────────────────────────────────────

    /** Глубина коррекции по Фибоначчи (гл. 4): резкие — 50–61.8 %, боковые — около 38.2 %. */
    private fun fibGuideline(what: String, ratio: Double, lo: Double, hi: Double): Guideline =
        Guideline(true, ratio in lo..hi, "$what ${fmt3(ratio)} (норма ${fmt3(lo)}–${fmt3(hi)})")

    /**
     * Объём (гл. 2): он растёт и падает вместе со скоростью цены. Вторая волна кончается на
     * низком объёме (продавец иссяк), третья — самая объёмная, пятая — тише третьей.
     */
    private fun volumeGuideline(what: String, ratio: Double, against: String, lower: Boolean) =
        Guideline(true, if (lower) ratio < 1.0 else ratio > 1.0, "$what ${fmt(ratio)}× $against")

    /** Чередование (гл. 2): если одна из коррекций 2 и 4 резкая, другая — боковая или мельче. */
    private fun alternationGuideline(s2: ElliottShape, s4: ElliottShape, r2: Double, r4: Double): Guideline {
        val form = s2.sideways != s4.sideways
        val depth = abs(r2 - r4) >= ALTERNATION_DEPTH
        return Guideline(
            true, form || depth,
            "чередование: 2-я ${s2.title} ${fmt3(r2)}, 4-я ${s4.title} ${fmt3(r4)}"
        )
    }

    /**
     * Равенство (гл. 2, 4): одна из движущих волн растянута, две другие тяготеют к равенству
     * или к соотношению 0.618.
     */
    private fun equalityGuideline(w1: Double, w3: Double, w5: Double): Guideline {
        val waves = listOf("1-я" to w1, "3-я" to w3, "5-я" to w5)
        val extended = waves.maxBy { it.second }
        val rest = waves.filter { it !== extended }
        val ratio = minOf(rest[0].second, rest[1].second) / maxOf(rest[0].second, rest[1].second)
        val near = abs(ratio - 1.0) <= EQUALITY_TOLERANCE || abs(ratio - 0.618) <= EQUALITY_TOLERANCE
        return Guideline(
            true, near,
            "растянута ${extended.first}, ${rest[0].first} к ${rest[1].first}: ${fmt3(ratio)} (≈1 или ≈0.618)"
        )
    }

    /**
     * Канал (гл. 2): линия через [a] и [b], параллель через [c]. Конец 4-й не пробивает
     * параллель (линия 1–3 через 2 — оценка её границы), конец 5-й до своей доходит (линия 2–4
     * через 3). Допуск — порог младшей степени. [side] = +1: конец должен быть не ниже линии.
     */
    private fun channelGuideline(
        what: String,
        a: ElliottPoint,
        b: ElliottPoint,
        c: ElliottPoint,
        end: ElliottPoint,
        side: Int,
        ctx: Ctx,
    ): Guideline {
        if (b.index == a.index) return Guideline(false, false, "$what: н/д")
        val slope = (b.price - a.price) / (b.index - a.index)
        val line = c.price + slope * (end.index - c.index)
        val tol = ctx.subAt(end.index)
        return Guideline(
            true, side * (end.price - line) >= -tol,
            "$what: ${priceText(end.price)} против линии ${priceText(line)}"
        )
    }

    /**
     * Глубина (гл. 2): коррекция чаще всего кончается в зоне волны 4 младшей степени внутри
     * предыдущей движущей волны. Не видно пятёрки — ориентир неприменим.
     */
    private fun depthGuideline(what: String, motive: ElliottStructure, end: ElliottPoint, ctx: Ctx): Guideline {
        val span = motive.fourthSpan
            ?: return Guideline(false, false, "глубина $what: пятёрка предыдущей волны не видна")
        val tol = ctx.subAt(end.index)
        val inside = end.price >= span.start - tol && end.price <= span.endInclusive + tol
        return Guideline(
            true, inside,
            "глубина $what: ${priceText(end.price)}, зона (iv) ${priceText(span.start)}–${priceText(span.endInclusive)}"
        )
    }

    private fun acceptMotive(shape: ElliottShape, mode: ElliottStructureMode, diagonal: Boolean): Boolean =
        when (mode) {
            ElliottStructureMode.OFF -> true
            ElliottStructureMode.SOFT, ElliottStructureMode.STRICT ->
                shape == ElliottShape.IMPULSE || shape == ElliottShape.EXTENDED ||
                    (diagonal && shape == ElliottShape.DIAGONAL) ||
                    (mode == ElliottStructureMode.SOFT && shape == ElliottShape.SIMPLE)
        }

    private fun acceptCorrective(shape: ElliottShape, mode: ElliottStructureMode, triangle: Boolean): Boolean =
        when (mode) {
            ElliottStructureMode.OFF -> true
            ElliottStructureMode.SOFT, ElliottStructureMode.STRICT ->
                shape == ElliottShape.ZIGZAG || shape == ElliottShape.FLAT || shape == ElliottShape.COMBINATION ||
                    (triangle && shape == ElliottShape.TRIANGLE) ||
                    (mode == ElliottStructureMode.SOFT && shape == ElliottShape.SIMPLE)
        }

    private fun priceText(v: Double): String = MarketAnalysis.fmtPrice(v)

    private fun pct(v: Double): String = "${kotlin.math.round(v * 100).toInt()}%"

    /** Двузнаковое форматирование без String.format: на ru-локали тот дал бы запятую. */
    private fun fmt(v: Double): String = fixed(v, 100)

    /** Три знака — для коэффициентов Фибоначчи (0.618 в два знака превратилось бы в 0.62). */
    private fun fmt3(v: Double): String = fixed(v, 1000)

    private fun fixed(v: Double, scale: Long): String {
        val x = kotlin.math.round(v * scale).toLong()
        val sign = if (x < 0) "-" else ""
        val ax = abs(x)
        val digits = scale.toString().length - 1
        return "$sign${ax / scale}.${(ax % scale).toString().padStart(digits, '0')}"
    }

    private companion object {
        /** Столько свечей бот запрашивает у GeckoTerminal (darsCandleLimit по умолчанию). */
        const val LOOKBACK_BARS = 200
        /** Сверх периода ATR — чтобы на окне вообще уместилось несколько волн. */
        const val MIN_EXTRA_BARS = 30
        /** Свечей старшего ТФ на запрос. */
        const val HTF_LIMIT = 200
        /** Разница глубин 2-й и 4-й, которая сама по себе считается чередованием. */
        const val ALTERNATION_DEPTH = 0.236
        /** Допуск у «≈1» и «≈0.618» в ориентире равенства. */
        const val EQUALITY_TOLERANCE = 0.1

        /** ATR на баре [i]: ряд ATR начинается с бара [period] (EMA истинных диапазонов). */
        fun atrAt(atrs: List<Double>, period: Int, i: Int): Double = atrs[(i - period).coerceIn(0, atrs.lastIndex)]
    }
}
