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

//    val tokenMonitor = remember { TokenMonitor() }
//    var isMonitoring by remember { mutableStateOf(false) }
//    var monitoredTokens by remember { mutableStateOf(emptyList<MonitoredToken>()) }
//    var showFilterSettings by remember { mutableStateOf(false) }
//    var currentSettings by remember { mutableStateOf(tokenMonitor.filterSettings) }
//    val scope = rememberCoroutineScope()

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
                        if (tokenMonitor.monitoredTokens.size >= 5) {
                            return@startMonitoring
                        }

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
                        if (tokenMonitor.monitoredTokens.size >= 5) {
                            return@startMonitoring
                        }
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
                modifier = Modifier.fillMaxWidth(),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            "Tokens: ${monitoredTokens.size}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
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
                        Text("Settings", fontSize = 13.sp)
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

// 📄 Улучшенная карточка токена
@Composable
fun TokenItemCard(token: MonitoredToken) {
    val clipboardManager = LocalClipboardManager.current
    var showCopiedMessage by remember { mutableStateOf(false) }
    var showFullAddress by remember { mutableStateOf(false) }

    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            delay(2000)
            showCopiedMessage = false
        }
    }

    // Определяем цвет рамки в зависимости от статуса
    val borderColor = when (token.status) {
        TokenStatus.STOPPED_TP -> Color(0xFF1B5E20).copy(alpha = 0.3f)
        TokenStatus.STOPPED_SL -> Color(0xFFB71C1C).copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (token.status != TokenStatus.MONITORING) 1.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = when (token.status) {
                TokenStatus.STOPPED_TP -> Color(0xFF1B5E20).copy(alpha = 0.1f)
                TokenStatus.STOPPED_SL -> Color(0xFFB71C1C).copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Первая строка: Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                token.tokenPair.baseToken?.symbol?.take(2) ?: "??",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Column {
                            Text(
                                token.tokenPair.baseToken?.symbol ?: "Unknown",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                token.tokenPair.baseToken?.name ?: "Unknown Token",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Бейдж статуса
                Badge(
                    containerColor = when (token.status) {
                        TokenStatus.STOPPED_TP -> Color(0xFF1B5E20)
                        TokenStatus.STOPPED_SL -> Color(0xFFB71C1C)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    contentColor = Color.White
                ) {
                    Text(
                        when (token.status) {
                            TokenStatus.STOPPED_TP -> "TP HIT"
                            TokenStatus.STOPPED_SL -> "SL HIT"
                            else -> "LIVE"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Token Address с копированием
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Label
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
                            token.tokenPair.baseToken?.address?.let { address ->
                                clipboardManager.setText(AnnotatedString(address))
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
                                token.tokenPair.baseToken?.address?.let { address ->
                                    clipboardManager.setText(AnnotatedString(address))
                                    showCopiedMessage = true
                                }
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "✓ Address copied to clipboard",
                    fontSize = 10.sp,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Цена и изменение
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        "Price",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$${token.currentPrice}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        "24h Change",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (token.priceChangePercent >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (token.priceChangePercent >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                        Text(
                            text = "${if (token.profitUsd >= 0) "+" else ""}$${formatNumber(token.profitUsd)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (token.profitUsd >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Статистика в 3 колонки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Ликвидность
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.WaterDrop,
                            contentDescription = "Liquidity",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Liquidity",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$${formatLargeNumber(token.tokenPair.liquidity?.usd ?: 0.0)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Объем
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.BarChart,
                            contentDescription = "Volume",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Volume",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$${formatLargeNumber(token.tokenPair.volume?.h24 ?: 0.0)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Цепочка
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = "Chain",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Chain",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        token.tokenPair.chainId?.uppercase()?.take(3) ?: "N/A",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Нижняя строка: время и возраст
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = "Found time",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatTimeAgo(token.foundTime),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Cake,
                        contentDescription = "Token age",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Age: ${formatAge(token.foundTime)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// 🔧 Улучшенные функции форматирования (кросс-платформенные)

/**
 * Форматирование адреса (первые 6 и последние 4 символа)
 */
private fun formatAddress(address: String?): String {
    if (address.isNullOrBlank()) return "N/A"
    if (address.length <= 10) return address
    return "${address.take(6)}...${address.takeLast(4)}"
}

/**
 * Умное форматирование цены
 */

private fun formatPrice(price: Double): String {
    if (price <= 0) return "0"

    val decimals = when {
        price >= 1000 -> 2
        price >= 1 -> 4
        price >= 0.0001 -> 6
        price >= 0.000001 -> 8
        else -> 10
    }

    return price.roundToDecimals(decimals).removeTrailingZeros()
}

private fun Double.roundToDecimals(decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = round(this * factor) / factor
    return rounded.toString()
}



/**
 * Форматирование времени "сколько времени назад"
 */
private fun formatTimeAgo(timestamp: Long): String {
    val now = Clock.System.now().toEpochMilliseconds()
    val diff = now - timestamp
    val minutes = diff / (1000 * 60)
    val hours = diff / (1000 * 60 * 60)
    val days = diff / (1000 * 60 * 60 * 24)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 7 -> "$days days ago"
        else -> "${days / 7} wk ago"
    }
}

/**
 * Форматирование возраста токена
 */
private fun formatAge(timestamp: Long): String {
    val now = Clock.System.now().toEpochMilliseconds()
    val diff = now - timestamp
    val hours = diff / (1000 * 60 * 60)
    val days = hours / 24

    return if (hours < 24) {
        "${hours}h"
    } else {
        "${days}d"
    }
}


/**
 * Простое форматирование чисел
 */
private fun formatSimpleNumber(number: Int): String {
    return when {
        number >= 1_000_000 -> "${number / 1_000_000}M"
        number >= 1_000 -> "${number / 1_000}K"
        else -> number.toString()
    }
}

/**
 * Удаление незначащих нулей и точки
 */
//private fun String.removeTrailingZeros(): String {
//    return if (contains(".")) {
//        this.trimEnd('0').trimEnd('.')
//    } else {
//        this
//    }
//}

private fun Double.roundToDecimalss(decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return round(this * factor) / factor
}

private fun Double.toCleanString(): String {
    return this.toString().removeTrailingZeros()
}

private fun String.removeTrailingZeros(): String {
    return trimEnd('0').trimEnd('.')
}

/* ---------- format percent ---------- */

private fun formatPercent(percent: Double): String {
    if (percent.isNaN() || percent.isInfinite()) return "0"
    return percent
        .roundToDecimalss(2)
        .toCleanString()
}

/* ---------- format large numbers ---------- */

private fun formatLargeNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"

    return when {
        value >= 1_000_000_000 ->
            (value / 1_000_000_000)
                .roundToDecimalss(2)
                .toCleanString() + "B"

        value >= 1_000_000 ->
            (value / 1_000_000)
                .roundToDecimalss(2)
                .toCleanString() + "M"

        value >= 1_000 ->
            (value / 1_000)
                .roundToDecimalss(2)
                .toCleanString() + "K"

        value >= 1 ->
            value.roundToDecimalss(0).toCleanString()

        else ->
            value.roundToDecimalss(4).toCleanString()
    }
}

/* ---------- format regular numbers ---------- */

fun formatNumber(number: Double): String {
    if (number.isNaN() || number.isInfinite()) return "0"

    return when {
        number >= 1000 ->
            number.roundToDecimalss(0).toCleanString()

        number >= 1 ->
            number.roundToDecimalss(2).toCleanString()

        else ->
            number.roundToDecimalss(4).toCleanString()
    }
}
