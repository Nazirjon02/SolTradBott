package tj.khujand.solana.trading.bot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Insights
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.engine.RunSummary
import tj.khujand.solana.trading.bot.util.plural

// ─── Сделки ───────────────────────────────────────────────────────────────────
//
// «История» и «Аналитика» — одна вкладка: обе про закрытые сделки. На телефоне между ними переключатель, на
// широком окне они стоят рядом — список слева, итоги справа.

/** Ширина вкладки на широком окне: две колонки, не шире. */
private val TradesMaxWidth = 1480.dp

private enum class HistoryFilter(val label: String) {
    ALL("Все"), WIN("Прибыльные"), LOSS("Убыточные"), DEMO("DEMO"), REAL("REAL");

    fun matches(t: TradeHistoryItem): Boolean = when (this) {
        ALL -> true
        WIN -> t.isWin
        LOSS -> !t.isWin
        DEMO -> t.isDemo
        REAL -> !t.isDemo
    }
}

/**
 * Вкладка «Сделки». После «Нового старта» ([runStartedAt] > 0) итоги по умолчанию — за текущий забег режима
 * ([demoMode]); список сделок — вся история с фильтрами.
 */
@Composable
fun TradesScreen(
    allTrades: List<TradeHistoryItem>,
    runs: List<RunSummary> = emptyList(),
    runStartedAt: Long = 0L,
    demoMode: Boolean = true,
    wide: Boolean = false,
) {
    var tab by remember { mutableStateOf(0) }
    // «Забег / вся история» живёт здесь, а не в аналитике: от выбора зависят и итоги, и календарь, который
    // открывается поверх всей вкладки.
    var currentRunOnly by remember { mutableStateOf(true) }
    var showCalendar by remember { mutableStateOf(false) }
    val scoped = runStartedAt > 0 && currentRunOnly
    val statTrades = if (scoped) allTrades.filter { it.isDemo == demoMode && it.closedAt >= runStartedAt } else allTrades
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.now().toLocalDateTime(zone).date }
    val runNumber = runs.lastOrNull()?.number ?: 0

    if (showCalendar) {
        PnlCalendarScreen(
            trades = statTrades,
            runStarts = runs.filter { it.number > 0 }.map { it.startedAt },
            today = today,
            zone = zone,
            scopeLabel = if (scoped) "Забег №$runNumber (${if (demoMode) "DEMO" else "REAL"})" else "Вся история",
            onClose = { showCalendar = false },
        )
        return
    }

    val total = allTrades.sumOf { it.pnl }
    val subtitle = if (allTrades.isEmpty()) "закрытых сделок пока нет"
    else "${allTrades.size} ${plural(allTrades.size, "сделка", "сделки", "сделок")} · итого ${fmtSignedUsd(total)} USD"
    val stats: @Composable (PaddingValues) -> Unit = { padding ->
        StatsPane(
            trades = statTrades, runs = runs, demoMode = demoMode, scoped = scoped,
            runNumber = runNumber.takeIf { runStartedAt > 0 }, todayDate = today, zone = zone, contentPadding = padding,
            onScopeChange = { currentRunOnly = it }, onOpenCalendar = { showCalendar = true },
        )
    }

    if (wide) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = TradesMaxWidth).fillMaxSize().padding(horizontal = 24.dp)) {
                ScreenHeader("Сделки", subtitle)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text("История", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(10.dp))
                        HistoryPane(allTrades, PaddingValues(bottom = 24.dp))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text("Аналитика", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(10.dp))
                        stats(PaddingValues(bottom = 24.dp))
                    }
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ScreenHeader("Сделки", subtitle)
                SegmentedControl(listOf("История", "Аналитика"), tab) { tab = it }
                Spacer(Modifier.height(12.dp))
            }
            val padding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
            if (tab == 0) HistoryPane(allTrades, padding) else stats(padding)
        }
    }
}

/** Список закрытых сделок с фильтрами и итогом по отфильтрованному. */
@Composable
fun HistoryPane(allTrades: List<TradeHistoryItem>, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    val filtered = allTrades.filter(filter::matches)
    val wins = filtered.count { it.isWin }
    val totalPnl = filtered.sumOf { it.pnl }

    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HistoryFilter.entries.toList()) { f -> HistoryFilterChip(f.label, filter == f) { filter = f } }
            }
        }
        item(key = "totals") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Прибыльных", "$wins", "из ${filtered.size}", if (wins > 0) Green else TextSecondary, Modifier.weight(1f))
                StatCard("Убыточных", "${filtered.size - wins}", "из ${filtered.size}", if (filtered.size - wins > 0) Red else TextSecondary, Modifier.weight(1f))
                StatCard("Итого", fmtSignedUsd(totalPnl), "USD", when { filtered.isEmpty() -> TextSecondary; totalPnl >= 0 -> Green; else -> Red }, Modifier.weight(1f))
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") { EmptyState(if (allTrades.isEmpty()) "Закрытых сделок пока нет" else "Под фильтр ничего не попало") }
        } else {
            // Без ключей: у строки истории нет гарантированно уникального поля, а повтор ключа роняет список.
            items(filtered) { trade -> TradeCard(trade) }
        }
    }
}

/**
 * Итоги по сделкам [trades] (уже отобранным: текущий забег или вся история): кривая доходности, календарь, сегодня
 * и весь период, лучшая и худшая сделка, забеги.
 *
 * @param scoped показан текущий забег, а не вся история.
 * @param runNumber номер идущего забега; null — «Нового старта» не было, переключателя «Забег / Вся история» нет.
 */
