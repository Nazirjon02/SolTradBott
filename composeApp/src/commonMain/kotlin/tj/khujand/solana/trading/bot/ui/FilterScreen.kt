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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import tj.khujand.solana.trading.bot.crypto.createSignerFromSeedPhrase
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.network.FilterSettings

@Composable
fun FilterScreen(
    currentSettings: FilterSettings,
    onSettingsChanged: (FilterSettings) -> Unit,
    onTestSwap: suspend () -> String,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showSeedPhrase by remember { mutableStateOf(false) }
    var testSwapStatus by remember { mutableStateOf("") }
    var showResetDemoDialog by remember { mutableStateOf(false) }
    var demoBalance by remember { mutableStateOf(DemoAccountManager.getBalance()) }

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
            exitStage4Pct = p4,
            tradeUsdAmount = settings.tradeUsdAmount.roundToInt().toDouble()
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
                    valueRange = 20_000f..200_000f,
                    steps = 1799
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
                    steps = 3999
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
                    valueRange = 100f..20_000f,
                    steps = 198
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
                    valueRange = 100f..250_000f,
                    steps = 2498
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
                    valueRange = 40_000f..250_000f,
                    steps = 41
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
                    valueRange = 50_000f..350_000f,
                    steps = 59
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
                    valueRange = 60_000f..450_000f,
                    steps = 77
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
                    valueRange = 70_000f..550_000f,
                    steps = 95
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

        // ✅ Jupiter Trading
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🪐 Jupiter Trading", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "⚠️ Автоподпись рискованна. Хранение seed phrase внутри приложения небезопасно.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = currentSettings.jupiterEnabled,
                        onCheckedChange = { checked ->
                            val newSettings = currentSettings.copy(jupiterEnabled = checked)
                            applySettings(newSettings)
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(if (currentSettings.jupiterEnabled) "Real trading ON" else "Real trading OFF")
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = currentSettings.seedPhrase,
                    onValueChange = { value ->
                        val newSettings = currentSettings.copy(seedPhrase = value)
                        applySettings(newSettings)
                    },
                    label = { Text("Seed phrase (12/24 words)") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showSeedPhrase) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showSeedPhrase = !showSeedPhrase }) {
                            Icon(
                                if (showSeedPhrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = currentSettings.jupiterApiKey,
                    onValueChange = { value ->
                        val newSettings = currentSettings.copy(jupiterApiKey = value.trim())
                        applySettings(newSettings)
                    },
                    label = { Text("Jupiter API key (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                val publicKey = remember(currentSettings.seedPhrase) {
                    if (currentSettings.seedPhrase.isBlank()) {
                        ""
                    } else {
                        try {
                            createSignerFromSeedPhrase(currentSettings.seedPhrase.trim()).publicKeyBase58()
                        } catch (e: Exception) {
                            "unavailable"
                        }
                    }
                }
                val seedValid = remember(currentSettings.seedPhrase) {
                    val words = currentSettings.seedPhrase.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    (words.size == 12 || words.size == 24) && publicKey.isNotBlank() && publicKey != "unavailable"
                }
                if (publicKey.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Wallet: $publicKey",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (currentSettings.seedPhrase.isNotBlank() && !seedValid) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Seed phrase invalid",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Trade amount ($)")
                    Text("$${currentSettings.tradeUsdAmount.toInt()}", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.tradeUsdAmount.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(tradeUsdAmount = v.roundToInt().toDouble())
                        applySettings(newSettings)
                    },
                    valueRange = 1f..20f,
                    steps = 19
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Slippage")
                    Text("${currentSettings.slippageBps / 100.0}%", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = currentSettings.slippageBps.toFloat(),
                    onValueChange = { v ->
                        val newSettings = currentSettings.copy(slippageBps = v.toInt())
                        applySettings(newSettings)
                    },
                    valueRange = 10f..200f,
                    steps = 19
                )

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            testSwapStatus = onTestSwap()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = currentSettings.jupiterEnabled
                ) {
                    Text("Test swap")
                }
                if (testSwapStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        testSwapStatus,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ✅ Demo account
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🧪 Demo account", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Balance: $${formatDemoBalance(demoBalance)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Each new token = -$${DemoAccountManager.DEMO_TRADE_AMOUNT.toInt()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showResetDemoDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset demo balance")
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

    if (showResetDemoDialog) {
        AlertDialog(
            onDismissRequest = { showResetDemoDialog = false },
            title = { Text("Reset demo account?") },
            text = { Text("Demo balance will be set to $10000.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        DemoAccountManager.resetBalance()
                        demoBalance = DemoAccountManager.getBalance()
                        showResetDemoDialog = false
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDemoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatDemoBalance(value: Double): String {
    val factor = 100.0
    val rounded = kotlin.math.round(value * factor) / factor
    val parts = rounded.toString().split('.')
    return if (parts.size == 1) {
        "$rounded.00"
    } else {
        val decimalPart = parts[1].padEnd(2, '0').take(2)
        "${parts[0]}.$decimalPart"
    }
}