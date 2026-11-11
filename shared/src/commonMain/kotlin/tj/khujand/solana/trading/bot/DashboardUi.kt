@file:OptIn(ExperimentalLayoutApi::class)

package tj.khujand.solana.trading.bot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import tj.khujand.solana.trading.bot.core.engine.ActivityEvent
import tj.khujand.solana.trading.bot.core.engine.ActivityLevel
import tj.khujand.solana.trading.bot.core.engine.ActivityStats
import tj.khujand.solana.trading.bot.core.engine.EXIT_BLOCKED_AFTER
import tj.khujand.solana.trading.bot.core.strategy.EntryDiagnosis
import tj.khujand.solana.trading.bot.core.strategy.FunnelSnapshot
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.WatchItem
import tj.khujand.solana.trading.bot.data.AccountBalance
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.OpenPosition
import tj.khujand.solana.trading.bot.util.formatDexTime
import tj.khujand.solana.trading.bot.util.formatLargeNumber
import tj.khujand.solana.trading.bot.util.formatLocalTime
import tj.khujand.solana.trading.bot.util.plural

// ─── Главная ──────────────────────────────────────────────────────────────────
//
// Порядок блоков — по тому, что важнее увидеть первым: деньги и управление, открытые позиции (деньги под
// риском), почему бот не входит и чего ждёт, что он делал, итоги. На широком окне — две колонки: слева
// деньги, справа работа бота; открытый график сделки встаёт на место правой колонки — рядом с позициями.

/** Ширина главной на широком окне: дальше две колонки только растягивают строки. */
private val DashboardMaxWidth = 1480.dp

@Composable
fun DashboardScreen(
    botStatus: BotStatus,
    strategies: List<StrategyConfig>,
    balance: AccountBalance? = null,
    connected: Boolean = false,
    activity: List<ActivityEvent> = emptyList(),
    stats: ActivityStats = ActivityStats(),
    errors: List<ActivityEvent> = emptyList(),
    trades: List<TradeHistoryItem> = emptyList(),
    openPositions: List<OpenPosition> = emptyList(),
    onClosePosition: (String) -> Unit = {},
    watch: List<WatchItem> = emptyList(),
    onWatchClick: (WatchItem) -> Unit = {},
    onPositionChart: ((OpenPosition) -> Unit)? = null,
    diagnosis: EntryDiagnosis? = null,
    funnel: FunnelSnapshot? = null,
    signalOnly: Boolean = false,
    lastCycleAgo: String? = null,
    modeLabel: String = if (balance?.isDemo != false) "DEMO" else "REAL",
    wide: Boolean = false,
    /** Широкое окно: панель вместо правой колонки (график сделки). */
    sidePane: (@Composable () -> Unit)? = null,
    onOpenTrades: () -> Unit = {},
    onOpenStrategies: () -> Unit = {},
    onStatusChange: (BotStatus) -> Unit,
) {
    val running = botStatus == BotStatus.RUNNING
    val todayStart = remember { startOfTodayMillis() }
    val today = trades.filter { it.closedAt >= todayStart }

    val header: @Composable () -> Unit = { DashboardHeader(botStatus, stats, connected, errors) }
    val hero: @Composable () -> Unit = {
        HeroCard(balance, connected, modeLabel, today.sumOf { it.pnl }, today.size, botStatus, onStatusChange)
    }
    val why: @Composable () -> Unit = {
        if (diagnosis != null && funnel != null) WhyNoTradesCard(diagnosis, funnel, signalOnly, lastCycleAgo)
    }
    val positions: LazyListScope.() -> Unit = {
        if (openPositions.isNotEmpty()) {
            item(key = "positions-header") {
                val unrealized = openPositions.sumOf { it.pnlUsd }
                SectionHeader(
                    "Открытые позиции · ${openPositions.size}",
                    "${fmtSignedUsd(unrealized)} USD",
                    actionColor = if (unrealized >= 0) Green else Red,
                )
            }
            items(openPositions, key = { "pos-" + it.tradeId }) { p ->
                PositionCard(p, onClose = { onClosePosition(p.tradeId) }, onChart = onPositionChart?.let { { it(p) } })
            }
        }
    }
    val recent: LazyListScope.() -> Unit = {
        item(key = "recent-header") { SectionHeader("Последние сделки", "Все сделки ›", onAction = onOpenTrades) }
        val recentTrades = trades.take(3)
        if (recentTrades.isEmpty()) item(key = "recent-empty") { EmptyState("Закрытых сделок пока нет") }
        else items(recentTrades) { t -> TradeCard(t) }
    }

    if (!wide) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { header() }
                item(key = "hero") { hero() }
                positions()
                if (diagnosis != null && funnel != null) item(key = "why") { why() }
                item(key = "watch") { WatchBoardCard(watch, running, onWatchClick) }
                item(key = "activity") { ActivityFeedCard(activity, running) }
                item(key = "performance") { PerformanceCard(trades) }
                item(key = "strategies") { StrategiesSummaryCard(strategies, trades, onOpenStrategies) }
                recent()
            }
        }
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = DashboardMaxWidth).fillMaxSize()) {
            Box(Modifier.padding(horizontal = 24.dp)) { header() }
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "hero") { hero() }
                    positions()
                    item(key = "performance") { PerformanceCard(trades) }
                    recent()
                }
                if (sidePane != null) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))) {
                        sidePane()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (diagnosis != null && funnel != null) item(key = "why") { why() }
                        item(key = "watch") { WatchBoardCard(watch, running, onWatchClick) }
                        item(key = "activity") { ActivityFeedCard(activity, running) }
                        item(key = "strategies") { StrategiesSummaryCard(strategies, trades, onOpenStrategies) }
                    }
                }
            }
        }
    }
}

