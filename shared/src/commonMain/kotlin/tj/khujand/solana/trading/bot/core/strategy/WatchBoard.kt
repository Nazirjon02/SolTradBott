package tj.khujand.solana.trading.bot.core.strategy

import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Статус монеты на доске наблюдения (порт WatchStatus из MRX).
 *
 * Сообщается всегда парой «цвет + слово»: на телефоне при ярком свете зелёный от жёлтого отличается
 * плохо, а «ГОТОВ» от «ЖДЁМ» — всегда.
 */
enum class WatchStatus(val label: String) {
    /** Сетап сложился и прошёл общие фильтры — бот входит (дальше риск и проверка монеты). */
    READY("ГОТОВ"),

    /** Сложилось почти всё, а цена у уровня входа — или сетап есть, но его сняли общие фильтры. */
    NEAR("БЛИЗКО"),

    /** Сетап собран частично — смотрим, но входа пока не ждём. */
    WAITING("ЖДЁМ"),

    /** Не показывается без «показать все»: таких монет всегда большинство. */
    NONE("НЕТ"),
}

/** Пороги доски — вынесены, потому что их захочется покрутить по опыту наблюдения. */
data class WatchThresholds(
    /** До уровня входа не дальше этого процента — монета «БЛИЗКО». */
    val nearPercent: Double = 2.0,
    /** Доля пройденных условий, с которой монета вообще попадает на доску. */
    val minChecksFraction: Double = 0.6,
)

/**
 * Строка доски: что бот видит по одной монете прямо сейчас. Собирается из того же цикла сканера, который
 * и так гоняет стратегию, — ни одного дополнительного запроса доска не стоит.
 *
 * @param missing первое непройденное условие с цифрами; null — всё сошлось.
 */
data class WatchItem(
    val mint: String,
    val symbol: String,
    val pairAddress: String,
    val strategyId: String,
    val strategyName: String,
    val timeframe: String,
    val price: Double,
    val entryLevel: Double?,
    val passed: Int,
    val total: Int,
    val missing: String?,
    val status: WatchStatus,
    val updatedAt: Long,
) {
    /** Сколько процентов цене до уровня входа: «+1.5» — уровень выше, «−0.8» — ниже. null — уровня нет. */
    val distancePct: Double?
        get() = entryLevel?.takeIf { it > 0.0 && price > 0.0 }?.let { (it - price) / price * 100 }
}

/**
 * Статус по разбору: [accepted] — сетап прошёл общие фильтры StrategyManager (range, уверенность), иначе
 * [Evaluation.signal] есть, но вход сняли — это «БЛИЗКО», а не «ГОТОВ».
 */
fun watchStatus(e: Evaluation, price: Double, accepted: Boolean, t: WatchThresholds = WatchThresholds()): WatchStatus {
    if (e.signal != null) return if (accepted) WatchStatus.READY else WatchStatus.NEAR
    val fraction = if (e.total > 0) e.passed.toDouble() / e.total else 0.0
    if (fraction < t.minChecksFraction) return WatchStatus.NONE
    val distance = e.entryLevel?.takeIf { it > 0.0 && price > 0.0 }?.let { abs(it - price) / price * 100 }
    return if (distance != null && distance <= t.nearPercent) WatchStatus.NEAR else WatchStatus.WAITING
}

/**
 * Доска наблюдения (порт из MRX): по строке на монету и стратегию. Строки обновляет каждый цикл сканера;
 * монета, которую сканер больше не отдаёт, уходит с доски через [ttlMs] без обновлений.
 */
class WatchBoard(
    private val ttlMs: Long = 10 * 60_000L,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _items = MutableStateFlow<List<WatchItem>>(emptyList())

    /** Все строки, лучшие сверху: ГОТОВ → БЛИЗКО → ЖДЁМ → НЕТ, внутри — по доле пройденных условий. */
    val items: StateFlow<List<WatchItem>> = _items.asStateFlow()

    fun update(item: WatchItem) {
        val now = nowMs()
        _items.update { list ->
            (list.filter { !(it.strategyId == item.strategyId && it.mint == item.mint) && now - it.updatedAt < ttlMs } + item)
                .sortedWith(ORDER)
        }
    }

    /** Монету купили (или она уже торговалась) — ждать по ней больше нечего. */
    fun remove(mint: String) = _items.update { list -> list.filter { it.mint != mint } }

    /** Стратегию остановили — её строки устарели. */
    fun clearStrategy(strategyId: String) = _items.update { list -> list.filter { it.strategyId != strategyId } }

    fun clear() = _items.update { emptyList() }

    private companion object {
        val ORDER = compareBy<WatchItem>({ it.status.ordinal }, { -(it.passed.toDouble() / it.total.coerceAtLeast(1)) }, { -it.updatedAt })
    }
}
