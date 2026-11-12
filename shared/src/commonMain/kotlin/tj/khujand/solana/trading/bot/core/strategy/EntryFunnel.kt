package tj.khujand.solana.trading.bot.core.strategy

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Почему готовый сетап не стал сделкой. */
enum class EntryLoss(val label: String) {
    RANGE("цена у верха диапазона"),
    CONFIDENCE("уверенность ниже порога"),
    PRICE_RAN("цена убежала от сигнала"),
    COIN_CHECK("проверка монеты"),
    PORTFOLIO("лимиты портфеля"),
    STRATEGY_LIMIT("лимиты стратегии"),
    NO_SIZE("нулевой размер позиции"),
    EXECUTION("сбой исполнения"),
    ALREADY_TRADED("монету уже купили"),
}

/** Почему цикл стратегии не дошёл до монет. */
enum class CycleBlock(val label: String) {
    COOLDOWN("кулдаун после сделки"),
    STRATEGY_LIMITS("лимиты стратегии"),
    PORTFOLIO("лимиты портфеля"),
    HARD_STOP("стоп по убытку портфеля"),
}

/**
 * Что произошло за один цикл одной стратегии. Заполняется по ходу цикла в его корутине (общего состояния нет)
 * и целиком уходит в [EntryFunnel.record] в конце цикла.
 *
 * @param countsAsCycle false — отчёт не о цикле сканера, а о входе по взводу: циклов он не добавляет.
 */
class CycleReport(val strategyId: String, val atMs: Long, val countsAsCycle: Boolean = true) {
    var block: CycleBlock? = null
        private set
    var blockDetail: String = ""
        private set
    var emptyScan = false
    val coins = LinkedHashSet<String>()
    val alreadyTraded = LinkedHashSet<String>()
    val noCandles = LinkedHashSet<String>()
    val setups = LinkedHashSet<String>()
    val losses = LinkedHashMap<String, EntryLoss>()
    val signals = LinkedHashSet<String>()
    var opened = 0

    /** Рабочий ТФ стратегии (мс) и пулы, по которым она в этом цикле запрашивала свечи. */
    var candleTfMs = 0L
        private set
    val candlePools = LinkedHashSet<String>()

    fun candles(timeframeMs: Long, pools: Collection<String>) {
        candleTfMs = timeframeMs
        candlePools += pools
    }

    fun block(reason: CycleBlock, detail: String) {
        block = reason
        blockDetail = detail
    }

    fun loss(mint: String, reason: EntryLoss) {
        losses[mint] = reason
    }
}

/** Последний цикл стратегии: работает она или стоит на кулдауне/лимите. */
data class StrategyCycle(val atMs: Long, val block: CycleBlock?, val detail: String)

/**
 * Спрос стратегий на свечи. По каждому пулу нужен один запрос на бар самого мелкого ТФ, на котором его разбирает
 * хоть одна стратегия: старшие ТФ собираются из младшего без запроса. Оценка снизу — без редких запросов старшего
 * ТФ, который из младшего не собрать.
 */
data class CandleDemand(
    val pools: Int = 0,
    val requestsPerMinute: Double = 0.0,
    /** ТФ (мс) → сколько пулов разбирается на нём как на самом мелком. */
    val poolsByTimeframeMs: Map<Long, Int> = emptyMap(),
)

/**
 * Воронка входа за последние [windowMinutes] минут: сколько монет стратегии получили от сканера, у скольких
 * сложился сетап и что с ним стало. Монеты считаются без повторов — стратегия видит одну монету каждые 30 с.
 */
data class FunnelSnapshot(
    val windowMinutes: Int,
    val cycles: Int = 0,
    val blockedCycles: Map<CycleBlock, Int> = emptyMap(),
    val emptyScans: Int = 0,
    val coins: Int = 0,
    /** Монеты под фильтрами, которые уже торговались: считаются, только когда других кандидатов не осталось. */
    val alreadyTraded: Int = 0,
    val noCandles: Int = 0,
    val setups: Int = 0,
    /** Монеты, чей сетап не стал сделкой, — по причинам (монета может попасть в несколько). */
    val losses: Map<EntryLoss, Int> = emptyMap(),
    val rejectedCoins: Int = 0,
    val signals: Int = 0,
    val opened: Int = 0,
    val lastCycleAt: Long? = null,
    val strategies: Map<String, StrategyCycle> = emptyMap(),
    /** Сколько свечей стратегии просят сейчас (по их последним циклам). */
    val candleDemand: CandleDemand = CandleDemand(),
)

/**
 * Счётчик воронки входа для карточки «почему нет сделок». Ничего не решает и ни во что не вмешивается: стратегии
 * только сообщают, что увидели. Хранит поминутные корзины за окно [windowMs].
 */
