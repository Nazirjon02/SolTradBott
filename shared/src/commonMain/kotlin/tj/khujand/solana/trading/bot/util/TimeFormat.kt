package tj.khujand.solana.trading.bot.util

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * KMP-форматирование времени сделок. Один и тот же момент показываем в двух видах:
 *  • DEX  — в UTC, как на сайте/графике DexScreener (чтобы сверять свечи);
 *  • наше — в локальной таймзоне устройства (реальное время исполнения у нас).
 */

/** «06.07 14:32» — дата и время момента [ms] в указанной зоне. «—» для пустого времени (ms<=0). */
fun formatDateTime(ms: Long, zone: TimeZone): String {
    if (ms <= 0) return "—"
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    val d = dt.day.toString().padStart(2, '0')
    val mo = (dt.month.ordinal + 1).toString().padStart(2, '0')
    val h = dt.hour.toString().padStart(2, '0')
    val mi = dt.minute.toString().padStart(2, '0')
    return "$d.$mo $h:$mi"
}

/** Время момента [ms] как на DexScreener (UTC). */
fun formatDexTime(ms: Long): String = formatDateTime(ms, TimeZone.UTC)

/** Наше локальное реальное время момента [ms]. */
fun formatLocalTime(ms: Long): String = formatDateTime(ms, TimeZone.currentSystemDefault())

/** Однострочная метка для Telegram: «DEX 06.07 09:32 UTC · наше 06.07 14:32». */
fun tradeTimeLine(ms: Long): String =
    "DEX ${formatDexTime(ms)} UTC · наше ${formatLocalTime(ms)}"
