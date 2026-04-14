package tj.khujand.solana.trading.bot

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import tj.khujand.solana.trading.bot.ui.MainScreen
import tj.khujand.solana.trading.bot.ui.SolTradBotTheme

@Composable
@Preview
fun App() {
    SolTradBotTheme {
        MainScreen()
    }
}
