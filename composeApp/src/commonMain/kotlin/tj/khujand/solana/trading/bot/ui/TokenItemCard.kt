package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.util.formatLargeNumber
import tj.khujand.solana.trading.bot.util.formatNumber
import tj.khujand.solana.trading.bot.util.formatSimpleNumber
import kotlin.time.Clock

@Composable
fun TokenItemCard(
    token: MonitoredToken,
    onCloseToken: ((String, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clipboardManager   = LocalClipboardManager.current
    var showCopiedMessage  by remember { mutableStateOf(false) }
    var showFullAddress    by remember { mutableStateOf(false) }

    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) { delay(2000); showCopiedMessage = false }
    }

    val isProfit = token.profitUsd >= 0
    val isLive   = token.status == TokenStatus.MONITORING

    // Card color logic
    val cardColor = when (token.status) {
        TokenStatus.STOPPED_TP -> SuccessGreenBg
        TokenStatus.STOPPED_SL -> DangerRedBg
        else -> MaterialTheme.colorScheme.surface
    }
    val accentColor = when (token.status) {
        TokenStatus.STOPPED_TP -> SuccessGreen
        TokenStatus.STOPPED_SL -> DangerRed
        else -> BrandIndigo
    }
    val leftBarColor = when (token.status) {
        TokenStatus.STOPPED_TP -> SuccessGreen
        TokenStatus.STOPPED_SL -> DangerRed
        else -> if (isProfit) SuccessGreen else DangerRed
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(leftBarColor, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            )

            Column(modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {

                // ── Row 1: Symbol + Status badge ──────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                token.tokenPair.baseToken?.symbol?.take(2) ?: "??",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                        Column {
                            Text(
                                token.tokenPair.baseToken?.symbol ?: "Unknown",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                token.tokenPair.baseToken?.name ?: "Unknown Token",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Status badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = accentColor.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                            Text(
                                when (token.status) {
                                    TokenStatus.STOPPED_TP -> "TP HIT"
                                    TokenStatus.STOPPED_SL -> "SL HIT"
                                    else -> "LIVE"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                if (isLive && !token.demoBuyApplied && token.jupiterSellLastError.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Jupiter: ${token.jupiterSellLastError}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Token Address ──────────────────────────────────────────
                Text(
                    "Token Address",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted,
                    letterSpacing = 0.3.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            token.tokenPair.baseToken?.address?.let {
                                clipboardManager.setText(AnnotatedString(it))
                                showCopiedMessage = true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (showFullAddress) token.tokenPair.baseToken?.address ?: "N/A"
                        else formatAddress(token.tokenPair.baseToken?.address),
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { showFullAddress = !showFullAddress }, modifier = Modifier.size(22.dp)) {
                            Icon(
                                if (showFullAddress) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                token.tokenPair.baseToken?.address?.let {
                                    clipboardManager.setText(AnnotatedString(it))
                                    showCopiedMessage = true
                                }
                            },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                if (showCopiedMessage) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (showCopiedMessage) SuccessGreen else BrandIndigo,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                if (showCopiedMessage) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "✓ Copied to clipboard",
                        fontSize = 10.sp,
                        color = SuccessGreen,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Price row ──────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text("Price", fontSize = 11.sp, color = TextMuted)
                        Text(
                            "\$${formatPrice(token.currentPrice.toDoubleOrNull() ?: 0.0)}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        val stagesDone = listOf(token.exitStage1Done, token.exitStage2Done, token.exitStage3Done, token.exitStage4Done).count { it }
                        Text("Stages $stagesDone/4", fontSize = 11.sp, color = TextSecondary)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("P&L", fontSize = 11.sp, color = TextMuted)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (token.priceChangePercent >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isProfit) SuccessGreen else DangerRed
                            )
                            Text(
                                "${if (token.profitUsd >= 0) "+" else ""}\$${formatNumber(token.profitUsd)}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isProfit) SuccessGreen else DangerRed
                            )
                        }
                    }
                }

                // ── Demo balance row ───────────────────────────────────────
                if (token.demoBuyApplied) {
                    val investment = DemoAccountManager.DEMO_TRADE_AMOUNT
                    val nowValue = investment + token.profitUsd
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Entry  \$${formatNumber(investment)}", fontSize = 12.sp, color = TextSecondary)
                            Text(
                                "Now  \$${formatNumber(nowValue)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (nowValue >= investment) SuccessGreenDark else DangerRedDark
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Stats row (3 columns) ──────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatChip(
                        icon  = Icons.Default.WaterDrop,
                        label = "Liquidity",
                        value = "\$${formatLargeNumber(token.tokenPair.liquidity?.usd ?: 0.0)}",
                        color = BrandTeal,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        icon  = Icons.Default.BarChart,
                        label = "Market Cap",
                        value = "\$${formatLargeNumber(token.tokenPair.marketCap ?: 0.0)}",
                        color = BrandIndigo,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        icon  = Icons.Default.Link,
                        label = "Chain",
                        value = token.tokenPair.chainId?.uppercase()?.take(3) ?: "N/A",
                        color = BrandPurple,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Time row ──────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(13.dp), tint = TextMuted)
                        Text(formatTimeAgo(token.foundTime), fontSize = 11.sp, color = TextSecondary)
                    }
                    val createdAt = token.tokenPair.pairCreatedAt ?: token.ageToken.toLongOrNull()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Cake, contentDescription = null, modifier = Modifier.size(13.dp), tint = TextMuted)
                        Text(
                            if (createdAt != null) "Age: ${formatTimeAgo(createdAt)}" else "Age: N/A",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                // ── Close button (live tokens only) ────────────────────────
                if (isLive && onCloseToken != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val profit = token.profitUsd >= 0
                            val closeKey = token.tokenPair.pairAddress?.takeIf { it.isNotBlank() }
                                ?: token.tokenPair.baseToken?.address?.takeIf { it.isNotBlank() }
                            if (closeKey != null) {
                                onCloseToken(closeKey, profit)
                            } else {
                                println("⚠️ Close: нет pairAddress и base mint — закрытие невозможно")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = if (isProfit) SuccessGreen else DangerRed,
                            contentColor   = Color.White
                        )
                    ) {
                        Icon(
                            if (isProfit) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (isProfit) "Close Profit" else "Close Loss",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "(${if (token.profitUsd >= 0) "+" else ""}\$${formatNumber(token.profitUsd)})",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ─── Stat Chip ────────────────────────────────────────────────────────────────

@Composable
private fun StatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(10.dp),
        color    = color.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            Text(label, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─── Formatters ───────────────────────────────────────────────────────────────

private fun formatAddress(address: String?): String {
    if (address.isNullOrBlank()) return "N/A"
    if (address.length <= 10) return address
    return "${address.take(6)}…${address.takeLast(4)}"
}

private fun formatPrice(price: Double): String {
    if (price <= 0) return "0"
    val decimals = when {
        price >= 1000   -> 2
        price >= 1      -> 4
        price >= 0.0001 -> 6
        price >= 0.000001 -> 8
        else            -> 10
    }
    return formatNumber(price, decimals)
}

private fun formatTimeAgo(timestamp: Long): String {
    val now     = Clock.System.now().toEpochMilliseconds()
    val diff    = now - timestamp
    val minutes = diff / (1000 * 60)
    val hours   = diff / (1000 * 60 * 60)
    val days    = diff / (1000 * 60 * 60 * 24)
    return when {
        minutes < 1  -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24   -> "$hours hr ago"
        days < 7     -> "$days days ago"
        else         -> "${days / 7} wk ago"
    }
}
