package tj.khujand.solana.trading.bot

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.io.File
import java.util.Properties

/**
 * Desktop-дашборд DRX. По умолчанию БД лежит в ~/.soltradbot/soltradbot.db;
 * чтобы делить базу с сервером — задай обоим одинаковый DB_PATH.
 */
fun main() {
    val appDir = File(System.getProperty("user.home"), ".soltradbot").also { it.mkdirs() }
    val dbPath = System.getenv("DB_PATH")?.takeIf { it.isNotBlank() }
        ?: appDir.resolve("soltradbot.db").absolutePath

    val runtime = DrxRuntimeHolder.init(dbPath)
    // Тумблер «Возобновлять торговлю при запуске» в настройках: бот снова в том состоянии, в котором его оставили.
    if (runtime.takeResumeOnLaunch()) runtime.restoreDesiredState()

    Runtime.getRuntime().addShutdownHook(Thread {
        runtime.shutdown()
    })

    val prefsFile = appDir.resolve("window.properties")
    val saved = loadWindowPrefs(prefsFile)

    application {
        // Окно размером с монитор ноутбука, а не с телефон: на широком окне вкладки слева, главная в две колонки.
        val windowState = rememberWindowState(
            size = saved?.size ?: DpSize(1280.dp, 860.dp),
            position = WindowPosition(Alignment.Center),
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        Window(
            onCloseRequest = {
                saveWindowPrefs(prefsFile, windowState)
                exitApplication()
            },
            title = "DRX — Solana trading bot",
            state = windowState,
        ) {
            // Уже 400 dp интерфейс не вмещает: на узком окне он и так раскладывается как на телефоне.
            LaunchedEffect(Unit) { window.minimumSize = Dimension(400, 600) }
            App(runtime)
        }
    }
}

private class WindowPrefs(val size: DpSize, val maximized: Boolean)

/** Размер окна с прошлого запуска; файла нет или он испорчен — null (окно по умолчанию). */
private fun loadWindowPrefs(file: File): WindowPrefs? = runCatching {
    if (!file.exists()) return null
    val p = Properties().apply { file.inputStream().use { load(it) } }
    val w = p.getProperty("width")?.toFloatOrNull() ?: return null
    val h = p.getProperty("height")?.toFloatOrNull() ?: return null
    WindowPrefs(DpSize(w.coerceAtLeast(400f).dp, h.coerceAtLeast(600f).dp), p.getProperty("maximized") == "true")
}.getOrNull()

private fun saveWindowPrefs(file: File, state: WindowState) {
    runCatching {
        val p = Properties()
        p.setProperty("width", state.size.width.value.toString())
        p.setProperty("height", state.size.height.value.toString())
        p.setProperty("maximized", (state.placement == WindowPlacement.Maximized).toString())
        file.outputStream().use { p.store(it, "DRX window") }
    }
}
