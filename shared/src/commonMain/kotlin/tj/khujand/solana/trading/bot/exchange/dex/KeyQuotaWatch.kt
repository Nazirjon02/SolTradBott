package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Месячная квота ключа CoinGecko: у бесплатного Demo-ключа 10 000 запросов в месяц, при нескольких стратегиях это
 * один-два дня работы бота. Что сервер отвечает, когда квота кончилась, в документации не сказано — описан
 * только отказ 429 «слишком много запросов». Запросы с ключом бот шлёт не чаще раза в секунду (60 в минуту при
 * лимите 100), а после каждого отказа ждёт минуту, поэтому [streakLimit] отказов 429 подряд — это уже не
 * поминутный лимит, а квота.
 *
 * Тогда ключ откладывается: запросы идут без ключа до 1-го числа (UTC), когда квота обновляется, но не дольше
 * [retryAfterMs] — потом ключ пробуется снова. Ошиблись — потеряем пару запросов в сутки, а не месяц ключа.
 */
internal class KeyQuotaWatch(
    private val streakLimit: Int = 3,
    private val retryAfterMs: Long = 24 * 60 * 60_000L,
) {
    // Ответы на запросы с ключом приходят из разных корутин — счётчик меняется атомарно.
    private val streak = MutableStateFlow(0)

    /** Ответ на запрос с ключом (HTTP-код). Возвращает, до какого момента работать без ключа, или null. */
    fun onKeyedResponse(code: Int, nowMs: Long): Long? = when (code) {
        429 -> if (streak.updateAndGet { it + 1 } >= streakLimit) {
            streak.value = 0
            minOf(nextMonthStartUtc(nowMs), nowMs + retryAfterMs)
        } else {
            null
        }
        in 200..299, 404 -> {
            streak.value = 0
            null
        }
        else -> null
    }

    /** Ключ сохранили заново — считаем с нуля. */
    fun reset() {
        streak.value = 0
    }
}

/** Начало следующего месяца по UTC (мс) — с него обновляется месячная квота CoinGecko. */
internal fun nextMonthStartUtc(nowMs: Long): Long {
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.UTC).date
    return LocalDate(today.year, today.month.ordinal + 1, 1).plus(1, DateTimeUnit.MONTH)
        .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
}
