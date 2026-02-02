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

    fun normalizeSettings(settings: FilterSettings): FilterSettings {
        var entryMin = settings.entryMinMarketCap
        var entryMax = settings.entryMaxMarketCap
        if (entryMin > entryMax) {
            val tmp = entryMin
            entryMin = entryMax
            entryMax = tmp
        }

        val p1 = settings.exitStage1Pct.coerceIn(0.0, 100.0)
        val p2 = settings.exitStage2Pct.coerceIn(0.0, 100.0)
        val p3 = settings.exitStage3Pct.coerceIn(0.0, 100.0)
        val p4 = (100.0 - (p1 + p2 + p3)).coerceIn(0.0, 100.0)

        return settings.copy(
            entryMinMarketCap = entryMin,
            entryMaxMarketCap = entryMax,
            exitStage1Pct = p1,
            exitStage2Pct = p2,
            exitStage3Pct = p3,
            exitStage4Pct = p4
        )
    }

    fun applySettings(updated: FilterSettings) {
        val normalized = normalizeSettings(updated)
        onSettingsChanged(normalized)
        saveSettings(normalized)
    }

    fun isStageCapOrderValid(settings: FilterSettings): Boolean {
        return settings.exitStage1Cap <= settings.exitStage2Cap &&
            settings.exitStage2Cap <= settings.exitStage3Cap &&
            settings.exitStage3Cap <= settings.exitStage4Cap
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

        // ✅ Условия входа (по ТЗ)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📥 Entry rules", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Max age (min)")
                    Text("${currentSettings.entryMaxAgeMinutes}", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.entryMaxAgeMinutes.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(entryMaxAgeMinutes = v.toInt())
                        applySettings(newSettings)
                    },
                    valueRange = 5f..120f,
                    steps = 23
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Market Cap min")
                    Text("$${currentSettings.entryMinMarketCap.toInt()}", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.entryMinMarketCap.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(entryMinMarketCap = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 50_000f..200_000f,
                    steps = 30
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Market Cap max")
                    Text("$${currentSettings.entryMaxMarketCap.toInt()}", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.entryMaxMarketCap.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(entryMaxMarketCap = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 100_000f..500_000f,
                    steps = 40
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Min liquidity")
                    Text("$${currentSettings.entryMinLiquidity.toInt()}", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.entryMinLiquidity.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(entryMinLiquidity = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 1000f..20_000f,
                    steps = 38
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Min volume 24h")
                    Text("$${currentSettings.entryMinVolume.toInt()}", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.entryMinVolume.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(entryMinVolume = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 50_000f..300_000f,
                    steps = 50
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = currentSettings.requireSocials,
                        onCheckedChange = { checked ->
                            val newSettings = currentSettings.copy(requireSocials = checked)
                            applySettings(newSettings)
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Require socials (Telegram/X)")
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = currentSettings.requireWebsite,
                        onCheckedChange = { checked ->
                            val newSettings = currentSettings.copy(requireWebsite = checked)
                            applySettings(newSettings)
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Require website")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ✅ Условия выхода (по ТЗ)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📤 Exit stages", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                val stageOrderOk = isStageCapOrderValid(currentSettings)
                if (!stageOrderOk) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "⚠️ Cap значения должны идти по возрастанию (Stage1 ≤ Stage2 ≤ Stage3 ≤ Stage4)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 1 cap")
                    Text("$${currentSettings.exitStage1Cap.toInt()} • ${currentSettings.exitStage1Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage1Cap.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage1Cap = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 150_000f..300_000f,
                    steps = 30
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 1 %")
                    Text("${currentSettings.exitStage1Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage1Pct.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage1Pct = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 5f..50f,
                    steps = 45
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 2 cap")
                    Text("$${currentSettings.exitStage2Cap.toInt()} • ${currentSettings.exitStage2Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage2Cap.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage2Cap = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 200_000f..400_000f,
                    steps = 40
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 2 %")
                    Text("${currentSettings.exitStage2Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage2Pct.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage2Pct = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 5f..50f,
                    steps = 45
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 3 cap")
                    Text("$${currentSettings.exitStage3Cap.toInt()} • ${currentSettings.exitStage3Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage3Cap.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage3Cap = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 250_000f..500_000f,
                    steps = 50
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 3 %")
                    Text("${currentSettings.exitStage3Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage3Pct.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage3Pct = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 5f..50f,
                    steps = 45
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 4 cap")
                    Text("$${currentSettings.exitStage4Cap.toInt()} • ${currentSettings.exitStage4Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage4Cap.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage4Cap = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 300_000f..700_000f,
                    steps = 80
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stage 4 %")
                    Text("${currentSettings.exitStage4Pct.toInt()}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.exitStage4Pct.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(exitStage4Pct = v.toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 5f..50f,
                    steps = 45
                )
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
                    Text("Entry age:", fontSize = 12.sp)
                    Text(
                        "${currentSettings.entryMaxAgeMinutes} min",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Entry MC:", fontSize = 12.sp)
                    Text(
                        "$${currentSettings.entryMinMarketCap.toInt()} - $${currentSettings.entryMaxMarketCap.toInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Entry liq/vol:", fontSize = 12.sp)
                    Text(
                        "$${currentSettings.entryMinLiquidity.toInt()} / $${currentSettings.entryMinVolume.toInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Socials/website:", fontSize = 12.sp)
                    Text(
                        "${if (currentSettings.requireSocials) "on" else "off"} / ${if (currentSettings.requireWebsite) "on" else "off"}",
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