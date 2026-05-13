package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tj.khujand.solana.trading.bot.domain.EquityManager
import tj.khujand.solana.trading.bot.domain.EquitySnapshot
import tj.khujand.solana.trading.bot.domain.ProfitLossStatistics
import tj.khujand.solana.trading.bot.domain.TokenHistory
import tj.khujand.solana.trading.bot.domain.TokenHistoryManager
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.util.formatDemoBalance
import tj.khujand.solana.trading.bot.util.formatLargeNumber
import tj.khujand.solana.trading.bot.util.formatNumber

@Composable
fun AnalyticsScreen() {
    val history     = remember { TokenHistoryManager.loadHistory() }
    val stats       = remember { TokenHistoryManager.getStatistics() }
    val equity      = remember { EquityManager.loadSnapshots() }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Аналитика", style = MaterialTheme.typography.headlineMedium, color = TextOnDark)

        // Overall stats
        OverallStatsCard(stats)

        // Win rate bar
        WinRateCard(stats)

        // Period breakdown
        PeriodBreakdownCard(history)

        // Best / worst trades
        TopTradesCard(history)

        // Demo vs Real split
        DemoRealSplitCard(stats)

        // Equity curve
        if (equity.size >= 2) {
            EquityCurveCard(equity)
        }

        // Тепловая карта по часам
        if (history.isNotEmpty()) {
            HeatmapCard(history)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ─── Overall stats grid ───────────────────────────────────────────────────────

@Composable
private fun OverallStatsCard(stats: ProfitLossStatistics) {
    val netColor = if (stats.overallNetProfit >= 0) SuccessGreen else DangerRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Общая статистика", style = MaterialTheme.typography.titleMedium, color = TextOnDark)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile("Сделки",   stats.totalTrades.toString(),                                    TextOnDark)
                StatTile("Win",      "${formatNumber(stats.winRate, 1)}%",                              SuccessGreen)
                StatTile("TP",       stats.profitableCloseCount.toString(),                             SuccessGreen)
                StatTile("SL",       stats.slCount.toString(),                                         DangerRed)
            }
            HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile("Net PnL",  "${if (stats.overallNetProfit >= 0) "+" else ""}$${formatDemoBalance(stats.overallNetProfit)}", netColor)
                StatTile("Прибыль", "+$${formatDemoBalance(stats.totalProfit)}",                        SuccessGreen)
                StatTile("Убыток",  "$${formatDemoBalance(stats.totalLoss)}",                           DangerRed)
                StatTile("Инвест.", "$${formatLargeNumber(stats.totalInvested)}",                       TextOnDarkMuted)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = valueColor)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextOnDarkMuted)
    }
}

// ─── Win rate visual bar ──────────────────────────────────────────────────────

@Composable
private fun WinRateCard(stats: ProfitLossStatistics) {
    val winPct = (stats.winRate / 100.0).coerceIn(0.0, 1.0).toFloat()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Win Rate", style = MaterialTheme.typography.titleMedium, color = TextOnDark)
                Text(
                    "${formatNumber(stats.winRate, 1)}%",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (stats.winRate >= 50) SuccessGreen else DangerRed,
                )
            }
            LinearProgressIndicator(
                progress    = { winPct },
                modifier    = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color       = SuccessGreen,
                trackColor  = DangerRed.copy(alpha = 0.4f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TP: ${stats.profitableCloseCount}", style = MaterialTheme.typography.labelSmall, color = SuccessGreen)
                Text("SL: ${stats.slCount}", style = MaterialTheme.typography.labelSmall, color = DangerRed)
            }
        }
    }
}

// ─── Period breakdown ─────────────────────────────────────────────────────────

private data class PeriodStats(val label: String, val pnl: Double, val count: Int)

