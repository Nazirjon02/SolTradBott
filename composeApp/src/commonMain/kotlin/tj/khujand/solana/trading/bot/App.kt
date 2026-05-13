package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import tj.khujand.solana.trading.bot.ui.*

private enum class AppTab(val label: String, val icon: ImageVector) {
    SCANNER  ("Сканер",    Icons.Default.Search),
    PORTFOLIO("Портфель",  Icons.Default.AccountBalanceWallet),
    HISTORY  ("История",   Icons.Default.History),
    ANALYTICS("Аналитика", Icons.Default.BarChart),
    SETTINGS ("Настройки", Icons.Default.Settings),
}

@Composable
fun App() {
    SolTradBotTheme {
        var currentTab by remember { mutableStateOf(AppTab.SCANNER) }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = DarkSurface,
                    contentColor   = CyanAccent,
                ) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected  = currentTab == tab,
                            onClick   = { currentTab = tab },
                            icon      = { Icon(tab.icon, contentDescription = tab.label) },
                            label     = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors    = NavigationBarItemDefaults.colors(
                                selectedIconColor   = CyanAccent,
                                selectedTextColor   = CyanAccent,
                                unselectedIconColor = TextOnDarkMuted,
                                unselectedTextColor = TextOnDarkMuted,
                                indicatorColor      = CyanAccentBg,
                            )
                        )
                    }
                }
            },
            containerColor = DarkBg,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(DarkBg)
            ) {
                when (currentTab) {
                    AppTab.SCANNER   -> MainScreen()
                    AppTab.PORTFOLIO -> PortfolioScreen()
                    AppTab.HISTORY   -> HistoryScreen()
                    AppTab.ANALYTICS -> AnalyticsScreen()
                    AppTab.SETTINGS  -> SettingsScreen()
                }
            }
        }
    }
}