class EntryFunnel(
    private val windowMs: Long = 60 * 60_000L,
    private val bucketMs: Long = 60_000L,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Bucket(
        val startMs: Long,
        val cycles: Int = 0,
        val blocked: Map<CycleBlock, Int> = emptyMap(),
        val emptyScans: Int = 0,
        val coins: Set<String> = emptySet(),
        val alreadyTraded: Set<String> = emptySet(),
        val noCandles: Set<String> = emptySet(),
        val setups: Set<String> = emptySet(),
        val losses: Map<EntryLoss, Set<String>> = emptyMap(),
        val signals: Set<String> = emptySet(),
        val opened: Int = 0,
    )

    /** Свечи, которые стратегия запрашивала в своём последнем цикле со сканированием. */
    private data class CandleNeed(val atMs: Long, val timeframeMs: Long, val pools: Set<String>)

    private data class State(
        val buckets: List<Bucket> = emptyList(),
        val strategies: Map<String, StrategyCycle> = emptyMap(),
        val lastCycleAt: Long? = null,
        val candles: Map<String, CandleNeed> = emptyMap(),
    )

    // Отчёты приходят из корутин разных стратегий — состояние неизменяемое, обновление атомарное.
    private val state = MutableStateFlow(State())
    private val _version = MutableStateFlow(0L)

    /** Растёт с каждым отчётом — UI перечитывает [snapshot]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun record(r: CycleReport) {
        val start = r.atMs - r.atMs.mod(bucketMs)
        state.update { s ->
            val fresh = s.buckets.filter { it.startMs + bucketMs > r.atMs - windowMs }
            val existing = fresh.firstOrNull { it.startMs == start }
            val merged = (existing ?: Bucket(start)).merge(r)
            val buckets = (fresh.filter { it.startMs != start } + merged).sortedBy { it.startMs }
            s.copy(
                buckets = buckets,
                strategies = if (r.countsAsCycle) s.strategies + (r.strategyId to StrategyCycle(r.atMs, r.block, r.blockDetail))
                             else s.strategies,
                lastCycleAt = if (r.countsAsCycle) maxOf(s.lastCycleAt ?: 0L, r.atMs) else s.lastCycleAt,
                candles = if (r.candlePools.isEmpty() || r.candleTfMs <= 0L) s.candles
                          else s.candles + (r.strategyId to CandleNeed(r.atMs, r.candleTfMs, r.candlePools.toSet())),
            )
        }
        _version.update { it + 1 }
    }

    /** Стратегию выключили — её последний цикл больше не говорит о том, почему нет входов. */
    fun forgetStrategy(strategyId: String) {
        state.update { it.copy(strategies = it.strategies - strategyId, candles = it.candles - strategyId) }
        _version.update { it + 1 }
    }

    fun snapshot(): FunnelSnapshot {
        val now = nowMs()
        val s = state.value
        val inWindow = s.buckets.filter { it.startMs + bucketMs > now - windowMs }
        val losses = HashMap<EntryLoss, MutableSet<String>>()
        inWindow.forEach { b -> b.losses.forEach { (reason, mints) -> losses.getOrPut(reason) { HashSet() } += mints } }
        val blocked = HashMap<CycleBlock, Int>()
        inWindow.forEach { b -> b.blocked.forEach { (k, v) -> blocked[k] = (blocked[k] ?: 0) + v } }
        return FunnelSnapshot(
            windowMinutes = (windowMs / 60_000L).toInt(),
            cycles = inWindow.sumOf { it.cycles },
            blockedCycles = blocked,
            emptyScans = inWindow.sumOf { it.emptyScans },
            coins = inWindow.flatMapTo(HashSet()) { it.coins }.size,
            alreadyTraded = inWindow.flatMapTo(HashSet()) { it.alreadyTraded }.size,
            noCandles = inWindow.flatMapTo(HashSet()) { it.noCandles }.size,
            setups = inWindow.flatMapTo(HashSet()) { it.setups }.size,
            losses = losses.mapValues { it.value.size },
            rejectedCoins = losses.values.flatMapTo(HashSet()) { it }.size,
            signals = inWindow.flatMapTo(HashSet()) { it.signals }.size,
            opened = inWindow.sumOf { it.opened },
            lastCycleAt = s.lastCycleAt,
            strategies = s.strategies,
            candleDemand = demand(s.candles.values.filter { now - it.atMs <= DEMAND_FRESH_MS }),
        )
    }

    private fun demand(needs: Collection<CandleNeed>): CandleDemand {
        val finest = HashMap<String, Long>()
        needs.forEach { n -> n.pools.forEach { pool -> finest[pool] = minOf(finest[pool] ?: Long.MAX_VALUE, n.timeframeMs) } }
        if (finest.isEmpty()) return CandleDemand()
        return CandleDemand(
            pools = finest.size,
            requestsPerMinute = finest.values.sumOf { 60_000.0 / it },
            poolsByTimeframeMs = finest.values.groupingBy { it }.eachCount(),
        )
    }

    private fun Bucket.merge(r: CycleReport): Bucket {
        val mergedLosses = losses.toMutableMap()
        r.losses.forEach { (mint, reason) -> mergedLosses[reason] = (mergedLosses[reason] ?: emptySet()) + mint }
        return copy(
            cycles = cycles + if (r.countsAsCycle) 1 else 0,
            blocked = r.block?.let { blocked + (it to ((blocked[it] ?: 0) + 1)) } ?: blocked,
            emptyScans = emptyScans + if (r.emptyScan) 1 else 0,
            coins = coins + r.coins,
            alreadyTraded = alreadyTraded + r.alreadyTraded,
            noCandles = noCandles + r.noCandles,
            setups = setups + r.setups,
            losses = mergedLosses,
            signals = signals + r.signals,
            opened = opened + r.opened,
        )
    }

    private companion object {
        /** Стратегия, которая столько не сканировала (кулдаун, лимит, выключена), свечей сейчас не просит. */
        const val DEMAND_FRESH_MS = 5 * 60_000L
    }
}
