package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tj.khujand.solana.trading.bot.domain.TokenHistory
import tj.khujand.solana.trading.bot.domain.TokenHistoryManager
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.util.exportTradesToCsv
import tj.khujand.solana.trading.bot.util.formatDemoBalance
import tj.khujand.solana.trading.bot.util.formatNumber

private enum class HistoryFilter(val label: String) {
    ALL   ("Все"),
    TP    ("TP"),
    SL    ("SL"),
    DEMO  ("Demo"),
    REAL  ("Real"),
}

@Composable
fun HistoryScreen() {
    var history        by remember { mutableStateOf(TokenHistoryManager.loadHistory()) }
    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var exportMessage  by remember { mutableStateOf<String?>(null) }
    var showClearDlg   by remember { mutableStateOf(false) }

    val filtered = remember(history, selectedFilter) {
        when (selectedFilter) {
            HistoryFilter.ALL  -> history
            HistoryFilter.TP   -> history.filter { it.status == TokenStatus.STOPPED_TP }
            HistoryFilter.SL   -> history.filter { it.status == TokenStatus.STOPPED_SL }
            HistoryFilter.DEMO -> history.filter { !it.isRealTrade }
            HistoryFilter.REAL -> history.filter { it.isRealTrade }
        }.sortedByDescending { it.exitTime }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("История", style = MaterialTheme.typography.headlineMedium, color = TextOnDark)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // CSV Export
                IconButton(onClick = {
                    exportMessage = try {
                        exportTradesToCsv(history)
                    } catch (e: Exception) {
                        "Ошибка экспорта: ${e.message}"
                    }
                }) {
                    Icon(Icons.Default.Download, contentDescription = "Экспорт CSV", tint = CyanAccent)
                }
                // Clear history
                IconButton(onClick = { showClearDlg = true }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Очистить", tint = DangerRed.copy(alpha = 0.7f))
                }
            }
        }

        // Export feedback
        exportMessage?.let { msg ->
            Spacer(Modifier.height(4.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = CyanAccent, maxLines = 2)
        }

        Spacer(Modifier.height(10.dp))

        // Filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { filter ->
                val selected = selectedFilter == filter
                FilterChip(
                    selected = selected,
                    onClick  = { selectedFilter = filter },
                    label    = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor     = CyanAccentBg,
                        selectedLabelColor         = CyanAccent,
                        selectedLeadingIconColor   = CyanAccent,
                        containerColor             = DarkSurface,
                        labelColor                 = TextOnDarkMuted,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled           = true,
                        selected          = selected,
                        selectedBorderColor = CyanAccent.copy(alpha = 0.4f),
                        borderColor       = DarkBorder,
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Summary row
        val totalPnl = filtered.sumOf { it.profitUsd }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${filtered.size} сделок", style = MaterialTheme.typography.labelMedium, color = TextOnDarkMuted)
            Text(
                "${if (totalPnl >= 0) "+" else ""}$${formatDemoBalance(totalPnl)}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (totalPnl >= 0) SuccessGreen else DangerRed,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, modifier = Modifier.size(48.dp), tint = TextOnDarkFaint)
                    Spacer(Modifier.height(8.dp))
                    Text("Нет записей", style = MaterialTheme.typography.bodyMedium, color = TextOnDarkMuted)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { "${it.entryTime}_${it.symbol}_${it.exitTime}" }) { item ->
                    HistoryItemCard(item)
                }
            }
        }
    }

    if (showClearDlg) {
        AlertDialog(
            onDismissRequest = { showClearDlg = false },
            containerColor   = DarkSurface,
            title = { Text("Очистить историю?", color = TextOnDark) },
            text  = { Text("Все записи будут удалены безвозвратно.", color = TextOnDarkMuted) },
            confirmButton = {
                TextButton(onClick = {
                    TokenHistoryManager.clearHistory()
                    history = emptyList()
                    showClearDlg = false
                }) { Text("Удалить", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDlg = false }) { Text("Отмена", color = TextOnDarkMuted) }
            }
        )
    }
}

@Composable
private fun HistoryItemCard(item: TokenHistory) {
    val clipboard = LocalClipboardManager.current
    val isProfit  = item.profitUsd >= 0
    val pnlColor  = if (isProfit) SuccessGreen else DangerRed
    val statusBg  = if (item.status == TokenStatus.STOPPED_TP) SuccessGreenBg else DangerRedBg
    val statusLabel = when {
        item.isPartialExit             -> "PARTIAL"
        item.status == TokenStatus.STOPPED_TP -> "TP"
        else                           -> "SL"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: symbol + date + type badge
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.symbol,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextOnDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusBadge(statusLabel, if (isProfit) SuccessGreen else DangerRed, if (isProfit) SuccessGreenBg else DangerRedBg)
                    if (item.isRealTrade) {
                        StatusBadge("REAL", CyanAccent, CyanAccentBg)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${item.entryDate}  →  ${item.exitDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextOnDarkMuted,
                )
                if (item.note.isNotBlank()) {
                    Text(item.note, style = MaterialTheme.typography.labelSmall, color = TextOnDarkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Right: PnL
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (item.profitUsd >= 0) "+" else ""}$${formatDemoBalance(item.profitUsd)}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = pnlColor,
                )
                Text(
                    "${if (item.priceChangePercent >= 0) "+" else ""}${formatNumber(item.priceChangePercent, 1)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = pnlColor.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, textColor: Color, bgColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = textColor)
    }
}