@Composable
private fun PeriodBreakdownCard(history: List<TokenHistory>) {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val dayMs   = 86_400_000L
    val weekMs  = 7 * dayMs
    val monthMs = 30 * dayMs

    fun pnlSince(sinceMs: Long): PeriodStats {
        val label = when (sinceMs) {
            now - dayMs   -> "Сегодня"
            now - weekMs  -> "7 дней"
            else          -> "30 дней"
        }
        val subset = history.filter { it.exitTime >= sinceMs }
        return PeriodStats(label, subset.sumOf { it.profitUsd }, subset.size)
    }

    val periods = listOf(
        pnlSince(now - dayMs),
        pnlSince(now - weekMs),
        pnlSince(now - monthMs),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PnL по периодам", style = MaterialTheme.typography.titleMedium, color = TextOnDark)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                periods.forEach { p ->
                    PeriodTile(p)
                }
            }
        }
    }
}

@Composable
private fun PeriodTile(p: PeriodStats) {
    val color = if (p.pnl >= 0) SuccessGreen else DangerRed
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVar)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(p.label, style = MaterialTheme.typography.labelSmall, color = TextOnDarkMuted)
        Spacer(Modifier.height(4.dp))
        Text(
            "${if (p.pnl >= 0) "+" else ""}$${formatDemoBalance(p.pnl)}",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
        )
        Text("${p.count} сделок", style = MaterialTheme.typography.labelSmall, color = TextOnDarkFaint)
    }
}

// ─── Top / worst trades ───────────────────────────────────────────────────────

@Composable
private fun TopTradesCard(history: List<TokenHistory>) {
    val completed = history.filter { !it.isPartialExit }
    val best  = completed.sortedByDescending { it.profitUsd }.take(3)
    val worst = completed.sortedBy { it.profitUsd }.take(3)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Лучшие и худшие сделки", style = MaterialTheme.typography.titleMedium, color = TextOnDark)

            if (best.isEmpty()) {
                Text("Нет данных", style = MaterialTheme.typography.bodySmall, color = TextOnDarkFaint)
            } else {
                Text("Топ прибыльных", style = MaterialTheme.typography.labelMedium, color = SuccessGreen)
                best.forEach { t -> TradeRow(t) }
                Spacer(Modifier.height(4.dp))
                Text("Топ убыточных", style = MaterialTheme.typography.labelMedium, color = DangerRed)
                worst.forEach { t -> TradeRow(t) }
            }
        }
    }
}

@Composable
private fun TradeRow(t: TokenHistory) {
    val color = if (t.profitUsd >= 0) SuccessGreen else DangerRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(DarkSurfaceVar)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.symbol, style = MaterialTheme.typography.titleSmall, color = TextOnDark, modifier = Modifier.weight(1f))
        Text(t.exitDate, style = MaterialTheme.typography.labelSmall, color = TextOnDarkMuted, modifier = Modifier.weight(1.5f))
        Text(
            "${if (t.profitUsd >= 0) "+" else ""}$${formatDemoBalance(t.profitUsd)}",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

// ─── Demo / Real split card ───────────────────────────────────────────────────

@Composable
private fun DemoRealSplitCard(stats: ProfitLossStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            SplitColumn(
                modifier = Modifier.weight(1f),
                title    = "Demo",
                titleColor = CyanAccent,
                net      = stats.demoNetProfit,
                invested = stats.demoInvested,
                returnPct = stats.demoReturnPct,
            )
            VerticalDivider(modifier = Modifier.height(60.dp).padding(horizontal = 8.dp), color = DarkBorder, thickness = 0.5.dp)
            SplitColumn(
                modifier = Modifier.weight(1f),
                title    = "Real",
                titleColor = WarnAmber,
                net      = stats.realNetProfit,
                invested = stats.realInvested,
                returnPct = stats.realReturnPct,
            )
        }
    }
}

@Composable
private fun SplitColumn(
    modifier: Modifier,
    title: String,
    titleColor: Color,
    net: Double,
    invested: Double,
    returnPct: Double,
) {
    val netColor = if (net >= 0) SuccessGreen else DangerRed
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = titleColor)
        Text(
            "${if (net >= 0) "+" else ""}$${formatDemoBalance(net)}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = netColor,
        )
        Text("Вложено: $${formatLargeNumber(invested)}", style = MaterialTheme.typography.labelSmall, color = TextOnDarkMuted)
        Text(
            "${if (returnPct >= 0) "+" else ""}${formatNumber(returnPct, 1)}%",
            style = MaterialTheme.typography.bodySmall,
            color = netColor.copy(alpha = 0.8f),
        )
    }
}

