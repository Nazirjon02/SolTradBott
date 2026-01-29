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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.network.FilterSettings
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Clock

@Composable
fun MainScreen() {
    val tokenMonitor = remember { TokenMonitor() }

    // Загружаем сохраненные настройки при запуске
    var currentSettings by remember {
        mutableStateOf(FilterSettingsManager.loadSettings())
    }

    var isMonitoring by remember { mutableStateOf(false) }
    var monitoredTokens by remember { mutableStateOf(emptyList<MonitoredToken>()) }
    var showFilterSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Обновляем настройки в мониторе при изменении
    LaunchedEffect(currentSettings) {
        tokenMonitor.filterSettings = currentSettings
    }


    fun toggleMonitoring() {
        if (isMonitoring) {
            tokenMonitor.stopMonitoring()
            isMonitoring = false
        } else {
            tokenMonitor.filterSettings = currentSettings
            scope.launch {
                tokenMonitor.startMonitoring(
                    intervalSeconds = 3, // ⏱️ 30 секунд для соблюдения rate limits
                    onNewTokenFound = { newToken ->

                        monitoredTokens = tokenMonitor.monitoredTokens
                    },
                    onTokenUpdated = { updatedToken ->
                        monitoredTokens = tokenMonitor.monitoredTokens.toList()
                    },
                    onError = { error ->
                        println("Ошибка: $error")
                    }
                )
                isMonitoring = true
            }
        }
    }

    fun clearAllTokens() {
        tokenMonitor.clearAllTokens()
        monitoredTokens = emptyList()
    }



    fun updateSettings(newSettings: FilterSettings) {
        currentSettings = newSettings
        if (isMonitoring) {
            tokenMonitor.stopMonitoring()
            isMonitoring = false
            scope.launch {
                tokenMonitor.filterSettings = newSettings
                tokenMonitor.startMonitoring(
                    intervalSeconds = 3, // ⏱️ 30 секунд для соблюдения rate limits
                    onNewTokenFound = { newToken ->
                        monitoredTokens = tokenMonitor.monitoredTokens
                    },
                    onTokenUpdated = { updatedToken ->
                        monitoredTokens = tokenMonitor.monitoredTokens.toList()
                    },
                    onError = { error ->
                        println("Ошибка: $error")
                    }
                )
                isMonitoring = true
            }
        }
    }

    if (showFilterSettings) {
        FilterScreen(
            currentSettings = currentSettings,
            onSettingsChanged = { newSettings ->
                currentSettings = newSettings
            },
            onClose = {
                showFilterSettings = false
                updateSettings(currentSettings)
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
                        Text(
                            if (isMonitoring) "🟢 Active" else "⚪ Stopped",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
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
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
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
                            "Vol >$${formatSimpleNumber(currentSettings.minVolumeUSD.toInt())} • " +
                                    "Age <${currentSettings.maxAgeHours}",
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
                        TokenItemCard(token = token)
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
                }
            }
        }
    }
}
