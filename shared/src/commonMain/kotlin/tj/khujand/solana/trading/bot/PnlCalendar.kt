package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.engine.fmtNum

// Календарь P&L (порт из MRX): какой день закрыт в плюс, какой — в минус. Открывается с «Аналитики».

/** Итог одного дня: сумма PnL закрытых в этот день сделок и их число. */
data class DayPnl(val pnl: Double, val trades: Int)

/** PnL по дням закрытия сделок (местные дни зоны [zone]). */
fun dailyPnl(trades: List<TradeHistoryItem>, zone: TimeZone): Map<LocalDate, DayPnl> =
    trades.groupBy { Instant.fromEpochMilliseconds(it.closedAt).toLocalDateTime(zone).date }
        .mapValues { (_, list) -> DayPnl(list.sumOf { it.pnl }, list.size) }

/** Число дней в месяце [month] (1..12) года [year]. */
fun daysInMonth(year: Int, month: Int): Int {
    val first = LocalDate(year, month, 1)
    return first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
}

/**
 * Клетки сетки месяца с понедельника: null — пустые клетки до 1-го числа и после последнего, чтобы
 * сетка состояла из целых недель.
 */
fun monthCells(year: Int, month: Int): List<LocalDate?> {
    val first = LocalDate(year, month, 1)
    val lead = first.dayOfWeek.ordinal // ПН = 0
    val days = (1..daysInMonth(year, month)).map { LocalDate(year, month, it) }
    val cells = List<LocalDate?>(lead) { null } + days
    val tail = (7 - cells.size % 7) % 7
    return cells + List(tail) { null }
}

private val MONTHS = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)
private val MONTHS_GENITIVE = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
private val WEEKDAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

private fun LocalDate.monthNumber(): Int = month.ordinal + 1

/** «+12.4» / «−3» — сумма дня без валюты, коротко для клетки. */
private fun shortPnl(v: Double): String = when {
    v > 0 -> "+${fmtNum(roundForCell(v))}"
    v < 0 -> "−${fmtNum(roundForCell(-v))}"
    else -> "0"
}

private fun roundForCell(v: Double): Double = if (v >= 100) kotlin.math.round(v) else kotlin.math.round(v * 10) / 10

/** Цвет клетки: насыщенность — от самого крупного по модулю дня ЭТОГО месяца. */
private fun dayColor(pnl: Double, maxAbs: Double): Color {
    if (pnl == 0.0 || maxAbs <= 0.0) return SurfaceCard
    val k = (abs(pnl) / maxAbs).coerceIn(0.0, 1.0).toFloat()
    return (if (pnl > 0) Green else Red).copy(alpha = 0.18f + 0.62f * k)
}

/**
 * Вход в календарь с «Аналитики»: последние семь дней клетками и их итог — видно, что за кнопкой,
 * ещё до нажатия.
 */
