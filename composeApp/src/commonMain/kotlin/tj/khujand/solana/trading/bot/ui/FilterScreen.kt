package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.network.FilterSettings

@Composable
fun FilterScreen(
    currentSettings: FilterSettings,
    onSettingsChanged: (FilterSettings) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Функция для сохранения настроек
    fun saveSettings(settings: FilterSettings) {
        scope.launch {
            FilterSettingsManager.saveSettings(settings)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Заголовок
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "⚙️ Filter Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Row {
                // Кнопка сброса
                IconButton(
                    onClick = {
                        val defaultSettings = FilterSettings()
                        onSettingsChanged(defaultSettings)
                        saveSettings(defaultSettings)
                    }
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset to defaults")
                }

                // Кнопка закрытия
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 1. Минимальный объем (USD)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("💰 Min 24h Volume", fontWeight = FontWeight.Medium)
                    Text(
                        "$${currentSettings.minVolumeUSD.toInt()}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = currentSettings.minVolumeUSD.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(minVolumeUSD = newValue.toDouble())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 1000f..50000f,
                    steps = 49
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("25K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("50K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Максимальный возраст токена
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("⏰ Max Token Age", fontWeight = FontWeight.Medium)
                    Text(
                        "${currentSettings.maxAgeHours}h",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = currentSettings.maxAgeHours.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(maxAgeHours = newValue.toInt())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 1f..72f,
                    steps = 71
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("36h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("72h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Минимальная ликвидность
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("💧 Min Liquidity", fontWeight = FontWeight.Medium)
                    Text(
                        "$${currentSettings.minLiquidityUSD.toInt()}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = currentSettings.minLiquidityUSD.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(minLiquidityUSD = newValue.toDouble())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 5000f..50000f,
                    steps = 45
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("5K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("25K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("50K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Выбор блокчейнов
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🔗 Blockchains", fontWeight = FontWeight.Medium, fontSize = 16.sp)

                Spacer(modifier = Modifier.height(8.dp))

                val availableChains = listOf(
                    "solana" to "Solana",
                    "ethereum" to "Ethereum",
                    "bsc" to "BNB Chain",
                    "arbitrum" to "Arbitrum",
                    "polygon" to "Polygon",
                    "base" to "Base",
                    "avalanche" to "Avalanche",
                    "optimism" to "Optimism"
                )

                availableChains.forEach { (chainId, chainName) ->
                    val isSelected = currentSettings.chains.contains(chainId)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                val newChains = if (isSelected) {
                                    currentSettings.chains - chainId
                                } else {
                                    currentSettings.chains + chainId
                                }
                                val newSettings = currentSettings.copy(chains = newChains)
                                onSettingsChanged(newSettings)
                                saveSettings(newSettings)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                val newChains = if (checked) {
                                    currentSettings.chains + chainId
                                } else {
                                    currentSettings.chains - chainId
                                }
                                val newSettings = currentSettings.copy(chains = newChains)
                                onSettingsChanged(newSettings)
                                saveSettings(newSettings)
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            chainName,
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp
                        )

                        if (isSelected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Кнопка выбрать все/очистить все
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val allChains = availableChains.map { it.first }
                            val newSettings = currentSettings.copy(chains = allChains)
                            onSettingsChanged(newSettings)
                            saveSettings(newSettings)
                        }
                    ) {
                        Text("Select All", fontSize = 12.sp)
                    }

                    TextButton(
                        onClick = {
                            val newSettings = currentSettings.copy(chains = emptyList())
                            onSettingsChanged(newSettings)
                            saveSettings(newSettings)
                        }
                    ) {
                        Text("Clear All", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Дополнительные фильтры
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🛡️ Advanced Filters", fontWeight = FontWeight.Medium, fontSize = 16.sp)

                Spacer(modifier = Modifier.height(12.dp))

                // Проверка на скам
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = currentSettings.excludeRugPull,
                        onCheckedChange = { checked ->
                            val newSettings = currentSettings.copy(excludeRugPull = checked)
                            onSettingsChanged(newSettings)
                            saveSettings(newSettings)
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exclude suspicious tokens", fontWeight = FontWeight.Medium)
                        Text(
                            "Filter out potential scams and rug pulls",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Проверка холдеров
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = currentSettings.checkHolders,
                        onCheckedChange = { checked ->
                            val newSettings = currentSettings.copy(checkHolders = checked)
                            onSettingsChanged(newSettings)
                            saveSettings(newSettings)
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Check holder distribution", fontWeight = FontWeight.Medium)
                        Text(
                            "Filter tokens with centralized holders",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Статистика настроек
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📊 Filter Summary", fontWeight = FontWeight.Medium, fontSize = 14.sp)

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Selected chains:", fontSize = 12.sp)
                    Text(
                        currentSettings.chains.size.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Min volume:", fontSize = 12.sp)
                    Text(
                        "$${currentSettings.minVolumeUSD.toInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max age:", fontSize = 12.sp)
                    Text(
                        "${currentSettings.maxAgeHours}h",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Кнопка закрытия
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE & CLOSE", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}