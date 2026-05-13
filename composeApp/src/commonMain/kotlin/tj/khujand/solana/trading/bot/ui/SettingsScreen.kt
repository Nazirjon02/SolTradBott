package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.data.StrategyProfile

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