// ─── Шапка ────────────────────────────────────────────────────────────────────

@Composable
fun DashboardHeader(
    status: BotStatus,
    stats: ActivityStats = ActivityStats(),
    connected: Boolean = false,
    errors: List<ActivityEvent> = emptyList(),
) {
    var showErrors by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("DRX Bot", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            PulseLine(stats, connected) { showErrors = true }
        }
        StatusChip(status)
    }
    if (showErrors) ErrorsDialog(errors, stats.errorCount) { showErrors = false }
}

/**
 * Пульс сессии: точка + счётчик запросов + время последнего успешного ответа DEX-API.
 * Счётчик ошибок кликабелен — [onErrorsClick] открывает их список.
 */
@Composable
private fun PulseLine(stats: ActivityStats, connected: Boolean, onErrorsClick: () -> Unit) {
    val lastOk = stats.lastOkAt
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (lastOk != null) Green else TextSecondary, 6.dp)
        Spacer(Modifier.width(6.dp))
        when {
            !connected -> Text("Рантайм не подключён", fontSize = 12.sp, color = TextSecondary)
            lastOk != null -> Text("${stats.okCount} запр. · ответ ${formatClock(lastOk)}", fontSize = 12.sp, color = TextSecondary)
            else -> Text("ожидание ответа DEX…", fontSize = 12.sp, color = TextSecondary)
        }
        if (stats.errorCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "· ${stats.errorCount} ош. ›", fontSize = 12.sp, color = Red, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onErrorsClick() }.padding(horizontal = 2.dp)
            )
        }
    }
}

/**
 * Окно со списком ошибок сессии (новые сверху): лента активности держит 60 событий и за минуту
 * сканирования вытесняет ошибку, а здесь — последние 100. [total] — счётчик из шапки: в него входят и
 * сбои запросов без текста, поэтому строк может быть меньше.
 */
