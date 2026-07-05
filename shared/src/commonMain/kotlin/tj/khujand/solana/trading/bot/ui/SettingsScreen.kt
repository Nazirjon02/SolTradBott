package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.data.StrategyProfile
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.domain.EquityManager
import tj.khujand.solana.trading.bot.domain.TokenHistoryManager
import tj.khujand.solana.trading.bot.readSettingsFile
import tj.khujand.solana.trading.bot.writeSettingsFile

@Composable
fun SettingsScreen() {
    val tokenMonitor = remember { TradingRuntime.tokenMonitor() }
    val engineController = remember { TradingRuntime.engineController() }
    var settings by remember { mutableStateOf(FilterSettingsManager.loadSettings()) }

    fun applySettings(updated: tj.khujand.solana.trading.bot.exchange.dex.FilterSettings) {
        settings = updated
        FilterSettingsManager.saveSettings(updated)
        tokenMonitor.filterSettings = updated
    }

    Column {
        StrategyProfileCard(
            onProfileSelected = { profile -> applySettings(profile.applyTo(settings)) }
        )
        SettingsBackupCard(
            onSettingsImported = { newSettings ->
                settings = newSettings
                tokenMonitor.filterSettings = newSettings
            }
        )
        BotResetCard(
            onReset = {
                engineController.stopMonitoring()
                tokenMonitor.fullReset()
                TokenHistoryManager.clearHistory()
                EquityManager.clear()
                DemoAccountManager.resetBalance()
            }
        )
        FilterScreen(
            currentSettings   = settings,
            onSettingsChanged = { applySettings(it) },
            onTestSwap        = { tokenMonitor.testSwap() },
            onClose           = {},
        )
    }
}

@Composable
private fun StrategyProfileCard(onProfileSelected: (StrategyProfile) -> Unit) {
    var showConfirm by remember { mutableStateOf<StrategyProfile?>(null) }

    GlowCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        glow = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Профиль стратегии", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "Быстро применить набор настроек для выбранного стиля торговли",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StrategyProfile.entries.forEach { profile ->
                    ProfileChip(
                        profile  = profile,
                        modifier = Modifier.weight(1f),
                        onClick  = { showConfirm = profile },
                    )
                }
            }
        }
    }

    showConfirm?.let { profile ->
        AlertDialog(
            onDismissRequest = { showConfirm = null },
            containerColor   = DarkSurface,
            title = { Text("${profile.emoji} ${profile.label}", color = TextPrimary) },
            text  = {
                Text(
                    "${profile.description}\n\nНастройки будут перезаписаны. Ключи и секреты сохранятся.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = { onProfileSelected(profile); showConfirm = null },
                    colors = ButtonDefaults.buttonColors(containerColor = SolPurple),
                ) { Text("Применить", color = DarkBg) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = null }) { Text("Отмена", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun ProfileChip(profile: StrategyProfile, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier  = modifier,
        onClick   = onClick,
        shape     = RoundedCornerShape(8.dp),
        color     = DarkSurfaceVar,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(profile.emoji, style = MaterialTheme.typography.titleMedium)
            Text(
                profile.label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
                maxLines = 1,
            )
        }
    }
}

// ─── Bot Reset Card ───────────────────────────────────────────────────────────

@Composable
private fun BotResetCard(onReset: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = DangerRedBg),
        border   = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment      = Alignment.CenterVertically,
                horizontalArrangement  = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(18.dp))
                Text("Полный сброс бота", style = MaterialTheme.typography.titleMedium, color = DangerRed)
            }
            Text(
                "Удаляет историю сделок, активные позиции, кривую капитала и сбрасывает демо-баланс до \$10 000. Настройки фильтров, ключи и Telegram не затрагиваются.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Button(
                onClick  = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(8.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = DarkBg),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Сбросить всё", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor   = DarkSurface,
            title            = { Text("Подтвердить сброс", color = DangerRed) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Будет безвозвратно удалено:", color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    listOf(
                        "Вся история сделок",
                        "Активные позиции (кеш)",
                        "Список закрытых токенов",
                        "Кривая капитала / Equity",
                        "Демо-баланс → сброс до \$10 000",
                    ).forEach { item ->
                        Text("• $item", color = TextSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Настройки фильтров, API-ключи и Telegram не затрагиваются.",
                        color    = TextMuted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onReset(); showConfirm = false },
                    colors  = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = DarkBg),
                ) { Text("Сбросить", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Отмена", color = TextSecondary) }
            },
        )
    }
}

// ─── Backup Card ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsBackupCard(
    onSettingsImported: (tj.khujand.solana.trading.bot.exchange.dex.FilterSettings) -> Unit
) {
    var exportMsg     by remember { mutableStateOf<String?>(null) }
    var showImport    by remember { mutableStateOf(false) }
    var importJson    by remember { mutableStateOf("") }
    var importResult  by remember { mutableStateOf<String?>(null) }

    GlowCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Резервная копия настроек", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "Экспортируй все настройки в файл и быстро восстанови их без ручного ввода",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Export
                Button(
                    onClick = {
                        val json = FilterSettingsManager.exportToJson()
                        val result = writeSettingsFile(json)
                        exportMsg = if (result.startsWith("ERROR:"))
                            "Ошибка: ${result.removePrefix("ERROR:")}"
                        else
                            "Сохранено:\n$result"
                    },
                    modifier = Modifier.weight(1f),
                    shape  = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SolPurpleBg, contentColor = SolPurple),
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Экспорт", fontSize = 13.sp)
                }
                // Import
                Button(
                    onClick = {
                        val existing = readSettingsFile()
                        if (existing != null) importJson = existing
                        showImport = true
                    },
                    modifier = Modifier.weight(1f),
                    shape  = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVar, contentColor = TextPrimary),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Импорт", fontSize = 13.sp)
                }
            }

            exportMsg?.let { msg ->
                Surface(shape = RoundedCornerShape(6.dp), color = if (msg.startsWith("Ошибка")) DangerRedBg else SuccessGreenBg) {
                    Text(
                        msg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 11.sp,
                        color = if (msg.startsWith("Ошибка")) DangerRed else SuccessGreen,
                    )
                }
            }
        }
    }

    // Import dialog
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importResult = null },
            containerColor = DarkSurface,
            title = { Text("Импорт настроек", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Вставь JSON из файла резервной копии. Текущие настройки будут перезаписаны.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = importJson,
                        onValueChange = { importJson = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        placeholder = { Text("{\"version\":2,...}", color = TextSecondary, fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = TextPrimary,
                            fontSize = 11.sp,
                        ),
                        maxLines = 12,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SolPurple,
                            unfocusedBorderColor = DarkSurfaceVar,
                        ),
                    )
                    importResult?.let { res ->
                        Text(res, fontSize = 11.sp, color = if (res.startsWith("Ошибка")) DangerRed else SuccessGreen)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val err = FilterSettingsManager.importFromJson(importJson)
                        if (err == null) {
                            val loaded = FilterSettingsManager.loadSettings()
                            onSettingsImported(loaded)
                            importResult = "Настройки успешно применены"
                        } else {
                            importResult = err
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SolPurple),
                ) { Text("Применить", color = DarkBg) }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importResult = null }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
        )
    }
}
