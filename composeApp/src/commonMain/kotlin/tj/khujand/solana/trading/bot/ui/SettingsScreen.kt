package tj.khujand.solana.trading.bot.ui

import androidx.compose.runtime.*
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.data.FilterSettingsManager

@Composable
fun SettingsScreen() {
    val tokenMonitor = remember { TradingRuntime.tokenMonitor() }
    var settings by remember { mutableStateOf(FilterSettingsManager.loadSettings()) }

    FilterScreen(
        currentSettings  = settings,
        onSettingsChanged = { updated ->
            settings = updated
            FilterSettingsManager.saveSettings(updated)
            tokenMonitor.filterSettings = updated
        },
        onTestSwap = { tokenMonitor.testSwap() },
        onClose    = {},
    )
}
