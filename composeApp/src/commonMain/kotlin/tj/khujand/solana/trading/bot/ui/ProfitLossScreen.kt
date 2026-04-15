package tj.khujand.solana.trading.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.round
import tj.khujand.solana.trading.bot.domain.ProfitLossStatistics
import tj.khujand.solana.trading.bot.domain.TokenHistory
import tj.khujand.solana.trading.bot.domain.TokenHistoryManager

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProfitLossScreen(onClose: () -> Unit, refreshKey: Int = 0) {
    var history    by remember { mutableStateOf(TokenHistoryManager.loadHistory()) }
    var statistics by remember { mutableStateOf(TokenHistoryManager.getStatistics()) }
    var showClearDialog  by remember { mutableStateOf(false) }
    var showHistoryScreen by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        history    = TokenHistoryManager.loadHistory()
        statistics = TokenHistoryManager.getStatistics()
    }

    if (showHistoryScreen) {
        HistoryListScreen(history = history, onBack = { showHistoryScreen = false })
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Gradient header ──────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(BrandPurple, Color(0xFF6366F1))),
                            RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                        )
                        .padding(top = 52.dp, start = 20.dp, end = 16.dp, bottom = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Прибыль и убытки",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                journalRecordsSubtitle(history.size),
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (history.isNotEmpty()) {
                                IconButton(onClick = { showClearDialog = true }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Очистить историю",
                                        tint = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            IconButton(onClick = onClose) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                            }
                        }
                    }
                }

                // ── Content ──────────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        if (history.isNotEmpty()) {
                            PnLMainSummary(statistics, onOpenAllTrades = { showHistoryScreen = true })
                        } else {
                            EmptyHistoryState()
                        }
                    }
                }
            }

            // ── Clear dialog ─────────────────────────────────────────────
            if (showClearDialog) {
                AlertDialog(
                    onDismissRequest = { showClearDialog = false },
                    shape = RoundedCornerShape(20.dp),
                    icon = {
                        Icon(Icons.Default.DeleteForever, contentDescription = null,
                            tint = DangerRed, modifier = Modifier.size(32.dp))
                    },
                    title = { Text("Очистить историю?", fontWeight = FontWeight.Bold) },
                    text  = { Text("Вся история сделок будет удалена безвозвратно.", color = TextSecondary) },
                    confirmButton = {
                        Button(
                            onClick = {
                                TokenHistoryManager.clearHistory()
                                history    = emptyList()
                                statistics = TokenHistoryManager.getStatistics()
                                showClearDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                        ) { Text("Очистить", color = Color.White) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDialog = false }) { Text("Отмена") }
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// History list sub-screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryListScreen(history: List<TokenHistory>, onBack: () -> Unit) {
    var filterMode by remember { mutableStateOf("all") }
    val filteredHistory = when (filterMode) {
        "demo" -> history.filter { !it.isRealTrade }
        "real" -> history.filter { it.isRealTrade }
        else   -> history
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(BrandPurple, Color(0xFF6366F1))),
                        RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    )
                    .padding(top = 52.dp, start = 20.dp, end = 16.dp, bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Сделки", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(recordsSubtitle(filteredHistory.size), fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f))
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }
                }
            }

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("all" to "Все", "demo" to "Демо", "real" to "Реал").forEach { (mode, label) ->
                    FilterChip(
                        selected = filterMode == mode,
                        onClick  = { filterMode = mode },
                        label    = { Text(label, fontSize = 13.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandPurple,
                            selectedLabelColor     = Color.White
                        )
                    )
                }
            }

            if (filteredHistory.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredHistory.sortedByDescending { it.exitTime }) { HistoryItemCard(it) }
                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }
            } else {
                EmptyHistoryState()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Summary (главный экран — без «сетки из 20 карточек»)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PnLMainSummary(statistics: ProfitLossStatistics, onOpenAllTrades: () -> Unit) {
    var detailsOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HeroSummaryCard(statistics)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("По режимам", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Savings, null, modifier = Modifier.size(18.dp), tint = BrandIndigo)
                        Text("Демо", fontSize = 14.sp, color = TextPrimary)
                    }
                    Text(
                        "${formatCurrency(statistics.demoNetProfit)} · ${statistics.demoReturnPct.toInt()}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
                HorizontalDivider(color = NeutralBorder.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AttachMoney, null, modifier = Modifier.size(18.dp), tint = BrandTeal)
                        Text("Реал", fontSize = 14.sp, color = TextPrimary)
                    }
                    Text(
                        "${formatCurrency(statistics.realNetProfit)} · ${statistics.realReturnPct.toInt()}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { detailsOpen = !detailsOpen }
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (detailsOpen) "Скрыть подробности" else "Подробная статистика",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Icon(
                if (detailsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = BrandPurple
            )
        }

        AnimatedVisibility(
            visible = detailsOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            DetailedStatsList(statistics)
        }

        Button(
            onClick = onOpenAllTrades,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandPurple,
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Открыть список сделок", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HeroSummaryCard(statistics: ProfitLossStatistics) {
    val totalColor = if (statistics.overallNetProfit >= 0) SuccessGreen else DangerRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Итог по счёту", fontSize = 13.sp, color = TextSecondary)
            Text(
                formatCurrency(statistics.overallNetProfit),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = totalColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Доходность ${statistics.returnPct.toInt()}% · винрейт ${statistics.winRate.toInt()}% · сделок ${statistics.totalTrades}",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )
            HorizontalDivider(color = NeutralBorder.copy(alpha = 0.7f))
            SummaryMoneyRow("Вложено", formatUsdPlain(statistics.totalInvested))
            SummaryMoneyRow("Вернулись (USD)", formatUsdPlain(statistics.totalReturn))
            if (statistics.partialExitsCount > 0) {
                Text(
                    "Есть частичные выходы: ${statistics.partialExitsCount}. Полный разбор — в блоке ниже.",
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun SummaryMoneyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = TextSecondary)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}

@Composable
private fun DetailedStatsList(statistics: ProfitLossStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DetailLine("Закрытые сделки (P&L)", formatCurrency(statistics.netProfit))
            if (statistics.partialExitsCount > 0) {
                DetailLine("Частичные выходы, шт.", "${statistics.partialExitsCount}")
                DetailLine("Сумма по частичным", formatCurrency(statistics.partialNetProfit))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = NeutralBorder.copy(alpha = 0.6f))
            DetailLine("Полностью закрыто частичными выходами", "${statistics.tpTriggerCount}")
            DetailLine("Стоп-лоссов (включая частичные убытки)", "${statistics.slCount}")
            DetailLine("Прибыльных закрытий", "${statistics.profitableCloseCount}")
            val realOk = if (statistics.realTrades > 0) "${statistics.realSuccessCount} из ${statistics.realTrades}" else "—"
            DetailLine("Реал: успешных свапов", realOk)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 2,
            textAlign = TextAlign.End
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyHistoryState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = CircleShape, color = BrandPurpleLight, modifier = Modifier.size(80.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Assessment, contentDescription = null,
                        modifier = Modifier.size(40.dp), tint = BrandPurple)
                }
            }
            Text("Пока нет истории сделок", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(
                "Токены, закрытые по TP или SL, появятся здесь",
                fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// History item card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryItemCard(item: TokenHistory) {
    val clipboardManager  = LocalClipboardManager.current
    var showCopiedMessage by remember { mutableStateOf(false) }
    var showFullAddress   by remember { mutableStateOf(false) }
    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) { delay(2000); showCopiedMessage = false }
    }

    // Полные закрытия: TP/SL в status; частичные выходы всегда были с TP в данных —
    // итог по сделке смотрим по profitUsd, чтобы убытки не подсвечивались как прибыль.
    val isProfit     = item.profitUsd >= 0.0
    val accentColor  = if (isProfit) SuccessGreen else DangerRed
    val cardColor    = if (isProfit) SuccessGreenBg else DangerRedBg
    val tokenAddress = item.tokenPair.baseToken?.address ?: ""
    val modeLabel    =
        if (item.isRealTrade) if (item.isSwapSuccess) "Реал ✓" else "Реал ✗" else "Демо"
    val pctLabel     = if (!item.isPartialExit && item.investedUsd > 0) {
        val exitPct = ((item.exitAmountUsd / item.investedUsd) * 100).toInt()
        " · $exitPct% позиции"
    } else ""

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
            )
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = RoundedCornerShape(6.dp), color = accentColor.copy(alpha = 0.18f)) {
                                Text(
                                    if (isProfit) "ТП" else "СЛ",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                            Text(item.symbol, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            "$modeLabel$pctLabel · ${item.exitDate}",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 2
                        )
                    }
                    Text(
                        formatCurrency(item.profitUsd),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                if (item.isPartialExit && item.note.isNotBlank()) {
                    Text(item.note, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                val priceLine =
                    "\$${formatPrice(item.entryPrice)} → \$${formatPrice(item.exitPrice)}  ·  " +
                        "${if (item.priceChangePercent >= 0) "+" else ""}${formatDecimal(item.priceChangePercent, 2)}%"
                Text(priceLine, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)

                if (tokenAddress.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Адрес", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { clipboardManager.setText(AnnotatedString(tokenAddress)); showCopiedMessage = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (showFullAddress) tokenAddress else formatAddress(tokenAddress),
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = if (showFullAddress) 4 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            IconButton(onClick = { showFullAddress = !showFullAddress }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    if (showFullAddress) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(tokenAddress))
                                    showCopiedMessage = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (showCopiedMessage) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = if (showCopiedMessage) SuccessGreen else BrandIndigo,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    if (showCopiedMessage) {
                        Text(
                            "✓ Скопировано",
                            fontSize = 10.sp,
                            color = SuccessGreen,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatters
// ─────────────────────────────────────────────────────────────────────────────

/** Записей в журнале может быть больше, чем «позиций»: одна сделка = до нескольких строк при частичных выходах. */
private fun journalRecordsSubtitle(count: Int): String {
    val n = count % 100
    val n1 = count % 10
    val word = when {
        n in 11..14 -> "записей"
        n1 == 1 -> "запись"
        n1 in 2..4 -> "записи"
        else -> "записей"
    }
    return "В журнале: $count $word (в т.ч. частичные выходы)"
}

private fun recordsSubtitle(count: Int): String {
    val n = count % 100
    val n1 = count % 10
    val word = when {
        n in 11..14 -> "записей"
        n1 == 1 -> "запись"
        n1 in 2..4 -> "записи"
        else -> "записей"
    }
    return "$count $word"
}

private fun formatCurrency(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return "$sign\$${formatDecimal(value, 2)}"
}

private fun formatUsdPlain(value: Double): String = "\$${formatDecimal(value, 2)}"

private fun formatPrice(value: Double): String = when {
    value >= 1      -> formatDecimal(value, 4)
    value >= 0.0001 -> formatDecimal(value, 6)
    else            -> formatDecimal(value, 8)
}.trimEnd('0').trimEnd('.')

private fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    val rounded = round(value * factor) / factor
    val str = rounded.toString()
    val parts = str.split('.')
    return if (parts.size == 1) "$str.${"0".repeat(decimals)}"
    else "${parts[0]}.${parts[1].padEnd(decimals, '0').take(decimals)}"
}

private fun formatAddress(address: String): String {
    if (address.isBlank()) return "—"
    if (address.length <= 10) return address
    return "${address.take(6)}…${address.takeLast(4)}"
}
