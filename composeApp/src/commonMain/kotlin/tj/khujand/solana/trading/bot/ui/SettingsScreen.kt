package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
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
import tj.khujand.solana.trading.bot.readSettingsFile
import tj.khujand.solana.trading.bot.writeSettingsFile

@Composable
fun SettingsScreen() {
    val tokenMonitor = remember { TradingRuntime.tokenMonitor() }
    var settings by remember { mutableStateOf(FilterSettingsManager.loadSettings()) }

    fun applySettings(updated: tj.khujand.solana.trading.bot.network.FilterSettings) {
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

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Профиль стратегии", style = MaterialTheme.typography.titleMedium, color = TextOnDark)
            Text(
                "Быстро применить набор настроек для выбранного стиля торговли",
                style = MaterialTheme.typography.bodySmall,
                color = TextOnDarkMuted,
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
            title = { Text("${profile.emoji} ${profile.label}", color = TextOnDark) },
            text  = {
                Text(
                    "${profile.description}\n\nНастройки будут перезаписаны. Ключи и секреты сохранятся.",
                    color = TextOnDarkMuted,
                )
            },
            confirmButton = {
                Button(
                    onClick = { onProfileSelected(profile); showConfirm = null },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                ) { Text("Применить", color = DarkBg) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = null }) { Text("Отмена", color = TextOnDarkMuted) }
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
                color = TextOnDark,
                maxLines = 1,
            )
        }
    }
}

// ─── Backup Card ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsBackupCard(
    onSettingsImported: (tj.khujand.solana.trading.bot.network.FilterSettings) -> Unit
) {
    var exportMsg     by remember { mutableStateOf<String?>(null) }
    var showImport    by remember { mutableStateOf(false) }
    var importJson    by remember { mutableStateOf("") }
    var importResult  by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Резервная копия настроек", style = MaterialTheme.typography.titleMedium, color = TextOnDark)
            Text(
                "Экспортируй все настройки в файл и быстро восстанови их без ручного ввода",
                style = MaterialTheme.typography.bodySmall,
                color = TextOnDarkMuted,
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
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccentBg, contentColor = CyanAccent),
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
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVar, contentColor = TextOnDark),
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
                        color = if (msg.startsWith("Ошибка")) DangerRedDark else SuccessGreenDark,
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
            title = { Text("Импорт настроек", color = TextOnDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Вставь JSON из файла резервной копии. Текущие настройки будут перезаписаны.",
                        color = TextOnDarkMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = importJson,
                        onValueChange = { importJson = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        placeholder = { Text("{\"version\":2,...}", color = TextOnDarkMuted, fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = TextOnDark,
                            fontSize = 11.sp,
                        ),
                        maxLines = 12,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = DarkSurfaceVar,
                        ),
                    )
                    importResult?.let { res ->
                        Text(res, fontSize = 11.sp, color = if (res.startsWith("Ошибка")) DangerRedDark else SuccessGreenDark)
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
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                ) { Text("Применить", color = DarkBg) }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importResult = null }) {
                    Text("Отмена", color = TextOnDarkMuted)
                }
            },
        )
    }
}
