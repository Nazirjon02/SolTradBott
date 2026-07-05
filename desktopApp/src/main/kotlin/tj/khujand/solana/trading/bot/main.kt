package tj.khujand.solana.trading.bot

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime

fun main() = application {
    val windowState = rememberWindowState()

    Runtime.getRuntime().addShutdownHook(Thread {
        TradingRuntime.engineController().stopMonitoring()
    })

    Window(
        onCloseRequest = { windowState.isMinimized = true },
        title = "KotlinProject",
        state = windowState
    ) {
        App()
    }
}