package tj.khujand.solana.trading.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.ServiceController
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.createServiceController
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.isAndroid
import tj.khujand.solana.trading.bot.network.FilterSettings
import tj.khujand.solana.trading.bot.util.AppSettings
import tj.khujand.solana.trading.bot.util.formatDemoBalance
import tj.khujand.solana.trading.bot.util.formatSimpleNumber

@Composable
fun MainScreen() {
    val tokenMonitor = remember { TradingRuntime.tokenMonitor() }

    var currentSettings by remember { mutableStateOf(FilterSettingsManager.loadSettings()) }
    val serviceController = remember { createServiceController() }
    var isMonitoring by remember { mutableStateOf(false) }
    var monitoredTokens by remember { mutableStateOf(emptyList<MonitoredToken>()) }
    var showFilterSettings by remember { mutableStateOf(false) }
    var showProfitLoss by remember { mutableStateOf(false) }
    var profitLossRefresh by remember { mutableIntStateOf(0) }
    var isRequesting by remember { mutableStateOf(false) }
    var demoBalance by remember { mutableStateOf(DemoAccountManager.getBalance()) }
    var clearFailedCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshDemoBalance() { demoBalance = DemoAccountManager.getBalance() }

    LaunchedEffect(currentSettings) { tokenMonitor.filterSettings = currentSettings }

    LaunchedEffect(Unit) {
        tokenMonitor.restoreFromCache()
        monitoredTokens = tokenMonitor.monitoredTokens.toList()
        refreshDemoBalance()
        if (isAndroid()) {
            isMonitoring = AppSettings.getBooleanSafe(AppSettings.KEY_MONITORING_ACTIVE, false)
        } else {
            if (monitoredTokens.isNotEmpty()) {
                tokenMonitor.startMonitoring(
                    intervalSeconds = 10,
                    onNewTokenFound = { monitoredTokens = tokenMonitor.monitoredTokens.toList(); refreshDemoBalance() },
                    onTokenUpdated  = { monitoredTokens = tokenMonitor.monitoredTokens.toList(); refreshDemoBalance() },
                    onRequestStateChanged = { isRequesting = it },
                    onError = { println("Ошибка: $it") }
                )
                isMonitoring = true
            }
        }
    }

    LaunchedEffect(isMonitoring, isAndroid()) {
        if (!isAndroid() || !isMonitoring) return@LaunchedEffect
        while (true) {
            delay(2000)
            tokenMonitor.restoreFromCache()
            monitoredTokens = tokenMonitor.monitoredTokens.toList()
            refreshDemoBalance()
            isRequesting = AppSettings.getBooleanSafe(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
        }
    }

    fun toggleMonitoring() {
        if (isAndroid()) {
            if (isMonitoring) { serviceController?.stopMonitoring(); isMonitoring = false; isRequesting = false }
            else { serviceController?.startMonitoring(); isMonitoring = true; isRequesting = AppSettings.getBooleanSafe(AppSettings.KEY_REQUEST_IN_PROGRESS, false) }
            return
        }
        if (isMonitoring) {
            tokenMonitor.stopMonitoring(); isMonitoring = false; isRequesting = false
        } else {
            tokenMonitor.filterSettings = currentSettings
            scope.launch {
                tokenMonitor.startMonitoring(
                    intervalSeconds = 10,
                    onNewTokenFound = { monitoredTokens = tokenMonitor.monitoredTokens.toList(); refreshDemoBalance() },
                    onTokenUpdated  = { monitoredTokens = tokenMonitor.monitoredTokens.toList(); refreshDemoBalance() },
                    onRequestStateChanged = { isRequesting = it },
                    onError = { println("Ошибка: $it") }
                )
                isMonitoring = true
            }
        }
    }

    fun clearAllTokens() {
        if (isAndroid() && isMonitoring) { serviceController?.stopMonitoring(); isMonitoring = false }
        scope.launch {
            val failedCount = tokenMonitor.clearAllTokens()
            monitoredTokens = tokenMonitor.monitoredTokens.toList()
            refreshDemoBalance()
            clearFailedCount = failedCount
        }
    }

    fun updateSettings(newSettings: FilterSettings) {
        currentSettings = newSettings
        if (isMonitoring) {
            if (isAndroid()) {
                serviceController?.stopMonitoring(); serviceController?.startMonitoring()
            } else {
                tokenMonitor.stopMonitoring()
                scope.launch {
                    tokenMonitor.filterSettings = newSettings
                    tokenMonitor.startMonitoring(
                        intervalSeconds = 10,
                        onNewTokenFound = { monitoredTokens = tokenMonitor.monitoredTokens.toList(); refreshDemoBalance() },
                        onTokenUpdated  = { monitoredTokens = tokenMonitor.monitoredTokens.toList(); refreshDemoBalance() },
                        onRequestStateChanged = { isRequesting = it },
                        onError = { println("Ошибка: $it") }
                    )
                    isMonitoring = true
                }
            }
        }
    }

    when {
        showProfitLoss -> ProfitLossScreen(
            onClose = { showProfitLoss = false },
            refreshKey = profitLossRefresh
        )
        showFilterSettings -> FilterScreen(
            currentSettings = currentSettings,
            onSettingsChanged = { currentSettings = it },
            onTestSwap = { tokenMonitor.testSwap() },
            onClose = {
                showFilterSettings = false
                updateSettings(currentSettings)
                refreshDemoBalance()
            }
        )
        else -> MainContent(
            isMonitoring        = isMonitoring,
            isRequesting        = isRequesting,
            monitoredTokens     = monitoredTokens,
            currentSettings     = currentSettings,
            demoBalance         = demoBalance,
            clearFailedCount    = clearFailedCount,
            onToggleMonitoring  = { toggleMonitoring() },
            onClearTokens       = { clearAllTokens() },
            onOpenSettings      = { showFilterSettings = true },
            onOpenProfitLoss    = { profitLossRefresh++; showProfitLoss = true },
            onCloseToken        = { address, isProfit ->
                scope.launch {
                    tokenMonitor.closeTokenManually(address, isProfit)
                    monitoredTokens = tokenMonitor.monitoredTokens.toList()
                    refreshDemoBalance()
                }
            }
        )
    }
}

// ─── Main Content ─────────────────────────────────────────────────────────────

@Composable
private fun MainContent(
    isMonitoring: Boolean,
    isRequesting: Boolean,
    monitoredTokens: List<MonitoredToken>,
    currentSettings: FilterSettings,
    demoBalance: Double,
    clearFailedCount: Int?,
    onToggleMonitoring: () -> Unit,
    onClearTokens: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfitLoss: () -> Unit,
    onCloseToken: (String, Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(52.dp))

            // ── Header ──────────────────────────────────────────────────────
            DashboardHeader(
                isMonitoring    = isMonitoring,
                isRequesting    = isRequesting,
                monitoredTokens = monitoredTokens,
                currentSettings = currentSettings,
                demoBalance     = demoBalance,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ── Controls ────────────────────────────────────────────────────
            ControlPanel(
                isMonitoring     = isMonitoring,
                hasTokens        = monitoredTokens.isNotEmpty(),
                onToggle         = onToggleMonitoring,
                onClear          = onClearTokens,
                onOpenSettings   = onOpenSettings,
                onOpenProfitLoss = onOpenProfitLoss,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ── Active Filters strip ─────────────────────────────────────────
            AnimatedVisibility(visible = isMonitoring, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    ActiveFiltersStrip(currentSettings)
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // ── Token list / empty state ─────────────────────────────────────
            if (monitoredTokens.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(monitoredTokens) { token ->
                        TokenItemCard(token = token, onCloseToken = onCloseToken)
                    }
                }
            } else {
                EmptyState(isMonitoring = isMonitoring, clearFailedCount = clearFailedCount)
            }
        }
    }
}

// ─── Dashboard Header ─────────────────────────────────────────────────────────

@Composable
private fun DashboardHeader(
    isMonitoring: Boolean,
    isRequesting: Boolean,
    monitoredTokens: List<MonitoredToken>,
    currentSettings: FilterSettings,
    demoBalance: Double,
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(BrandIndigo, Color(0xFF818CF8))
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Left column
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Dex Monitor",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    // Animated request indicator
                    val transition = rememberInfiniteTransition()
                    val pulse by transition.animateFloat(
                        initialValue = 0.5f,
                        targetValue  = 1f,
                        animationSpec = infiniteRepeatable(
                            animation  = tween(600),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isRequesting -> Color(0xFFFBBF24).copy(alpha = pulse)
                                        isMonitoring -> SuccessGreen
                                        else         -> Color.White.copy(alpha = 0.4f)
                                    }
                                )
                        )
                        Text(
                            when {
                                isRequesting -> "Fetching data…"
                                isMonitoring -> "Active monitoring"
                                else         -> "Stopped"
                            },
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        "Balance  \$${formatDemoBalance(demoBalance)}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }

                // Token count badge
                val atLimit = monitoredTokens.size >= currentSettings.maxTokensToMonitor
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = if (atLimit) 0.3f else 0.2f),
                    tonalElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "${monitoredTokens.size}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "/ ${currentSettings.maxTokensToMonitor}",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                        if (atLimit) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "FULL",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarnAmber,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Control Panel ────────────────────────────────────────────────────────────

@Composable
private fun ControlPanel(
    isMonitoring: Boolean,
    hasTokens: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfitLoss: () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Row 1 — Settings & P&L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border   = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Settings", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = onOpenProfitLoss,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = BrandPurple
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).let {
                        androidx.compose.foundation.BorderStroke(1.dp, BrandPurple.copy(alpha = 0.4f))
                    }
                ) {
                    Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Прибыль / убытки", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Row 2 — Start/Stop & Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitoring) DangerRed else BrandIndigo,
                        contentColor   = Color.White
                    )
                ) {
                    Icon(
                        if (isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isMonitoring) "STOP" else "START",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                Button(
                    onClick  = onClear,
                    enabled  = hasTokens,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = DangerRedBg,
                        contentColor   = DangerRed,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor   = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─── Active Filters Strip ─────────────────────────────────────────────────────

@Composable
private fun ActiveFiltersStrip(settings: FilterSettings) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        color    = BrandIndigoLight,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.FilterAlt,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = BrandIndigo
                )
                Text(
                    "Filters",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandIndigoDark
                )
            }
            Text(
                "MC \$${formatSimpleNumber(settings.entryMinMarketCap.toInt())}–${formatSimpleNumber(settings.entryMaxMarketCap.toInt())} " +
                "• Liq ≥\$${formatSimpleNumber(settings.entryMinLiquidity.toInt())} " +
                "• Vol ≥\$${formatSimpleNumber(settings.entryMinVolume.toInt())} " +
                "• Age ≤${settings.entryMaxAgeMinutes}m",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

// ─── Empty State ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(isMonitoring: Boolean, clearFailedCount: Int?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier  = Modifier.size(80.dp),
                shape     = CircleShape,
                color     = BrandIndigoLight
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isMonitoring) Icons.Default.TrackChanges else Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = BrandIndigo
                    )
                }
            }

            Text(
                if (isMonitoring) "Scanning markets…" else "Ready to start",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                if (isMonitoring)
                    "Tokens matching your filters will appear here"
                else
                    "Press START to begin monitoring",
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            if (clearFailedCount != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val failed = clearFailedCount
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (failed == 0) SuccessGreenBg else DangerRedBg
                ) {
                    Text(
                        if (failed == 0) "✓ All tokens closed successfully"
                        else "⚠ Could not close $failed token(s)",
                        fontSize = 12.sp,
                        color = if (failed == 0) SuccessGreenDark else DangerRedDark,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
