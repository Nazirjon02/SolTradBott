package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/** Месячная квота бесплатного (Demo) ключа CoinGecko. */
const val DEFAULT_KEY_MONTHLY_QUOTA = 10_000

private const val DAY_MS = 24 * 60 * 60_000L

/**
 * Сколько запросов с ключом CoinGecko потрачено. Хранится в настройках, чтобы перезапуск не обнулял счёт.
 * Сутки и месяц — по UTC, как у CoinGecko.
 * @param usedBeforeToday сколько было потрачено в месяце к началу суток — от этого считается сегодняшний лимит.
 */
@Serializable
data class KeyUsage(
    val month: String = "",
    val usedMonth: Int = 0,
    val day: String = "",
    val usedToday: Int = 0,
    val usedBeforeToday: Int = 0,
)

/** Расход ключа для настроек и ленты; [nextDayMs] — когда начнутся новые сутки и с ними новый лимит. */
data class KeyBudgetView(
    val usedToday: Int,
    val allowanceToday: Int,
    val usedMonth: Int,
    val monthlyQuota: Int,
    val nextDayMs: Long,
)

/**
 * Дневной лимит ключа CoinGecko: остаток месячной квоты делится поровну на оставшиеся дни месяца —
 * (квота − потрачено к началу суток) ÷ дней до конца месяца, включая сегодня. Не потратили сегодня — завтра лимит
 * больше, потратили больше обычного — меньше; так квоты хватает ровно до 1-го числа.
 *
 * Считаются запросы, на которые сервер ответил 200 (так считает и CoinGecko). Где хранить счёт и какая квота —
 * параметрами: тесты обходятся без БД, а квоту пользователь может поменять, если перейдёт на платный план.
 */
class KeyBudget(
    private val monthlyQuota: () -> Int = { DEFAULT_KEY_MONTHLY_QUOTA },
    private val load: () -> KeyUsage? = { null },
    private val save: (KeyUsage) -> Unit = {},
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    // Запросы с ключом завершаются в разных корутинах — счёт меняется атомарно.
    private val state = MutableStateFlow<KeyUsage?>(null)

    /** Можно ли сейчас отправить запрос с ключом. */
    fun canSpend(): Boolean {
        val now = nowMs()
        val u = current(now)
        return u.usedToday < allowanceToday(u, now) && u.usedMonth < quota()
    }

    /** Запрос с ключом прошёл (ответ 200). */
    fun record() {
        val now = nowMs()
        val updated = state.updateAndGet { old ->
            val r = rolledOver(old ?: load() ?: KeyUsage(), now)
            r.copy(usedMonth = r.usedMonth + 1, usedToday = r.usedToday + 1)
        } ?: return
        save(updated)
    }

    /**
     * Сверка с кабинетом CoinGecko: за месяц всего потрачено [totalUsed] запросов, сегодняшние включительно. Нужна,
     * когда ключ тратился до того, как бот начал вести счёт, или им пользуется кто-то ещё. Сегодняшний счёт бота
     * остаётся: меньше него месячный быть не может. Дневной лимит пересчитывается от нового остатка.
     */
    fun correctMonthUsage(totalUsed: Int) {
        val now = nowMs()
        val updated = state.updateAndGet { old ->
            val r = rolledOver(old ?: load() ?: KeyUsage(), now)
            val month = totalUsed.coerceAtLeast(r.usedToday)
            r.copy(usedMonth = month, usedBeforeToday = month - r.usedToday)
        } ?: return
        save(updated)
    }

    fun view(): KeyBudgetView {
        val now = nowMs()
        val u = current(now)
        return KeyBudgetView(u.usedToday, allowanceToday(u, now), u.usedMonth, quota(), startOfDayUtc(now) + DAY_MS)
    }

    private fun quota(): Int = monthlyQuota().coerceAtLeast(0)

    /** Счёт с учётом смены суток и месяца; смена сразу сохраняется. */
    private fun current(now: Long): KeyUsage {
        val stored = state.value ?: (load() ?: KeyUsage()).also { state.value = it }
        val r = rolledOver(stored, now)
        if (r != stored) {
            state.value = r
            save(r)
        }
        return r
    }

    private fun allowanceToday(u: KeyUsage, now: Long): Int =
        (quota() - u.usedBeforeToday).coerceAtLeast(0) / daysLeftInMonthUtc(now)
}

/** Новые сутки — сегодняшний счёт с нуля, новый месяц — и месячный. */
internal fun rolledOver(u: KeyUsage, nowMs: Long): KeyUsage {
    val day = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.UTC).date.toString() // 2026-11-11
    val month = day.take(7) // 2026-11
    return when {
        u.month != month -> KeyUsage(month = month, day = day)
        u.day != day -> u.copy(day = day, usedToday = 0, usedBeforeToday = u.usedMonth)
        else -> u
    }
}

/** Начало текущих суток по UTC (мс). */
internal fun startOfDayUtc(nowMs: Long): Long = nowMs - nowMs.mod(DAY_MS)

/** Сколько дней до конца месяца по UTC, включая сегодняшний. */
internal fun daysLeftInMonthUtc(nowMs: Long): Int =
    ((nextMonthStartUtc(nowMs) - startOfDayUtc(nowMs)) / DAY_MS).toInt().coerceAtLeast(1)
