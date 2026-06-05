package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.util.formatDemoBalance
import tj.khujand.solana.trading.bot.util.formatLargeNumber
import tj.khujand.solana.trading.bot.util.formatNumber

@Composable
fun PortfolioScreen() {
    var tokens by remember { mutableStateOf(emptyList<MonitoredToken>()) }
    var demoBalance by remember { mutableStateOf(DemoAccountManager.getBalance()) }
    var showPanicDialog by remember { mutableStateOf(false) }
    var panicMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun aggregateOpenTokens(): List<MonitoredToken> =
        TradingRuntime.activeMonitors()
            .flatMap { it.monitoredTokens }
            .filter { it.status == TokenStatus.MONITORING }

    // Routes a manual close to the monitor that actually owns the token.
    fun closeToken(token: MonitoredToken) {
        val addr = token.tokenPair.baseToken?.address ?: token.tokenPair.pairAddress
        val owner = TradingRuntime.activeMonitors().firstOrNull { m ->
            m.monitoredTokens.any { (it.tokenPair.baseToken?.address ?: it.tokenPair.pairAddress) == addr }
        } ?: TradingRuntime.tokenMonitor()
        owner.manualClose(token)
    }

    LaunchedEffect(Unit) {
        while (true) {
            tokens = aggregateOpenTokens()
            demoBalance = DemoAccountManager.getBalance()
            delay(3_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Портфель", style = MaterialTheme.typography.headlineMedium, color = TextOnDark)
            if (tokens.isNotEmpty()) {
                Button(
                    onClick = { showPanicDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PANIC", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        panicMessage?.let { msg ->
            Spacer(Modifier.height(4.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = DangerRed)
        }

        // Balance header
        PortfolioBalanceCard(demoBalance = demoBalance, tokens = tokens)
        Spacer(Modifier.height(12.dp))

        if (showPanicDialog) {
            PanicSellDialog(
                tokenCount = tokens.size,
                onConfirm = {
                    showPanicDialog = false
                    scope.launch {
                        TradingRuntime.activeMonitors().forEach { it.clearAllTokens() }
                        tokens = emptyList()
                        demoBalance = DemoAccountManager.getBalance()
                        panicMessage = "🚨 Все позиции закрыты"
                    }
                },
                onDismiss = { showPanicDialog = false },
            )
        }

        if (tokens.isEmpty()) {
            EmptyPortfolioPlaceholder()
        } else {
            Text(
                "Открытые позиции (${tokens.size})",
                style = MaterialTheme.typography.titleSmall,
                color = TextOnDarkMuted,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tokens, key = { it.tokenPair.pairAddress ?: it.tokenPair.baseToken?.address ?: "" }) { token ->
                    PortfolioTokenCard(token = token, onCloseRequest = {
                        closeToken(token)
                    })
                }
            }
        }
    }
}

@Composable
private fun PortfolioBalanceCard(demoBalance: Double, tokens: List<MonitoredToken>) {
    val totalUnrealised = tokens.sumOf { it.profitUsd }
    val totalInvested   = tokens.sumOf { it.investedUsd }
    val pnlColor = when {
        totalUnrealised > 0  -> SuccessGreen
        totalUnrealised < 0  -> DangerRed
        else                 -> TextOnDarkMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Demo баланс", style = MaterialTheme.typography.labelMedium, color = TextOnDarkMuted)
                Text(
                    "$${formatDemoBalance(demoBalance)}",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = CyanAccent,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Нереализованный PnL", style = MaterialTheme.typography.labelMedium, color = TextOnDarkMuted)
                Text(
                    "${if (totalUnrealised >= 0) "+" else ""}$${formatDemoBalance(totalUnrealised)}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = pnlColor,
                )
                if (totalInvested > 0) {
                    Text(
                        "Вложено: $${formatLargeNumber(totalInvested)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextOnDarkFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioTokenCard(token: MonitoredToken, onCloseRequest: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var showCloseConfirm by remember { mutableStateOf(false) }

    val pnl = token.profitUsd
    val pct = token.priceChangePercent
    val pnlColor = if (pnl >= 0) SuccessGreen else DangerRed
    val symbol  = token.tokenPair.baseToken?.symbol ?: "???"
    val address = token.tokenPair.baseToken?.address ?: token.tokenPair.pairAddress ?: ""
    val mcap    = token.lastMarketCap.takeIf { it > 0 } ?: token.entryMarketCap

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // Symbol + address
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        symbol,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextOnDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (address.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                "${address.take(6)}…${address.takeLast(4)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextOnDarkMuted,
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(address)) },
                                modifier = Modifier.size(18.dp).padding(start = 2.dp),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(12.dp), tint = TextOnDarkFaint)
                            }
                        }
                    }
                }
                // PnL badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (pnl >= 0) SuccessGreenBg else DangerRedBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${if (pnl >= 0) "+" else ""}$${formatDemoBalance(pnl)}",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = pnlColor,
                        )
                        Text(
                            "${if (pct >= 0) "+" else ""}${formatNumber(pct, 1)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = pnlColor,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(10.dp))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricCell("Вход",    "$${formatNumber(token.entryPrice, 8)}")
                MetricCell("Текущая", "$${formatNumber(token.currentPrice.toDoubleOrNull() ?: 0.0, 8)}")
                MetricCell("Mcap",    formatLargeNumber(mcap))
                MetricCell("Вложено", "$${formatLargeNumber(token.investedUsd)}")
            }

            // Stage progress
            if (token.exitStage1Done || token.exitStage2Done || token.exitStage3Done) {
                Spacer(Modifier.height(8.dp))
                StageProgressRow(token)
            }

            // Jupiter error
            if (token.jupiterSellLastError.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠ ${token.jupiterSellLastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnAmber,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            // Manual close button
            OutlinedButton(
                onClick = { showCloseConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Закрыть вручную", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            containerColor   = DarkSurface,
            title = { Text("Закрыть позицию?", color = TextOnDark) },
            text  = { Text("Токен $symbol будет принудительно закрыт по текущей цене.", color = TextOnDarkMuted) },
            confirmButton = {
                TextButton(onClick = { onCloseRequest(); showCloseConfirm = false }) {
                    Text("Закрыть", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) {
                    Text("Отмена", color = TextOnDarkMuted)
                }
            }
        )
    }
}

@Composable
private fun PanicSellDialog(
    tokenCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = DarkSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(20.dp))
                Text("PANIC SELL", color = DangerRed, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Text(
                "Все $tokenCount открытых позиций будут немедленно закрыты по текущей цене.\n\nЭто действие нельзя отменить.",
                color = TextOnDarkMuted,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
            ) { Text("Закрыть всё", color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextOnDarkMuted) }
        }
    )
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextOnDarkMuted)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = TextOnDark)
    }
}

@Composable
private fun StageProgressRow(token: MonitoredToken) {
    val stages = listOf(
        "S1" to token.exitStage1Done,
        "S2" to token.exitStage2Done,
        "S3" to token.exitStage3Done,
        "S4" to token.exitStage4Done,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Стадии:", style = MaterialTheme.typography.labelSmall, color = TextOnDarkMuted)
        stages.forEach { (label, done) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (done) SuccessGreen.copy(alpha = 0.2f) else DarkBorder.copy(alpha = 0.4f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) SuccessGreen else TextOnDarkFaint,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Остаток: ${formatNumber(token.remainingPositionPct, 0)}%",
            style = MaterialTheme.typography.labelSmall,
            color = CyanAccent,
        )
    }
}

@Composable
private fun EmptyPortfolioPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = TextOnDarkFaint,
            )
            Spacer(Modifier.height(12.dp))
            Text("Нет открытых позиций", style = MaterialTheme.typography.titleMedium, color = TextOnDarkMuted)
            Text("Запустите сканер для поиска токенов", style = MaterialTheme.typography.bodySmall, color = TextOnDarkFaint)
        }
    }
}