@Composable
private fun ErrorsDialog(errors: List<ActivityEvent>, total: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised,
        title = { Text("Ошибки сессии · $total", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            if (errors.isEmpty()) {
                HintText("Текстов ошибок нет — счётчик растёт и от сбоев запросов, о которых лента уже написала.")
            } else {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    errors.asReversed().forEach { e ->
                        Row {
                            Text(formatClock(e.timestamp), fontSize = 11.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(8.dp))
                            Text(e.message, fontSize = 12.sp, color = TextPrimary, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { ActionButton("Закрыть", Green, onClick = onDismiss) }
    )
}

@Composable
fun StatusChip(status: BotStatus) {
    val (color, label) = when (status) {
        BotStatus.RUNNING -> Green to "РАБОТАЕТ"
        BotStatus.STOPPED -> Red to "ОСТАНОВЛЕН"
        BotStatus.PAUSED -> Amber to "ПАУЗА"
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 7.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/** Цвет режима счёта: DEMO — фиолетовый, SHADOW — синий, REAL — янтарный (деньги настоящие). */
internal fun modeColor(mode: String): Color = when (mode.uppercase()) {
    "REAL" -> Amber
    "SHADOW" -> Blue
    else -> Purple
}

// ─── Баланс и управление ──────────────────────────────────────────────────────

/**
 * Главная карточка: баланс, итог дня и кнопки управления ботом. Кнопки — здесь, а не отдельной строкой ниже:
 * «сколько денег» и «бот работает или нет» смотрят вместе.
 */
@Composable
fun HeroCard(
    balance: AccountBalance?,
    connected: Boolean,
    modeLabel: String,
    todayPnl: Double,
    todayTrades: Int,
    status: BotStatus,
    onStatusChange: (BotStatus) -> Unit,
) {
    DrxCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (balance?.isDemo != false) "Баланс · демо-счёт" else "Баланс · кошелёк", fontSize = 12.sp, color = TextSecondary)
                }
                Spacer(Modifier.height(4.dp))
                when {
                    !connected -> Text("Рантайм не подключён", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Amber)
                    balance == null -> Text("Загрузка…", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = TextSecondary)
                    else -> Text("${fmtUsd(balance.totalUsd)} USD", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (todayTrades == 0) "Сегодня сделок не было"
                    else "Сегодня ${fmtSignedUsd(todayPnl)} USD · $todayTrades ${plural(todayTrades, "сделка", "сделки", "сделок")}",
                    fontSize = 12.sp,
                    color = when { todayTrades == 0 -> TextSecondary; todayPnl >= 0 -> Green; else -> Red },
                )
                if (balance != null && !balance.isDemo) {
                    Text("${formatNumberRu(balance.sol, 4)} SOL на кошельке", fontSize = 12.sp, color = TextSecondary)
                }
            }
            Pill(modeLabel, modeColor(modeLabel))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                "Старт", Green, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, filled = true,
                enabled = status != BotStatus.RUNNING,
            ) { onStatusChange(BotStatus.RUNNING) }
            ActionButton(
                "Пауза", Amber, Modifier.weight(1f), icon = Icons.Rounded.Pause,
                enabled = status == BotStatus.RUNNING,
            ) { onStatusChange(BotStatus.PAUSED) }
            ActionButton(
                "Стоп", Red, Modifier.weight(1f), icon = Icons.Rounded.Stop,
                enabled = status != BotStatus.STOPPED,
            ) { onStatusChange(BotStatus.STOPPED) }
        }
    }
}

// ─── Лента активности ─────────────────────────────────────────────────────────

@Composable
fun ActivityFeedCard(events: List<ActivityEvent>, running: Boolean) {
    DrxCard {
        CardTitle("Активность", icon = Icons.Rounded.Bolt, iconTint = if (running) Green else TextSecondary) {
            Text(if (running) "в работе" else "ожидание", fontSize = 12.sp, color = if (running) Green else TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            HintText("Журнал пуст — нажмите «Старт», чтобы бот начал сканировать.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                events.takeLast(8).asReversed().forEach { ActivityRow(it) }
            }
        }
    }
}

@Composable
private fun ActivityRow(e: ActivityEvent) {
    val dotColor = when (e.level) {
        ActivityLevel.SUCCESS -> Green
        ActivityLevel.WARN -> Amber
        ActivityLevel.ERROR -> Red
        ActivityLevel.INFO -> TextSecondary
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(formatClock(e.timestamp), fontSize = 11.sp, color = TextSecondary, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(62.dp).padding(top = 1.dp))
        Box(Modifier.padding(top = 5.dp)) { StatusDot(dotColor, 6.dp) }
        Spacer(Modifier.width(8.dp))
        Text(e.message, fontSize = 13.sp, color = TextPrimary, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

// ─── Доходность ───────────────────────────────────────────────────────────────

/** Кривая доходности по закрытым сделкам и три итога под ней: P&L, доля прибыльных, худшая просадка. */
@Composable
fun PerformanceCard(trades: List<TradeHistoryItem>) {
    val data = equityCurve(trades)
    val total = trades.sumOf { it.pnl }
    val wins = trades.count { it.isWin }
    val winrate = if (trades.isNotEmpty()) wins * 100 / trades.size else 0
    var peak = 0.0
    var maxDd = 0.0
    data.forEach { v ->
        if (v > peak) peak = v
        if (v - peak < maxDd) maxDd = v - peak
    }
    DrxCard {
        CardTitle("Доходность", icon = Icons.Rounded.Insights, subtitle = "${trades.size} ${plural(trades.size, "сделка", "сделки", "сделок")} · закрытые") {
            Text(
                "${fmtSignedUsd(total)} USD", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                color = when { trades.isEmpty() -> TextSecondary; total >= 0 -> Green; else -> Red },
            )
        }
        Spacer(Modifier.height(14.dp))
        if (data.size >= 2) {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) { drawPnlChart(data) }
            Spacer(Modifier.height(6.dp))
            val chronological = trades.sortedBy { it.closedAt }
            val first = chronological.first().closedAt
            val last = chronological.last().closedAt
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (0..4).forEach { i -> Text(dateLabel(first + (last - first) * i / 4), fontSize = 11.sp, color = TextSecondary) }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                HintText("Кривая появится после двух закрытых сделок")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("P&L", fmtSignedUsd(total), if (trades.isEmpty()) TextSecondary else if (total >= 0) Green else Red, Modifier.weight(1f))
            MiniStat("Прибыльных", "$winrate%", if (trades.isEmpty()) TextSecondary else if (winrate >= 50) Green else Amber, Modifier.weight(1f))
            MiniStat("Просадка", fmtUsd(maxDd), if (maxDd < 0) Red else TextSecondary, Modifier.weight(1f))
        }
    }
}

internal fun DrawScope.drawPnlChart(data: List<Double>) {
    if (data.size < 2) return
    val w = size.width
    val h = size.height
    val pad = 4.dp.toPx()
    val minVal = minOf(data.min(), 0.0)
    val maxVal = maxOf(data.max(), 0.0)
    val range = maxVal - minVal

    fun xOf(i: Int) = w * i / (data.size - 1)
    fun yOf(v: Double) = if (range == 0.0) h / 2f else (h - pad) - ((v - minVal) / range * (h - 2 * pad)).toFloat()

    repeat(4) { i ->
        val y = pad + (h - 2 * pad) * i / 3f
        drawLine(BorderColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
    // Ноль — пунктиром-ориентиром: выше линии заработали, ниже — потеряли.
    if (range > 0.0) drawLine(TextSecondary.copy(alpha = 0.35f), Offset(0f, yOf(0.0)), Offset(w, yOf(0.0)), strokeWidth = 1.dp.toPx())

    val lineColor = if (data.last() >= 0) Green else Red
    val areaPath = Path().apply {
        moveTo(xOf(0), yOf(data[0]))
        data.forEachIndexed { i, v -> if (i > 0) lineTo(xOf(i), yOf(v)) }
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(areaPath, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)), startY = 0f, endY = h))
    val linePath = Path().apply {
        moveTo(xOf(0), yOf(data[0]))
        data.forEachIndexed { i, v -> if (i > 0) lineTo(xOf(i), yOf(v)) }
    }
    drawPath(linePath, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val lx = xOf(data.size - 1)
    val ly = yOf(data.last())
    drawCircle(lineColor.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = Offset(lx, ly))
    drawCircle(lineColor, radius = 4.5.dp.toPx(), center = Offset(lx, ly))
}

@Composable
fun StatCard(title: String, value: String, sub: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = SurfaceCard, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = color, textAlign = TextAlign.Center)
            Text(sub, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 14.sp)
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = BgDark.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        }
    }
}

// ─── Заголовки и пустые состояния ─────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, action: String = "", actionColor: Color = Blue, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (action.isNotEmpty()) {
            Text(
                action, fontSize = 13.sp, color = actionColor, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .let { m -> onAction?.let { m.clickable { it() } } ?: m }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        Text(message, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
        Text(
            text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ─── Стратегии на главной ─────────────────────────────────────────────────────

/** Все стратегии одной карточкой: включена ли, ТФ, итог и доля прибыльных — подробности на вкладке «Стратегии». */
@Composable
fun StrategiesSummaryCard(strategies: List<StrategyConfig>, trades: List<TradeHistoryItem>, onOpen: () -> Unit = {}) {
    DrxCard {
        CardTitle(
            "Стратегии", icon = Icons.Rounded.SmartToy,
            subtitle = "включено ${strategies.count { it.isActive }} из ${strategies.size}",
        ) {
            Text("Настроить ›", fontSize = 13.sp, color = Blue, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpen() }.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (strategies.isEmpty()) HintText("Стратегии не настроены — создайте первую на вкладке «Стратегии».")
        strategies.forEachIndexed { i, cfg ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            val own = trades.filter { it.strategy == cfg.name }
            val pnl = own.sumOf { it.pnl }
            val winrate = if (own.isNotEmpty()) own.count { it.isWin } * 100 / own.size else 0
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (cfg.isActive) Green else TextSecondary.copy(alpha = 0.5f), 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(cfg.name.ifEmpty { cfg.type.toTypeLabel() }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (cfg.isActive) TextPrimary else TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${cfg.timeframe} · ${cfg.type.toTypeLabel()}" + if (!cfg.isActive) " · выключена" else "",
                        fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (own.isEmpty()) "—" else "${fmtSignedUsd(pnl)} USD", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = when { own.isEmpty() -> TextSecondary; pnl >= 0 -> Green; else -> Red },
                    )
                    if (own.isNotEmpty()) Text("${own.size} сд. · $winrate% в плюс", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }
    }
}

// ─── Открытая позиция ─────────────────────────────────────────────────────────

/**
 * Карточка открытой позиции: P&L, где цена между стопом и целью, неудачные продажи, график и ручное закрытие.
 * Закрытие — через диалог с текущим итогом: рыночный выход необратим.
 */
@Composable
fun PositionCard(pos: OpenPosition, onClose: () -> Unit, onChart: (() -> Unit)? = null) {
    val pnlColor = if (pos.pnlUsd >= 0) Green else Red
    val mode = pos.execMode.ifEmpty { if (pos.isDemo) "DEMO" else "REAL" }
    var confirm by remember(pos.tradeId) { mutableStateOf(false) }

    DrxCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(pos.symbol, fontWeight = FontWeight.ExtraBold, color = TextPrimary, fontSize = 17.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Pill(mode, modeColor(mode))
            }
            Spacer(Modifier.width(8.dp))
            Text(fmtPct(pos.pnlPercent), color = pnlColor, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
        Text(
            "${pos.strategyName} · открыта ${formatLocalTime(pos.openedAt)}",
            fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            PriceCol("Вход", fmtPrice(pos.entryPrice), Modifier.weight(1f))
            PriceCol("Сейчас", fmtPrice(pos.currentPrice), Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("P&L", fontSize = 11.sp, color = TextSecondary)
                Text("${fmtSignedUsd(pos.pnlUsd)} USD", fontWeight = FontWeight.ExtraBold, color = pnlColor, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        PriceRangeBar(stop = pos.stopLoss, entry = pos.entryPrice, current = pos.currentPrice, take = pos.takeProfit)

        // Продажа не прошла: монитор повторяет с бо́льшим slippage, после EXIT_BLOCKED_AFTER подряд — с паузой.
        if (pos.exitFailures > 0) {
            val blocked = pos.exitFailures >= EXIT_BLOCKED_AFTER
            Spacer(Modifier.height(10.dp))
            Banner(
                "${if (blocked) "Продажа заблокирована" else "Продажа не прошла"} (${pos.exitFailures} подряд): ${pos.exitNote}",
                if (blocked) Tone.BAD else Tone.WARN,
            )
        }

        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoChip("🪙", "${formatLargeNumber(pos.qtyRemaining)} шт.")
            InfoChip("💰", "стоит $${fmtUsd(pos.valueUsd)}")
            InfoChip("📥", "вложено $${fmtUsd(pos.sizeUsd)}")
        }
        if (pos.mint.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MintCopyRow(pos.mint)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onChart != null) {
                ActionButton("График", Blue, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.ShowChart, onClick = onChart)
            }
            ActionButton("Закрыть", Red, Modifier.weight(1f), icon = Icons.Rounded.Close) { confirm = true }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Закрыть ${pos.symbol}?",
            text = "Позиция продаётся по рынку сейчас: P&L ${fmtSignedUsd(pos.pnlUsd)} USD (${fmtPct(pos.pnlPercent)}). " +
                "Отменить продажу будет нельзя." +
                if (mode == "REAL") " Это REAL-сделка: своп через Jupiter с вашего кошелька." else "",
            confirmLabel = "Закрыть позицию",
            icon = Icons.Rounded.WarningAmber,
            onConfirm = { confirm = false; onClose() },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * Путь цены от стопа к цели: метка входа и точка текущей цены. Сразу видно, сколько осталось до стопа и до
 * цели, — без вычитания цен в уме.
 */
@Composable
fun PriceRangeBar(stop: Double, entry: Double, current: Double, take: Double) {
    if (stop <= 0.0 || take <= stop) return
    fun frac(p: Double) = ((p - stop) / (take - stop)).coerceIn(0.0, 1.0).toFloat()
    val fe = frac(entry)
    val fc = frac(current)
    val color = if (current >= entry) Green else Red
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = 6.dp.toPx()
            val y = size.height / 2
            drawRoundRect(BorderColor, topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
            val x1 = size.width * minOf(fe, fc)
            val x2 = size.width * maxOf(fe, fc)
            drawRoundRect(color.copy(alpha = 0.55f), topLeft = Offset(x1, y - h / 2), size = Size((x2 - x1).coerceAtLeast(h), h),
                cornerRadius = CornerRadius(h / 2))
            drawLine(TextPrimary.copy(alpha = 0.8f), Offset(size.width * fe, y - h), Offset(size.width * fe, y + h), strokeWidth = 2.dp.toPx())
            drawCircle(color, radius = 6.dp.toPx(), center = Offset(size.width * fc, y))
            drawCircle(BgDark, radius = 2.5.dp.toPx(), center = Offset(size.width * fc, y))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("стоп ${fmtPrice(stop)}", fontSize = 11.sp, color = Red)
            Spacer(Modifier.weight(1f))
            Text("цель ${fmtPrice(take)}", fontSize = 11.sp, color = Green)
        }
    }
}

// ─── Закрытая сделка ──────────────────────────────────────────────────────────

@Composable
fun TradeCard(item: TradeHistoryItem) {
    val pnlColor = if (item.isWin) Green else Red
    DrxCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(item.symbol, fontWeight = FontWeight.ExtraBold, color = TextPrimary, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Pill(if (item.isDemo) "DEMO" else "REAL", if (item.isDemo) Purple else Amber)
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (item.isWin) "ПРИБЫЛЬ" else "УБЫТОК", pnlColor)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            PriceCol("Вход", fmtPrice(item.entryPrice), Modifier.weight(1f), item.openedAt)
            PriceCol("Выход", fmtPrice(item.exitPrice), Modifier.weight(1f), item.closedAt)
            Column(horizontalAlignment = Alignment.End) {
                Text("P&L", fontSize = 11.sp, color = TextSecondary)
                Text("${fmtSignedUsd(item.pnl)} USD", fontWeight = FontWeight.ExtraBold, color = pnlColor, fontSize = 15.sp)
                Text(fmtPct(item.pnlPercent), color = pnlColor, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconInfo(Icons.Rounded.SmartToy, item.strategy)
            IconInfo(Icons.Rounded.Schedule, item.duration)
            IconInfo(Icons.Rounded.Flag, item.closeReason)
        }
        Spacer(Modifier.height(6.dp))
        Text("Сигнал: ${item.openReason}", fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp)
        if (item.mint.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MintCopyRow(item.mint)
        }
    }
}

/** Адрес контракта (mint) с копированием по нажатию и отметкой «Скопировано». */
@Composable
fun MintCopyRow(mint: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(mint) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2_000); copied = false }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
            clipboard.setText(AnnotatedString(mint))
            copied = true
        }.padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = "Скопировать адрес", tint = if (copied) Green else TextSecondary,
            modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (copied) "Скопировано" else shortMint(mint),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = if (copied) Green else TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Колонка «Вход/Выход»: цена + две метки времени одного момента [ms]:
 *  • DEX  — в UTC, как на сайте/графике DexScreener;
 *  • наше — в локальной таймзоне (реальное время исполнения у нас).
 */
@Composable
fun PriceCol(label: String, value: String, modifier: Modifier = Modifier, ms: Long? = null) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Text(value, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp)
        if (ms != null) {
            Text("DEX ${formatDexTime(ms)}", fontSize = 10.sp, color = TextSecondary)
            Text("наше ${formatLocalTime(ms)}", fontSize = 10.sp, color = TextSecondary)
        }
    }
}

@Composable
fun InfoChip(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(icon, fontSize = 12.sp)
        Text(text, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Подпись с векторной иконкой: стратегия, длительность, причина выхода. */
@Composable
fun IconInfo(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── Форматирование ───────────────────────────────────────────────────────────

/** Сумма по-русски: «10 234,56». Без String.format — одинаково на всех платформах и в любой локали. */
internal fun fmtUsd(v: Double, decimals: Int = 2): String = formatNumberRu(v, decimals, group = true)

/** Сумма со знаком: «+12,40», «−6,00». */
internal fun fmtSignedUsd(v: Double, decimals: Int = 2): String = (if (v > 0.0) "+" else "") + fmtUsd(v, decimals)

/** Процент со знаком: «+12,0%». */
internal fun fmtPct(v: Double, decimals: Int = 1): String = (if (v > 0.0) "+" else "") + formatNumberRu(v, decimals) + "%"

/** Число без лишних нулей: «15», «1,5», «0,25». */
internal fun fmtPlain(v: Double): String = formatNumberRu(v, 2).trimEnd('0').trimEnd(',')

/** Число с запятой и (по желанию) узким пробелом между тысячами; минус — типографский. */
internal fun formatNumberRu(v: Double, decimals: Int, group: Boolean = false): String {
    if (v.isNaN() || v.isInfinite()) return "—"
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(abs(v) * factor).toLong()
    val intPart = (scaled / factor).toString()
    val grouped = if (group) intPart.reversed().chunked(3).joinToString(" ").reversed() else intPart
    val body = if (decimals > 0) "$grouped,${(scaled % factor).toString().padStart(decimals, '0')}" else grouped
    return if (v < 0 && scaled != 0L) "−$body" else body
}
