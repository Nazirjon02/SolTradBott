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
            modifier = Modifier.fillMaxWidth()
                .padding(top = 30.dp),
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

        // 1. Минимальный объем 24ч (USD) - ИСПОЛЬЗУЕТСЯ В ФИЛЬТРАЦИИ
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
                        "$${currentSettings.volumeH24MinUsd.toInt()}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = currentSettings.volumeH24MinUsd.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(volumeH24MinUsd = newValue.toDouble())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 100f..10000f,
                    steps = 99
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("5K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("10K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Максимальный возраст пары (часы) - ИСПОЛЬЗУЕТСЯ В ФИЛЬТРАЦИИ
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
                    Text("⏰ Max Pair Age", fontWeight = FontWeight.Medium)
                    Text(
                        "${currentSettings.pairMaxAgeHours}h",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = currentSettings.pairMaxAgeHours.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(pairMaxAgeHours = newValue.toDouble())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 0.5f..4f,
                    steps = 7
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.5h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("4h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Минимальная ликвидность (USD) - ИСПОЛЬЗУЕТСЯ В ФИЛЬТРАЦИИ
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
                        "$${currentSettings.liquidityMinUsd.toInt()}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = currentSettings.liquidityMinUsd.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(liquidityMinUsd = newValue.toDouble())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 100f..5000f,
                    steps = 49
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2.5K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("5K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Кулдаун после TP/SL (минуты, в течение которых токен не добавляется снова)
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
                    Text("⏳ Cooldown (мин)", fontWeight = FontWeight.Medium)
                    Text(
                        "${currentSettings.cooldownMinutes}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "После TP/SL токен не добавляется в мониторинг повторно",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Slider(
                    value = currentSettings.cooldownMinutes.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(cooldownMinutes = newValue.toInt())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 0f..480f,
                    steps = 47
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("4h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("8h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Макс. повторов: сколько раз один токен можно снова добавить в мониторинг после TP/SL
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
                    Text("🔄 Повторов после TP/SL", fontWeight = FontWeight.Medium)
                    Text(
                        "${currentSettings.maxReentriesAfterClose}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "0 = только один раз, 1 = один повтор, 2 = два повтора и т.д.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Slider(
                    value = currentSettings.maxReentriesAfterClose.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(maxReentriesAfterClose = newValue.toInt())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 0f..5f,
                    steps = 5
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2-3", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("5", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Авто-стоп при резком падении цены
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = currentSettings.autoStopEnabled,
                        onCheckedChange = { checked ->
                            val newSettings = currentSettings.copy(autoStopEnabled = checked)
                            onSettingsChanged(newSettings)
                            saveSettings(newSettings)
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("🛑 Авто-стоп при падении", fontWeight = FontWeight.Medium)
                }
                Text(
                    "При резком падении цены закрыть как по кнопке Profit (фиксация убытка)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentSettings.autoStopEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Падение %", fontWeight = FontWeight.Medium)
                        Text(
                            "${currentSettings.autoStopDropPercent.toInt()}%",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = currentSettings.autoStopDropPercent.toFloat(),
                        onValueChange = { newValue ->
                            val newSettings = currentSettings.copy(autoStopDropPercent = newValue.toDouble())
                            onSettingsChanged(newSettings)
                            saveSettings(newSettings)
                        },
                        valueRange = 5f..40f,
                        steps = 7
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = currentSettings.autoStopFromPeak,
                            onCheckedChange = { checked ->
                                val newSettings = currentSettings.copy(autoStopFromPeak = checked)
                                onSettingsChanged(newSettings)
                                saveSettings(newSettings)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (currentSettings.autoStopFromPeak) "От пика (макс. с добавления)" else "От цены входа",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

// 5. Максимальное количество токенов
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
                    Text("🎯 Max Tokens", fontWeight = FontWeight.Medium)
                    Text(
                        "${currentSettings.maxTokensToMonitor}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = currentSettings.maxTokensToMonitor.toFloat(),
                    onValueChange = { newValue ->
                        val newSettings = currentSettings.copy(maxTokensToMonitor = newValue.toInt())
                        onSettingsChanged(newSettings)
                        saveSettings(newSettings)
                    },
                    valueRange = 1f..50f,
                    steps = 49
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("25", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("50", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 6. Выбор блокчейнов
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

        // 7. Дополнительные фильтры
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

        Spacer(modifier = Modifier.height(16.dp))

        // 8. Выбор API для поиска токенов
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🔌 API Source", fontWeight = FontWeight.Medium, fontSize = 16.sp)

                Spacer(modifier = Modifier.height(12.dp))

                // Переключатель между API
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = currentSettings.useTokenBoostsApi,
                        onCheckedChange = { checked ->
                            val newSettings = currentSettings.copy(useTokenBoostsApi = checked)
                            onSettingsChanged(newSettings)
                            saveSettings(newSettings)
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (currentSettings.useTokenBoostsApi) "Token Boosts API" else "Token Profiles API",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (currentSettings.useTokenBoostsApi) 
                                "token-boosts/latest/v1 (рекомендуется)"
                            else 
                                "token-profiles/latest/v1 (альтернатива)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Информация о выбранном API
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Текущий API:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (currentSettings.useTokenBoostsApi)
                                "https://api.dexscreener.com/token-boosts/latest/v1"
                            else
                                "https://api.dexscreener.com/token-profiles/latest/v1",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
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
                    Text("Min volume 24h:", fontSize = 12.sp)
                    Text(
                        "$${currentSettings.volumeH24MinUsd.toInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Min liquidity:", fontSize = 12.sp)
                    Text(
                        "$${currentSettings.liquidityMinUsd.toInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max pair age:", fontSize = 12.sp)
                    Text(
                        "${currentSettings.pairMaxAgeHours}h",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cooldown after TP/SL:", fontSize = 12.sp)
                    Text(
                        "${currentSettings.cooldownMinutes} min",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Повторов после TP/SL:", fontSize = 12.sp)
                    Text(
                        "${currentSettings.maxReentriesAfterClose}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Авто-стоп при падении:", fontSize = 12.sp)
                    Text(
                        if (currentSettings.autoStopEnabled) "${currentSettings.autoStopDropPercent.toInt()}% от ${if (currentSettings.autoStopFromPeak) "пика" else "входа"}" else "выкл",
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