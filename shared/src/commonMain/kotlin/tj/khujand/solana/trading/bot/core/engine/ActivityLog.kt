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
class ActivityLog(private val capacity: Int = 60, private val errorCapacity: Int = 100) {

    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    private val _stats = MutableStateFlow(ActivityStats())
    val stats: StateFlow<ActivityStats> = _stats.asStateFlow()

    // Ошибки отдельно от ленты: в ленте 60 событий, и за минуту сканирования ошибка из неё уходит.
    // Окно «N ош.» в шапке показывает их все (последние [errorCapacity]) — новые в конце.
    private val _errors = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val errors: StateFlow<List<ActivityEvent>> = _errors.asStateFlow()

    fun add(level: ActivityLevel, message: String) {
        val event = ActivityEvent(Clock.System.now().toEpochMilliseconds(), level, message)
        _events.update { (it + event).takeLast(capacity) }
        if (level == ActivityLevel.ERROR) _errors.update { (it + event).takeLast(errorCapacity) }
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

    /**
     * Неудачный запрос — растит счётчик ошибок. С [reason] запрос попадает и в список ошибок (без записи
     * в ленту); без него — только счётчик: вызывающий сам пишет ошибку в ленту через [error].
     */
    fun requestFailed(reason: String? = null) {
        _stats.update { it.copy(errorCount = it.errorCount + 1) }
        if (reason != null) {
            val event = ActivityEvent(Clock.System.now().toEpochMilliseconds(), ActivityLevel.ERROR, reason)
            _errors.update { (it + event).takeLast(errorCapacity) }
        }
    }

    fun clear() {
        _events.update { emptyList() }
        _stats.update { ActivityStats() }
        _errors.update { emptyList() }
    }
}

/** Короткое форматирование числа (до 2 знаков) — без JVM-only String.format. */
fun fmtNum(v: Double): String {
    val rounded = kotlin.math.round(v * 100.0) / 100.0
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
}
