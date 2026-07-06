package tj.khujand.solana.trading.bot

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

/**
 * Desktop-дашборд DRX. По умолчанию БД лежит в ~/.soltradbot/soltradbot.db;
 * чтобы делить базу с сервером — задай обоим одинаковый DB_PATH.
 */
fun main() {
    val dbPath = System.getenv("DB_PATH")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".soltradbot").also { it.mkdirs() }
            .resolve("soltradbot.db").absolutePath

    val runtime = DrxRuntimeHolder.init(dbPath)

    Runtime.getRuntime().addShutdownHook(Thread {
        runtime.shutdown()
    })

    application {
        val windowState = rememberWindowState(width = 480.dp, height = 920.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "DRX",
            state = windowState
        ) {
            App(runtime)
        }
    }
}