@Composable
fun StatsPane(
    trades: List<TradeHistoryItem>,
    runs: List<RunSummary>,
    demoMode: Boolean,
    scoped: Boolean,
    runNumber: Int?,
    todayDate: LocalDate,
    zone: TimeZone,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onScopeChange: (currentRunOnly: Boolean) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
) {
    val pnlData = equityCurve(trades)
    val totalPnl = trades.sumOf { it.pnl }
    val wins = trades.count { it.isWin }
    val losses = trades.size - wins
    val winrate = if (trades.isNotEmpty()) wins * 100 / trades.size else 0
    val todayStart = remember { startOfTodayMillis() }
    val today = trades.filter { it.closedAt >= todayStart }
    val todayWinrate = if (today.isNotEmpty()) today.count { it.isWin } * 100 / today.size else 0
    val best = trades.maxByOrNull { it.pnl }
    val worst = trades.minByOrNull { it.pnl }

    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (runNumber != null) item(key = "scope") { RunScopeChips(scoped, runNumber) { onScopeChange(it) } }

        item(key = "equity") {
            DrxCard {
                CardTitle("Кривая доходности", icon = Icons.Rounded.Insights, subtitle = "${trades.size} ${plural(trades.size, "сделка", "сделки", "сделок")}") {
                    Text(
                        "${fmtSignedUsd(totalPnl)} USD", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        color = when { trades.isEmpty() -> TextSecondary; totalPnl >= 0 -> Green; else -> Red }
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (pnlData.size >= 2) {
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) { drawPnlChart(pnlData) }
                    Spacer(Modifier.height(6.dp))
                    val chronological = trades.sortedBy { it.closedAt }
                    val first = chronological.first().closedAt
                    val last = chronological.last().closedAt
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (0..4).forEach { i -> Text(dateLabel(first + (last - first) * i / 4), fontSize = 11.sp, color = TextSecondary) }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        HintText("Кривая появится после двух закрытых сделок")
                    }
                }
            }
        }

        // Календарь P&L: кривая выше — сумма нарастающим итогом, календарь — каждый день отдельно.
        item(key = "calendar") { PnlCalendarEntryCard(trades, todayDate, zone) { onOpenCalendar() } }

        item(key = "periods") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPanel(
                    "Сегодня",
                    listOf(
                        "P&L" to "${fmtSignedUsd(today.sumOf { it.pnl })} USD",
                        "Сделок" to "${today.size}",
                        "В плюс" to "$todayWinrate%",
                        "Комиссия" to "${fmtUsd(today.sumOf { it.fee })} USD",
                    ),
                    Modifier.weight(1f)
                )
                StatsPanel(
                    if (scoped) "Забег" else "Всё время",
                    listOf(
                        "P&L" to "${fmtSignedUsd(totalPnl)} USD",
                        "Сделок" to "${trades.size}",
                        "В плюс" to "$winrate%",
                        "Комиссия" to "${fmtUsd(trades.sumOf { it.fee })} USD",
                    ),
                    Modifier.weight(1f)
                )
            }
        }

        item(key = "extremes") {
            DrxCard {
                CardTitle("Лучшая и худшая сделка", icon = Icons.Rounded.EmojiEvents)
                Spacer(Modifier.height(12.dp))
                ExtremeRow("Лучшая", best?.symbol ?: "—", best?.let { "${fmtSignedUsd(it.pnl)} USD" } ?: "—",
                    best?.let { if (it.pnl >= 0) Green else Red } ?: TextSecondary)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                Spacer(Modifier.height(8.dp))
                ExtremeRow("Худшая", worst?.symbol ?: "—", worst?.let { "${fmtSignedUsd(it.pnl)} USD" } ?: "—",
                    worst?.let { if (it.pnl >= 0) Green else Red } ?: TextSecondary)
            }
        }

        item(key = "winloss") {
            DrxCard {
                Text("Прибыльные и убыточные", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(12.dp))
                WinLossBar(wins = wins, losses = losses)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    LegendDot("в плюс $wins ($winrate%)", if (trades.isEmpty()) TextSecondary else Green)
                    LegendDot("в минус $losses (${if (trades.isEmpty()) 0 else 100 - winrate}%)", if (trades.isEmpty()) TextSecondary else Red)
                }
            }
        }

        // Забеги: прошлые итоги рядом — ради этого «Новый старт» и не удаляет сделки.
        if (runs.isNotEmpty()) item(key = "runs") { RunsCard(runs, if (demoMode) "DEMO" else "REAL") }
    }
}

@Composable
fun StatsPanel(title: String, rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = SurfaceCard, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontSize = 13.sp, color = TextSecondary)
                    Text(value, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun ExtremeRow(label: String, trade: String, pnl: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, fontSize = 12.sp, color = TextSecondary)
            Text(trade, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        Text(pnl, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
fun WinLossBar(wins: Int, losses: Int) {
    val total = wins + losses
    val winFraction = if (total == 0) 0.5f else wins.toFloat() / total
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        Box(Modifier.weight(winFraction.coerceAtLeast(0.01f)).fillMaxHeight().background(if (total == 0) BorderColor else Green))
        Box(Modifier.weight((1f - winFraction).coerceAtLeast(0.01f)).fillMaxHeight().background(if (total == 0) BorderColor else Red))
    }
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = TextSecondary)
    }
}

@Composable
fun HistoryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Green else SurfaceCard,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onClick() }
    ) {
        Text(
            label,
            color = if (selected) Color.Black else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
