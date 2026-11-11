package tj.khujand.solana.trading.bot

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import tj.khujand.solana.trading.bot.core.strategy.CycleReport
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/**
 * Оболочка приложения с настоящим рантаймом (база в памяти, сеть не нужна): подписка на таблицу сделок, воронка
 * входа, черновик настроек и навигация собираются вместе без падений — на телефоне и на широком окне.
 */
class AppShellRuntimeTest {

    @Test
    fun shellComposesWithRealRuntime() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        seedDefaultStrategies(db)
        val runtime = DrxRuntime(db)
        try {
            // Воронка получила цикл — на главной должна появиться карточка «Почему нет сделок».
            runtime.entryFunnel.record(CycleReport("seed", System.currentTimeMillis()).apply { coins += "A" })
            listOf(Triple("shell-runtime-phone", 780 to 1700, false), Triple("shell-runtime-desktop", 2160 to 1350, true))
                .forEach { (name, size, wide) ->
                    val scene = ImageComposeScene(size.first, size.second, Density(if (wide) 1.5f else 2f)) {
                        DrxTheme { DrxApp(runtime, forceWide = wide) }
                    }
                    val bytes = try {
                        scene.render()
                        scene.render(16_000_000L)
                        scene.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                    } finally {
                        scene.close()
                    }
                    File("build/design-render").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
                    assertTrue(bytes.size > 20_000, "$name нарисован")
                }
        } finally {
            runtime.shutdown()
        }
    }
}
