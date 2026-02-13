package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.ServiceController
import tj.khujand.solana.trading.bot.createServiceController
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.util.AppSettings
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.isAndroid
import tj.khujand.solana.trading.bot.network.FilterSettings
import tj.khujand.solana.trading.bot.util.formatDemoBalance
import tj.khujand.solana.trading.bot.util.formatSimpleNumber
import kotlin.math.pow
import kotlin.time.Clock

@Composable
fun MainScreen() {
    val tokenMonitor = remember { TokenMonitor() }

    // Загружаем сохраненные настройки при запуске
    var currentSettings by remember {
        mutableStateOf(FilterSettingsManager.loadSettings())
    }
    val serviceController = remember { createServiceController() }
    var isMonitoring by remember { mutableStateOf(false) }
    var monitoredTokens by remember { mutableStateOf(emptyList<MonitoredToken>()) }
    var showFilterSettings by remember { mutableStateOf(false) }
    var showProfitLoss by remember { mutableStateOf(false) }
    var isRequesting by remember { mutableStateOf(false) }
    var demoBalance by remember { mutableStateOf(DemoAccountManager.getBalance()) }
    var clearFailedCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshDemoBalance() {
        demoBalance = DemoAccountManager.getBalance()
    }

    // Обновляем настройки в мониторе при изменении
    LaunchedEffect(currentSettings) {
        tokenMonitor.filterSettings = currentSettings
    }

    LaunchedEffect(Unit) {
        tokenMonitor.restoreFromCache()
        monitoredTokens = tokenMonitor.monitoredTokens.toList()
        refreshDemoBalance()
        // На Android: восстанавливаем флаг "мониторинг активен" (сервис в бекграунде)
        if (isAndroid()) {
            isMonitoring = AppSettings.getBooleanSafe(AppSettings.KEY_MONITORING_ACTIVE, false)
        } else {
            // Не Android: авто-старт in-app мониторинга если есть токены в кеше
            if (monitoredTokens.isNotEmpty()) {
                tokenMonitor.startMonitoring(
                    intervalSeconds = 10,
                    onNewTokenFound = {
                        monitoredTokens = tokenMonitor.monitoredTokens.toList()
                        refreshDemoBalance()
                    },
                    onTokenUpdated = {
                        monitoredTokens = tokenMonitor.monitoredTokens.toList()
                        refreshDemoBalance()
                    },
                    onRequestStateChanged = { isRequesting = it },
                    onError = { println("Ошибка: $it") }
                )
                isMonitoring = true
            }
        }
    }

    // На Android при активном мониторинге (сервис) подтягиваем список из кеша
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
            if (isMonitoring) {
                serviceController?.stopMonitoring()
                isMonitoring = false
                isRequesting = false
            } else {
                serviceController?.startMonitoring()
                isMonitoring = true
                isRequesting = AppSettings.getBooleanSafe(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
            }
            return
        }
        if (isMonitoring) {
            tokenMonitor.stopMonitoring()
            isMonitoring = false
            isRequesting = false
        } else {
            tokenMonitor.filterSettings = currentSettings
            scope.launch {
                tokenMonitor.startMonitoring(
                    intervalSeconds = 10,
                    onNewTokenFound = {
                        monitoredTokens = tokenMonitor.monitoredTokens.toList()
                        refreshDemoBalance()
                    },
                    onTokenUpdated = {
                        monitoredTokens = tokenMonitor.monitoredTokens.toList()
                        refreshDemoBalance()
                    },
                    onRequestStateChanged = { isRequesting = it },
                    onError = { println("Ошибка: $it") }
                )
                isMonitoring = true
            }
        }
    }

    fun clearAllTokens() {
        if (isAndroid() && isMonitoring) {
            serviceController?.stopMonitoring()
            isMonitoring = false
        }
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
                serviceController?.stopMonitoring()
                serviceController?.startMonitoring()
            } else {
                tokenMonitor.stopMonitoring()
                scope.launch {
                    tokenMonitor.filterSettings = newSettings
                    tokenMonitor.startMonitoring(
                        intervalSeconds = 10,
                        onNewTokenFound = {
                            monitoredTokens = tokenMonitor.monitoredTokens.toList()
                            refreshDemoBalance()
                        },
                        onTokenUpdated = {
                            monitoredTokens = tokenMonitor.monitoredTokens.toList()
                            refreshDemoBalance()
                        },
                        onRequestStateChanged = { isRequesting = it },
                        onError = { println("Ошибка: $it") }
                    )
                    isMonitoring = true
                }
            }
        }
    }

    if (showProfitLoss) {
        ProfitLossScreen(
            onClose = { showProfitLoss = false }
        )
    } else if (showFilterSettings) {
        FilterScreen(
            currentSettings = currentSettings,
            onSettingsChanged = { newSettings ->
                currentSettings = newSettings
            },
            onTestSwap = {
                tokenMonitor.testSwap()
            },
            onClose = {
                showFilterSettings = false
                updateSettings(currentSettings)
                refreshDemoBalance()
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 📊 Верхняя панель
            Card(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 55.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "🤖 Dex Monitor",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val transition = rememberInfiniteTransition()
                        val dotsAlpha by transition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 700),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                        Text(
                            if (isRequesting) "Dex Monitoring • request..." else "Dex Monitoring",
                            fontSize = 12.sp,
                            color = if (isRequesting)
                                MaterialTheme.colorScheme.primary.copy(alpha = dotsAlpha)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (isMonitoring) "🟢 Active (auto)"
                            else "⚪ Stopped",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        Text(
                            "Demo balance: $${formatDemoBalance(demoBalance)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Сделать так:
                    Badge(
                        containerColor = when {
                            monitoredTokens.size >= currentSettings.maxTokensToMonitor ->
                                MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = when {
                            monitoredTokens.size >= currentSettings.maxTokensToMonitor ->
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "${monitoredTokens.size}/${currentSettings.maxTokensToMonitor}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )

                            // Индикатор если достигнут лимит
                            if (monitoredTokens.size >= currentSettings.maxTokensToMonitor) {
                                Icon(
                                    Icons.Default.PauseCircle,
                                    contentDescription = "Limit reached",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 🎛️ Панель управления
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Кнопка настроек
                        Button(
                            onClick = { showFilterSettings = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Setting", fontSize = 12.sp)
                        }

                        // Кнопка Profit/Loss
                        Button(
                            onClick = { showProfitLoss = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("P&L", fontSize = 12.sp)
                        }
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {


                        // Кнопка старт/стоп
                        Button(
                            onClick = { toggleMonitoring() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMonitoring) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer,
                                contentColor = if (isMonitoring) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                if (isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isMonitoring) "STOP" else "START", fontSize = 13.sp)
                        }

                        // Кнопка очистки
                        Button(
                            onClick = { clearAllTokens() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            enabled = monitoredTokens.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear", fontSize = 13.sp)
                        }

                    }

                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 📊 Активные фильтры
            if (isMonitoring) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FilterAlt,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Active filters:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            "MC $${formatSimpleNumber(currentSettings.entryMinMarketCap.toInt())}-${formatSimpleNumber(currentSettings.entryMaxMarketCap.toInt())} • " +
                                    "Liq ≥$${formatSimpleNumber(currentSettings.entryMinLiquidity.toInt())} • " +
                                    "Vol ≥$${formatSimpleNumber(currentSettings.entryMinVolume.toInt())} • " +
                                    "Age ≤${currentSettings.entryMaxAgeMinutes}m",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 📊 Список токенов
            if (monitoredTokens.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(monitoredTokens) { token ->
                        TokenItemCard(
                            token = token,
                            onCloseToken = { pairAddress, isProfit ->
                                scope.launch {
                                    tokenMonitor.closeTokenManually(pairAddress, isProfit)
                                    monitoredTokens = tokenMonitor.monitoredTokens.toList()
                                    refreshDemoBalance()
                                }
                            }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (isMonitoring) "🔍 Searching for tokens..."
                                else "Press START to begin monitoring",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Tokens will appear here automatically",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    if (clearFailedCount != null) {
                        val failed = clearFailedCount ?: 0
                        val message = if (failed == 0) {
                            "✅ Все токены успешно закрыты"
                        } else {
                            "⚠️ Не удалось закрыть: $failed"
                        }
                        Text(
                            message,
                            fontSize = 12.sp,
                            color = if (failed == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// request indicator moved to header line