// ─── Equity Curve ─────────────────────────────────────────────────────────────

@Composable
private fun EquityCurveCard(snapshots: List<EquitySnapshot>) {
    val minVal = snapshots.minOf { it.balanceUsd }
    val maxVal = snapshots.maxOf { it.balanceUsd }
    val range  = (maxVal - minVal).coerceAtLeast(1.0)
    val lineColor = if (snapshots.last().balanceUsd >= snapshots.first().balanceUsd) SuccessGreen else DangerRed

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Equity Curve", style = MaterialTheme.typography.titleMedium, color = TextOnDark)
                Text(
                    "$${formatDemoBalance(snapshots.last().balanceUsd)}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = lineColor,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurfaceVar)
            ) {
                val w = size.width
                val h = size.height
                val pts = snapshots.mapIndexed { i, s ->
                    Offset(
                        x = i.toFloat() / (snapshots.size - 1) * w,
                        y = h - ((s.balanceUsd - minVal) / range * h).toFloat().coerceIn(2f, h - 2f),
                    )
                }
                // Fill area under curve
                val fillPath = Path().apply {
                    moveTo(pts.first().x, h)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, h)
                    close()
                }
                drawPath(
                    fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.3f), Color.Transparent),
                        startY = 0f, endY = h,
                    ),
                )
                // Line
                for (i in 0 until pts.size - 1) {
                    drawLine(
                        color     = lineColor,
                        start     = pts[i],
                        end       = pts[i + 1],
                        strokeWidth = 2f,
                        cap       = StrokeCap.Round,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Min: $${formatDemoBalance(minVal)}", style = MaterialTheme.typography.labelSmall, color = TextOnDarkFaint)
                Text("Max: $${formatDemoBalance(maxVal)}", style = MaterialTheme.typography.labelSmall, color = TextOnDarkFaint)
            }
        }
    }
}

// ─── Тепловая карта по часам суток ────────────────────────────────────────────

@Composable
private fun HeatmapCard(history: List<TokenHistory>) {
    // Группируем по часу выхода из сделки
    val byHour = Array(24) { mutableListOf<Double>() }
    history.forEach { t ->
        val hour = ((t.exitTime / 3_600_000L) % 24).toInt().coerceIn(0, 23)
        byHour[hour].add(t.profitUsd)
    }
    val avgPnl = byHour.map { list -> if (list.isEmpty()) 0.0 else list.average() }
    val maxAbs = avgPnl.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(0.01) ?: 0.01

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Тепловая карта (UTC час)", style = MaterialTheme.typography.titleMedium, color = TextOnDark)
            Text("Средний PnL по часу закрытия сделки", style = MaterialTheme.typography.bodySmall, color = TextOnDarkMuted)
            // 4 строки × 6 часов
            for (row in 0 until 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (col in 0 until 6) {
                        val hour = row * 6 + col
                        val pnl  = avgPnl[hour]
                        val count = byHour[hour].size
                        val intensity = (kotlin.math.abs(pnl) / maxAbs).toFloat().coerceIn(0f, 1f)
                        val cellColor = when {
                            count == 0     -> DarkSurfaceVar
                            pnl > 0        -> SuccessGreen.copy(alpha = 0.15f + intensity * 0.6f)
                            else           -> DangerRed.copy(alpha = 0.15f + intensity * 0.6f)
                        }
                        val textColor = when {
                            count == 0 -> TextOnDarkFaint
                            pnl > 0    -> SuccessGreen
                            else       -> DangerRed
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(cellColor)
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("${hour}h", style = MaterialTheme.typography.labelSmall, color = TextOnDarkFaint, fontSize = 8.sp)
                            Text(
                                if (count == 0) "—" else "${if (pnl >= 0) "+" else ""}${formatNumber(pnl, 1)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                                color = textColor,
                            )
                        }
                    }
                }
            }
            Text(
                "Зелёный = прибыльный час · Красный = убыточный час · Интенсивность = сила сигнала",
                style = MaterialTheme.typography.labelSmall,
                color = TextOnDarkFaint,
            )
        }
    }
}
