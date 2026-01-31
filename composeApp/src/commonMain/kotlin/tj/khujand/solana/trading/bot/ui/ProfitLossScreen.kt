package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.round
import tj.khujand.solana.trading.bot.domain.ProfitLossStatistics
import tj.khujand.solana.trading.bot.domain.TokenHistory
import tj.khujand.solana.trading.bot.domain.TokenHistoryManager
import tj.khujand.solana.trading.bot.domain.TokenStatus

@Composable
fun ProfitLossScreen(
    onClose: () -> Unit
) {
    var history by remember { mutableStateOf(TokenHistoryManager.loadHistory()) }
    var statistics by remember { mutableStateOf(TokenHistoryManager.getStatistics()) }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        history = TokenHistoryManager.loadHistory()
        statistics = TokenHistoryManager.getStatistics()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Заголовок
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 55.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "📊 Profit & Loss",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "История торговли",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = history.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear history",
                            tint = if (history.isNotEmpty()) MaterialTheme.colorScheme.error else Color.Gray
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }
        }

        // Статистика
        if (statistics.totalTrades > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Общий профит
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Net Profit",
                    value = formatCurrency(statistics.netProfit),
                    icon = Icons.Default.TrendingUp,
                    color = if (statistics.netProfit >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                )

                // Win Rate
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Win Rate",
                    value = "${statistics.winRate.toInt()}%",
                    icon = Icons.Default.Star,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // TP Count
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "TP Hits",
                    value = "${statistics.tpCount}",
                    icon = Icons.Default.CheckCircle,
                    color = Color(0xFF4CAF50)
                )

                // SL Count
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "SL Hits",
                    value = "${statistics.slCount}",
                    icon = Icons.Default.Cancel,
                    color = Color(0xFFF44336)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        } else {
            // Пустое состояние
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Assessment,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        "Нет истории торговли",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Токены с TP/SL будут отображаться здесь",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Список истории
        if (history.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history.sortedByDescending { it.exitTime }) { item ->
                    HistoryItemCard(item)
                }
            }
        }

        // Диалог очистки
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Очистить историю?") },
                text = { Text("Все данные о прибылях и убытках будут удалены. Это действие нельзя отменить.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            TokenHistoryManager.clearHistory()
                            history = emptyList()
                            statistics = TokenHistoryManager.getStatistics()
                            showClearDialog = false
                        }
                    ) {
                        Text("Очистить", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = color
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryItemCard(item: TokenHistory) {
    val clipboardManager = LocalClipboardManager.current
    var showCopiedMessage by remember { mutableStateOf(false) }
    var showFullAddress by remember { mutableStateOf(false) }
    
    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            delay(2000)
            showCopiedMessage = false
        }
    }
    
    val isProfit = item.status == TokenStatus.STOPPED_TP
    val cardColor = if (isProfit) Color(0xFF1B5E20).copy(alpha = 0.1f) else Color(0xFFB71C1C).copy(alpha = 0.1f)
    val borderColor = if (isProfit) Color(0xFF4CAF50) else Color(0xFFF44336)
    
    val tokenAddress = item.tokenPair.baseToken?.address ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardColor),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Badge(
                        containerColor = if (isProfit) Color(0xFF4CAF50) else Color(0xFFF44336),
                        contentColor = Color.White
                    ) {
                        Text(
                            if (isProfit) "TP HIT" else "SL HIT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        item.symbol,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    formatCurrency(item.profitUsd),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isProfit) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Token Address с копированием
            if (tokenAddress.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Token Address:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .clickable {
                                clipboardManager.setText(AnnotatedString(tokenAddress))
                                showCopiedMessage = true
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (showFullAddress) tokenAddress
                            else formatAddress(tokenAddress),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    showFullAddress = !showFullAddress
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    if (showFullAddress) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showFullAddress) "Hide address" else "Show full address",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(tokenAddress))
                                    showCopiedMessage = true
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                if (showCopiedMessage) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Copied",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy token address",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (showCopiedMessage) {
                    Text(
                        "✓ Address copied to clipboard",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Детали
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Entry Price",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$${formatPrice(item.entryPrice)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        "Exit Price",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$${formatPrice(item.exitPrice)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Изменение цены
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (isProfit) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isProfit) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                    Text(
                        "${if (item.priceChangePercent >= 0) "+" else ""}${formatDecimal(item.priceChangePercent, 2)}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isProfit) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                }

                Text(
                    item.exitDate,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatCurrency(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    val formatted = formatDecimal(value, 2)
    return "$sign$$formatted"
}

private fun formatPrice(value: Double): String {
    return when {
        value >= 1 -> formatDecimal(value, 4)
        value >= 0.0001 -> formatDecimal(value, 6)
        else -> formatDecimal(value, 8)
    }.trimEnd('0').trimEnd('.')
}

private fun formatDecimal(value: Double, decimals: Int): String {
    // Вычисляем factor без pow для совместимости
    var factor = 1.0
    repeat(decimals) {
        factor *= 10.0
    }
    val rounded = round(value * factor) / factor
    val str = rounded.toString()
    
    // Добавляем нули если нужно
    val parts = str.split('.')
    return if (parts.size == 1) {
        "$str.${"0".repeat(decimals)}"
    } else {
        val decimalPart = parts[1].padEnd(decimals, '0').take(decimals)
        "${parts[0]}.$decimalPart"
    }
}

/**
 * Форматирование адреса (первые 6 и последние 4 символа)
 */
private fun formatAddress(address: String): String {
    if (address.isBlank()) return "N/A"
    if (address.length <= 10) return address
    return "${address.take(6)}...${address.takeLast(4)}"
}