@Composable
fun PnlCalendarEntryCard(trades: List<TradeHistoryItem>, today: LocalDate, zone: TimeZone, onOpen: () -> Unit) {
    val byDay = remember(trades) { dailyPnl(trades, zone) }
    val week = (6 downTo 0).map { today.minus(it, DateTimeUnit.DAY) }
    val weekPnl = week.sumOf { byDay[it]?.pnl ?: 0.0 }
    val maxAbs = week.maxOfOrNull { abs(byDay[it]?.pnl ?: 0.0) } ?: 0.0
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpen() },
        color = SurfaceCard, shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("📅 Календарь P&L", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "7 дней: ${shortPnl(weekPnl)} USD  ›",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = when { weekPnl > 0 -> Green; weekPnl < 0 -> Red; else -> TextSecondary }
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { d ->
                    val day = byDay[d]
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                            .background(dayColor(day?.pnl ?: 0.0, maxAbs)).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(WEEKDAYS[d.dayOfWeek.ordinal], fontSize = 9.sp, color = TextSecondary, lineHeight = 11.sp)
                        Text("${d.day}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * Экран «Календарь P&L»: месяц сеткой, дни в плюс — зелёные, в минус — красные. Стрелки листают
 * месяцы; по нажатию на день — его итог. [runStarts] — дни «Нового старта» помечены флажком.
 * [today] — параметром, чтобы тест рендера не зависел от даты прогона.
 */
@Composable
fun PnlCalendarScreen(
    trades: List<TradeHistoryItem>,
    runStarts: List<Long>,
    today: LocalDate,
    zone: TimeZone,
    scopeLabel: String,
    onClose: () -> Unit,
) {
    val byDay = remember(trades) { dailyPnl(trades, zone) }
    val startDays = remember(runStarts) { runStarts.map { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }.toSet() }
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthNumber()) }
    var selected by remember { mutableStateOf<LocalDate?>(today) }

    val cells = monthCells(year, month)
    val inMonth = cells.filterNotNull()
    val monthTotal = inMonth.sumOf { byDay[it]?.pnl ?: 0.0 }
    val monthTrades = inMonth.sumOf { byDay[it]?.trades ?: 0 }
    val maxAbs = inMonth.maxOfOrNull { abs(byDay[it]?.pnl ?: 0.0) } ?: 0.0

    fun shift(delta: Int) {
        val d = LocalDate(year, month, 1).plus(delta, DateTimeUnit.MONTH)
        year = d.year
        month = d.monthNumber()
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Календарь P&L", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text(scopeLabel, fontSize = 12.sp, color = TextSecondary)
            }
            Surface(color = SurfaceCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onClose() }) {
                Text("✕ Закрыть", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        // На широком окне десктопа клетки растянулись бы до огромных — держим ширину телефона.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = 26.sp, color = TextPrimary, modifier = Modifier.clickable { shift(-1) }.padding(horizontal = 12.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${MONTHS[month - 1]} $year", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            "${shortPnl(monthTotal)} USD · сделок $monthTrades", fontSize = 12.sp,
                            color = when { monthTotal > 0 -> Green; monthTotal < 0 -> Red; else -> TextSecondary }
                        )
                    }
                    Text("›", fontSize = 26.sp, color = TextPrimary, modifier = Modifier.clickable { shift(1) }.padding(horizontal = 12.dp))
                }
                Row(Modifier.fillMaxWidth()) {
                    WEEKDAYS.forEach { w ->
                        Text(w, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { d ->
                            Box(Modifier.weight(1f).aspectRatio(0.8f)) {
                                if (d != null) {
                                    DayCell(
                                        date = d, day = byDay[d], maxAbs = maxAbs, isToday = d == today,
                                        isSelected = d == selected, isRunStart = d in startDays,
                                    ) { selected = d }
                                }
                            }
                        }
                    }
                }
                val sel = selected
                if (sel != null && sel.year == year && sel.monthNumber() == month) {
                    val day = byDay[sel]
                    Surface(color = SurfaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "${sel.day} ${MONTHS_GENITIVE[sel.monthNumber() - 1]} ${sel.year}" +
                                    (if (sel in startDays) " · 🏁 новый старт" else ""),
                                fontSize = 13.sp, color = TextSecondary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (day == null) "Сделок не закрывалось" else "${shortPnl(day.pnl)} USD · сделок ${day.trades}",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = when { day == null -> TextSecondary; day.pnl > 0 -> Green; day.pnl < 0 -> Red; else -> TextPrimary }
                            )
                        }
                    }
                }
                Text(
                    "Цвет — сумма закрытых в этот день сделок; чем насыщеннее, тем ближе к лучшему (худшему) дню месяца. " +
                        "Синяя рамка — сегодня, 🏁 — «Новый старт».",
                    fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    day: DayPnl?,
    maxAbs: Double,
    isToday: Boolean,
    isSelected: Boolean,
    isRunStart: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    // Border нулевой толщины Compose рисует волосяной линией — поэтому без рамки его нет вовсе.
    val border = when {
        isSelected -> Modifier.border(1.5.dp, TextPrimary, shape)
        isToday -> Modifier.border(1.5.dp, Blue, shape)
        else -> Modifier
    }
    Column(
        Modifier.fillMaxSize().clip(shape).background(dayColor(day?.pnl ?: 0.0, maxAbs)).then(border)
            .clickable { onClick() }.padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Межстрочный интервал — явно: Material3 даёт тексту 24sp, и в клетке узкого телефона
        // число дня и сумма налезали бы друг на друга.
        Text(
            "${date.day}${if (isRunStart) "🏁" else ""}", fontSize = 11.sp, lineHeight = 13.sp,
            color = TextPrimary, fontWeight = FontWeight.SemiBold
        )
        if (day != null) {
            Text(shortPnl(day.pnl), fontSize = 9.sp, lineHeight = 11.sp, color = TextPrimary, maxLines = 1)
        }
    }
}
