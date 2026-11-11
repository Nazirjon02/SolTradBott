package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.core.Indicators
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.SplMintInfo
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair

/**
 * «Мем-плейбук» — связка из обзора исследований мемкоинов (STRATEGY_RESEARCH.md §8):
 * отсечь мусор → не покупать памп → вход на откате по Фибоначчи → размер от риска → выходы «по иксам».
 *
 * Вход:
 *  1) ликвидность ≥ [StrategyConfig.mpMinLiqToMcPct]% капитализации: при < 1% из пула не выйти без
 *     огромного слиппеджа. Возраст и абсолютная ликвидность — общими фильтрами сканера
 *     (в сиде возраст ≥ 60 мин: моложе — лотерея запуска, а не сделка);
 *  2) импульс: от минимума окна [StrategyConfig.mpLookbackBars] баров до максимума ≥ [StrategyConfig.mpMinImpulsePct]%;
 *  3) откат от максимума на глубину [mpFibMin; mpFibMax) импульса (0.382…0.786). Глубже 0.786 — «пустой
 *     памп»: такие откаты чаще продолжают падать. Объём в откате «высох», без всплеска продаж — сам памп
 *     не покупаем (у неликвидных монет вчерашние лидеры роста потом отстают — Zaremba et al. 2021);
 *  4) триггер: бычий бар выше максимума прошлого, сильное закрытие, объём ≥ [StrategyConfig.mpTriggerVolumeMult] × отката;
 *  5) стоп — под минимумом отката (−0.5 ATR), но не глубже уровня mpFibMax: ниже него сетап сломан.
 *     Стоп дальше SL% конфига или до максимума импульса < 1R — пропуск;
 *  6) только когда сетап найден (экономим запросы): mint/freeze authority отозваны (RPC) и монета — не копия
 *     более старой монеты с тем же тикером (копии выходят на DEX в ~10 раз реже оригиналов — arXiv 2609.10246).
 *     Нет данных — нет входа (fail-closed).
 *
 * Выходы — общие тумблеры, сид их включает: половина на 2x («вернуть тело») → безубыток → четверть на 3x →
 * остаток под ступенчатым трейлингом «по иксам»; выход, если 24 ч нет нового максимума; ликвидность-стоп −20%.
 *
 * @param mintInfoSource mint-аккаунт монеты (authority); null — RPC недоступен.
 * @param symbolPairsSource пары Solana с тем же тикером; null — DexScreener недоступен.
 */
