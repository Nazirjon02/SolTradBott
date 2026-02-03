package tj.khujand.solana.trading.bot

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState()

    Window(
        onCloseRequest = { windowState.isMinimized = true },
        title = "KotlinProject",
        state = windowState
    ) {
        App()
    }
}