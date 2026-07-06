package tj.khujand.solana.trading.bot.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

enum class ActivityLevel { INFO, SUCCESS, WARN, ERROR }

data class ActivityEvent(
    val timestamp: Long,
    val level: ActivityLevel,
    val message: String
)

/** Сводный пульс сессии для шапки: сколько запросов прошло и когда был последний успешный ответ. */
data class ActivityStats(
    val okCount: Int = 0,
    val errorCount: Int = 0,
    val lastOkAt: Long? = null
)

/**
 * Кольцевой буфер последних событий движка. UI подписывается на [events] и
 * показывает живую ленту: что бот делает прямо сейчас и проходят ли запросы к DexScreener.
 *
 * Запись потокобезопасна (несколько стратегий пишут параллельно) за счёт [update].
 */
class ActivityLog(private val capacity: Int = 60) {

    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    private val _stats = MutableStateFlow(ActivityStats())
    val stats: StateFlow<ActivityStats> = _stats.asStateFlow()

    fun add(level: ActivityLevel, message: String) {
        val event = ActivityEvent(Clock.System.now().toEpochMilliseconds(), level, message)
        _events.update { (it + event).takeLast(capacity) }
    }

    fun info(msg: String)    = add(ActivityLevel.INFO, msg)
    fun success(msg: String) = add(ActivityLevel.SUCCESS, msg)
    fun warn(msg: String)    = add(ActivityLevel.WARN, msg)
    fun error(msg: String)   = add(ActivityLevel.ERROR, msg)

    /** Успешный ответ внешнего API — двигает счётчик и отметку времени (пульс в шапке). */
    fun requestOk() {
        val now = Clock.System.now().toEpochMilliseconds()
        _stats.update { it.copy(okCount = it.okCount + 1, lastOkAt = now) }
    }

    /** Неудачный запрос — растит счётчик ошибок. */
    fun requestFailed() {
        _stats.update { it.copy(errorCount = it.errorCount + 1) }
    }

    fun clear() {
        _events.update { emptyList() }
        _stats.update { ActivityStats() }
    }
}

/** Короткое форматирование числа (до 2 знаков) — без JVM-only String.format. */
fun fmtNum(v: Double): String {
    val rounded = kotlin.math.round(v * 100.0) / 100.0
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
}