class MemePlaybookStrategy(
    override val config: StrategyConfig,
    private val mintInfoSource: suspend (mint: String) -> SplMintInfo?,
    private val symbolPairsSource: suspend (symbol: String) -> List<TokenPair>?,
) : Strategy {

    override val name: String = StrategyType.MEME_PLAYBOOK.displayName

    /**
     * Итог on-chain проверок по mint: authority и копикат не меняются за минуты, а сетап держится
     * несколько циклов сканера — без кеша каждые 30 с повторялись бы одни и те же запросы.
     * Кешируются только определённые ответы (сбой сети не запоминаем).
     */
    private val checkVerdicts = mutableMapOf<String, String?>()

    override suspend fun analyze(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Signal? = evaluate(candidate, candles, higherTfCandles).signal

    /** Шаги: ликвидность к капитализации, шаги детектора ([MemeFibDetector.STAGES]), on-chain проверки. */
    override suspend fun evaluate(
        candidate: TokenCandidate,
        candles: List<Candle>,
        higherTfCandles: List<Candle>,
    ): Evaluation {
        val total = MemeFibDetector.STAGES + 2
        if (!liquidityDeepEnough(candidate, config.mpMinLiqToMcPct)) {
            val liqPct = if (candidate.marketCap > 0) candidate.liquidityUsd / candidate.marketCap * 100 else 0.0
            return Evaluation.stoppedAt(1, total, "ликвидность ${fmt1(liqPct)}% капитализации < ${fmt1(config.mpMinLiqToMcPct)}%")
        }

        val setup = when (val r = MemeFibDetector.detect(candles, config)) {
            is MemeFibResult.Reject -> {
                val base = Evaluation.stoppedAt(r.stage + 1, total, r.reason, r.level)
                val plan = r.arm?.takeIf { config.armedEntryEnabled } ?: return base
                // Взводим, как и входим, только монету, прошедшую on-chain проверки.
                return if (onChainVerdict(candidate) == "") base.copy(trigger = plan) else base
            }
            is MemeFibResult.Setup -> r
        }

        // Проверки по сети — только для найденного сетапа. Непустая строка — причина отказа.
        when (val verdict = onChainVerdict(candidate)) {
            null -> return Evaluation.stoppedAt(total, total, "authority/копикат не проверить — нет ответа сети")
            "" -> Unit
            else -> return Evaluation.stoppedAt(total, total, verdict)
        }

        // Цена входа — закрытие триггера (последний закрытый бар): от него детектор считал стоп. Цена кандидата
        // из сканера к сетапу отношения не имеет; ушла живая цена дальше 3% — вход отменит StrategyManager.
        val entry = candles.last().close
        if (entry <= 0.0) return Evaluation.stoppedAt(total, total, "нет цены входа")

        val signal = Signal(
            mint = candidate.mint,
            symbol = candidate.symbol,
            pairAddress = candidate.pairAddress,
            confidence = setup.confidence,
            reason = "Мем-плейбук: ${setup.reason}",
            entryPrice = entry,
            stopLoss = entry * (1 - setup.stopFrac),
            takeProfit = config.takeProfitPrice(entry),
        )
        return Evaluation.ready(signal, total)
    }

    private fun fmt1(v: Double): String = (kotlin.math.round(v * 10) / 10.0).toString()

    /**
     * Итог on-chain проверок с кешем по mint: "" — пройдены, непустая строка — причина отказа, null — данных нет
     * (сеть), решение не принимаем и не запоминаем.
     */
    private suspend fun onChainVerdict(candidate: TokenCandidate): String? {
        if (checkVerdicts.containsKey(candidate.mint)) return checkVerdicts[candidate.mint] ?: ""
        val verdict = onChainRejection(candidate) ?: return null
        if (checkVerdicts.size >= VERDICT_CACHE_LIMIT) checkVerdicts.clear()
        checkVerdicts[candidate.mint] = verdict.ifEmpty { null }
        return verdict
    }

    /**
     * Authority и копикат. "" — проверки пройдены, непустая строка — причина отказа,
     * null — данных нет (сеть), решение не принимаем.
     */
    private suspend fun onChainRejection(candidate: TokenCandidate): String? {
        if (config.mpRequireRevokedAuthorities) {
            val info = mintInfoSource(candidate.mint) ?: return null
            if (info.hasMintAuthority) return "mint authority не отозвана — могут допечатать"
            if (info.hasFreezeAuthority) return "freeze authority не отозвана — могут заморозить"
        }
        if (config.mpRejectCopycats) {
            val pairs = symbolPairsSource(candidate.symbol) ?: return null
            val createdAtMs = candidate.scannedAt - candidate.tokenAgeMinutes * 60_000L
            if (isCopycat(candidate.mint, createdAtMs, pairs)) return "копия более старой монеты ${candidate.symbol}"
        }
        return ""
    }

    private companion object {
        const val VERDICT_CACHE_LIMIT = 500
    }
}

/** Ликвидность пула ≥ [minPct]% капитализации. Нет капитализации — не пропускаем. */
internal fun liquidityDeepEnough(c: TokenCandidate, minPct: Double): Boolean {
    if (minPct <= 0.0) return true
    if (c.marketCap <= 0.0) return false
    return c.liquidityUsd / c.marketCap * 100 >= minPct
}

/**
 * Копикат: среди [pairs] с тем же тикером есть ДРУГАЯ монета, чья пара создана раньше нашей.
 * Время нашей монеты — самая ранняя из её пар в выдаче или [createdAtMs] (оценка по возрасту пары сканера).
 * Пары без даты создания не считаются: раньше они или позже — неизвестно.
 */
internal fun isCopycat(mint: String, createdAtMs: Long, pairs: List<TokenPair>): Boolean {
    val ownFromPairs = pairs.filter { it.baseToken?.address == mint }.mapNotNull { createdMs(it.pairCreatedAt) }.minOrNull()
    val own = listOfNotNull(ownFromPairs, createdAtMs.takeIf { it > 0 }).minOrNull() ?: return false
    return pairs.any { p ->
        val other = p.baseToken?.address
        val created = createdMs(p.pairCreatedAt)
        other != null && other != mint && created != null && created < own
    }
}

/** DexScreener иногда отдаёт pairCreatedAt в секундах — нормализуем в миллисекунды. */
private fun createdMs(value: Long?): Long? = when {
    value == null || value <= 0L -> null
    value < 100_000_000_000L -> value * 1000
    else -> value
}

/** Результат поиска сетапа «Мем-плейбука»: найден (с параметрами входа) или отклонён с причиной. */
sealed interface MemeFibResult {
    /**
     * @param stage шаг детектора (с 1, см. [MemeFibDetector.STAGES]), на котором сетап не сложился.
     * @param level уровень, у которого ждём входа: до зоны — её верх (откат mpFibMin), в зоне — максимум
     *   прошлого бара (триггер выше него).
     */
    data class Reject(
        val reason: String,
        val stage: Int = 1,
        val level: Double? = null,
        /** Не хватает только триггера — план входа по касанию максимума прошлого бара. */
        val arm: TriggerPlan? = null,
    ) : MemeFibResult

    /**
     * @param depth глубина отката как доля импульса (0.5 = откатили половину).
     * @param stopFrac расстояние до стопа как доля цены триггера.
     * @param roomR запас хода до максимума импульса в R.
     */
    data class Setup(
        val impulseLow: Double,
        val impulseHigh: Double,
        val depth: Double,
        val stopFrac: Double,
        val roomR: Double,
        val confidence: Double,
        val reason: String,
    ) : MemeFibResult
}

/** Чистый детектор сетапа «импульс → откат по Фибоначчи → триггер» по свечам рабочего ТФ (без сети). */
object MemeFibDetector {

    /**
     * Шаги детектора по порядку (для доски наблюдения): история, импульс, глубина отката, высохший
     * объём, триггер, объём триггера, стоп и запас хода.
     */
    const val STAGES = 7

    /** Минимум баров отката после пика (не считая триггер): один бар — это ещё не откат. */
    private const val MIN_PULLBACK_BARS = 2

    /** Закрытие бара-триггера — в верхних 40% диапазона. */
    private const val STRONG_CLOSE_POS = 0.6

    /** Стоп — на 0.5 ATR ниже минимума отката (за шумом)… */
    private const val STOP_ATR_BUFFER = 0.5

    /** …но не глубже чем на 0.25 ATR под уровнем mpFibMax: ниже него сетап и так сломан. */
    private const val FIB_STOP_ATR_BUFFER = 0.25

    /** Минимальный запас хода до максимума импульса, в R. */
    private const val MIN_ROOM_R = 1.0

    /** «Золотая зона» отката — бонус к уверенности. */
    private const val GOLDEN_MIN = 0.5
    private const val GOLDEN_MAX = 0.618

    /** Потолок отношения объёмов (для отката без объёма) — чтобы баллы и текст оставались конечными. */
    private const val MAX_VOL_RATIO = 99.0

    fun detect(candles: List<Candle>, config: StrategyConfig): MemeFibResult {
        // Шаг и уровень — для доски наблюдения: на чём остановились и у какого уровня ждём входа.
        var stage = 1
        var level: Double? = null
        fun reject(reason: String) = MemeFibResult.Reject(reason, stage, level)

        val n = candles.size
        val minBars = maxOf(config.atrPeriod + 2, MIN_PULLBACK_BARS + 4)
        if (n < minBars) return reject("мало свечей ($n<$minBars)")
        val fibMin = config.mpFibMin
        val fibMax = config.mpFibMax
        if (fibMin <= 0.0 || fibMax <= fibMin || fibMax >= 1.0) return reject("некорректная зона Фибоначчи")

        val trigger = candles[n - 1]
        val prev = candles[n - 2]

        // 1. Импульс: максимум окна (без бара-триггера) и минимум ДО него.
        stage = 2
        val start = maxOf(0, n - 1 - config.mpLookbackBars.coerceAtLeast(10))
        val h = (start..n - 2).maxBy { candles[it].high }
        val pullbackBars = n - 2 - h
        if (pullbackBars < MIN_PULLBACK_BARS) return reject("отката ещё нет ($pullbackBars бар после пика)")
        val l = (start..h).minBy { candles[it].low }
        if (l == h) return reject("нет импульса: минимум окна на пике")
        val high = candles[h].high
        val low = candles[l].low
        if (low <= 0.0) return reject("некорректный минимум")
        val impulsePct = (high / low - 1) * 100
        if (impulsePct < config.mpMinImpulsePct) {
            return reject("импульс +${fmt(impulsePct)}% < +${fmt(config.mpMinImpulsePct)}%")
        }
        val range = high - low

        // 2. Глубина отката: в зоне [fibMin; fibMax). Триггер тоже не должен проколоть fibMax.
        stage = 3
        level = high - fibMin * range
        val pullback = candles.subList(h + 1, n - 1)
        val pullbackLow = pullback.minOf { it.low }
        val depth = (high - pullbackLow) / range
        val deepest = (high - minOf(pullbackLow, trigger.low)) / range
        if (deepest >= fibMax) return reject("откат ${fmt2(deepest)} глубже ${fmt2(fibMax)} — пустой памп")
        if (depth < fibMin) return reject("откат ${fmt2(depth)} мельче ${fmt2(fibMin)} — ещё не в зоне")

        // 3. Объём «высыхает» в откате, и нет всплеска продаж против нас.
        stage = 4
        level = prev.high
        val impulse = candles.subList(l, h + 1)
        val impulseVol = impulse.map { it.volume }.average()
        val pullbackVol = pullback.map { it.volume }.average()
        if (impulseVol <= 0.0) return reject("нет объёма в импульсе")
        val dryRatio = pullbackVol / impulseVol
        if (dryRatio > config.mpPullbackVolumeRatio) {
            return reject("объём в откате не снизился (${pct(dryRatio)}% объёма импульса)")
        }
        val impulseMaxVol = impulse.maxOf { it.volume }
        if (pullback.any { !it.isBullish && it.volume > impulseMaxVol }) {
            return reject("всплеск объёма продаж в откате")
        }

        // 4. Бар-триггер: разворот вверх с сильным закрытием и возвратом объёма.
        stage = 5
        if (!trigger.isBullish || trigger.close <= prev.high) {
            // Всё, кроме триггера, сошлось — план взведённого входа (стоп и запас хода — от уровня).
            val atr = Indicators.atr(candles, config.atrPeriod).lastOrNull() ?: 0.0
            val structureLow = minOf(pullbackLow, trigger.low)
            val fibFloor = high - fibMax * range
            val plan = if (atr <= 0.0) null else armPlan(
                level = prev.high,
                stop = maxOf(structureLow - STOP_ATR_BUFFER * atr, fibFloor - FIB_STOP_ATR_BUFFER * atr),
                peak = high,
                invalidation = fibFloor,
                config = config,
                confidence = 0.6 + (if (depth in GOLDEN_MIN..GOLDEN_MAX) 0.1 else 0.0) +
                    0.1 * (1 - dryRatio / config.mpPullbackVolumeRatio).coerceIn(0.0, 1.0),
                what = "пробой максимума прошлого бара в зоне отката ${fmt2(depth)}",
            )
            return reject("нет триггера: цена не выше максимума прошлого бара").copy(arm = plan)
        }
        if (trigger.closePosition < STRONG_CLOSE_POS) return reject("слабое закрытие триггера")
        stage = 6
        val triggerVolRatio = when {
            pullbackVol > 0.0 -> trigger.volume / pullbackVol
            trigger.volume > 0.0 -> MAX_VOL_RATIO
            else -> 0.0
        }.coerceAtMost(MAX_VOL_RATIO)
        if (triggerVolRatio < config.mpTriggerVolumeMult) {
            return reject("объём триггера ×${fmt(triggerVolRatio)} < ×${fmt(config.mpTriggerVolumeMult)}")
        }

        // 5. Стоп под минимумом отката, но не глубже уровня слома (fibMax); запас хода до максимума импульса.
        stage = 7
        val atr = Indicators.atr(candles, config.atrPeriod).lastOrNull() ?: return reject("мало свечей для ATR")
        val entry = trigger.close
        val stop = maxOf(pullbackLow - STOP_ATR_BUFFER * atr, high - fibMax * range - FIB_STOP_ATR_BUFFER * atr)
        if (stop <= 0.0 || stop >= entry) return reject("некорректный стоп")
        val risk = entry - stop
        val stopFrac = risk / entry
        if (stopFrac * 100 > config.stopLossPercent) {
            return reject("стоп −${pct(stopFrac)}% дальше SL ${fmt(config.stopLossPercent)}%")
        }
        val roomR = (high - entry) / risk
        if (roomR < MIN_ROOM_R) return reject("до максимума импульса ${fmt(roomR)}R < ${fmt(MIN_ROOM_R)}R")

        val golden = depth in GOLDEN_MIN..GOLDEN_MAX
        val dryScore = (1 - dryRatio / config.mpPullbackVolumeRatio).coerceIn(0.0, 1.0)
        val triggerScore = ((triggerVolRatio - config.mpTriggerVolumeMult) / config.mpTriggerVolumeMult).coerceIn(0.0, 1.0)
        val roomScore = ((roomR - MIN_ROOM_R) / 2).coerceIn(0.0, 1.0)
        val confidence = (0.6 + (if (golden) 0.1 else 0.0) + 0.1 * dryScore + 0.1 * triggerScore + 0.05 * roomScore)
            .coerceAtMost(0.95)

        return MemeFibResult.Setup(
            impulseLow = low,
            impulseHigh = high,
            depth = depth,
            stopFrac = stopFrac,
            roomR = roomR,
            confidence = confidence,
            reason = "импульс +${fmt(impulsePct)}% за ${h - l} бар, откат ${fmt2(depth)}" +
                (if (golden) " (золотая зона)" else "") +
                " за $pullbackBars бар (объём ${pct(dryRatio)}% импульса), триггер ×${fmt(triggerVolRatio)}, " +
                "стоп −${pct(stopFrac)}% под минимумом отката, до максимума ${fmt(roomR)}R",
        )
    }

    private fun pct(frac: Double): String = fmt(frac * 100)

    private fun fmt(v: Double): String = (kotlin.math.round(v * 10) / 10.0).toString()

    private fun fmt2(v: Double): String = (kotlin.math.round(v * 1000) / 1000.0).toString()
}
