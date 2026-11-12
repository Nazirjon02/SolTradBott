package tj.khujand.solana.trading.bot

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/**
 * Интерфейс в действии: тест сам нажимает на вкладки, поля и кнопки настоящего приложения с настоящим рантаймом
 * (база в памяти, движок не запускается — сеть не нужна) и проверяет, что изменилось на экране и в базе.
 */
@OptIn(ExperimentalTestApi::class)
class AppInteractionTest {

    private class Env(val runtime: DrxRuntime, val db: DrxDatabase)

    /** Приложение на телефоне (420×900) или в широком окне; [seed] — заранее сохранённая seed-фраза. */
    private fun appTest(
        width: Int = 420,
        height: Int = 900,
        seed: String? = null,
        block: suspend DesktopComposeUiTest.(Env) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        migrateStrategyTable(driver)
        val db = DrxDatabase(driver)
        seedDefaultStrategies(db)
        SettingsStore(db).apply {
            setDemoMode(true)
            if (seed != null) setWalletSeed(seed)
        }
        val env = Env(DrxRuntime(db), db)
        try {
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { DrxTheme { DrxApp(env.runtime) } }
                block(env)
            }
        } finally {
            env.runtime.shutdown()
        }
    }

    private fun DesktopComposeUiTest.openTab(screen: Screen) = onNodeWithTag(navTag(screen)).performClick()

    private fun DesktopComposeUiTest.field(label: String) = onNode(hasSetTextAction() and hasText(label))

    @Test
    fun tabsOpenTheirScreens() = appTest { _ ->
        onNodeWithText("DRX Bot").assertExists()
        // Движок стоит — карточка «Почему нет сделок» говорит об этом первой строкой.
        onNodeWithText("Бот остановлен — стратегии не ищут входы").assertExists()

        openTab(Screen.TRADES)
        onNodeWithText("История").assertExists()
        onNodeWithText("Аналитика").assertExists()

        openTab(Screen.STRATEGIES)
        onNodeWithContentDescription("Новая стратегия").assertExists()

        openTab(Screen.SETTINGS)
        onNodeWithText("Режим счёта").assertExists()

        openTab(Screen.ANALYSIS)
        onNodeWithText("Анализ монет").assertExists()

        openTab(Screen.DASHBOARD)
        onNodeWithText("DRX Bot").assertExists()
    }

    @Test
    fun wideWindowUsesSideRailAndTwoColumns() = appTest(width = 1280, height = 860) { _ ->
        onNodeWithText("стоп").assertExists() // статус бота в боковой панели

        openTab(Screen.TRADES)
        // История и аналитика стоят рядом — переключателя между ними нет, оба заголовка видны сразу.
        onNodeWithText("История").assertExists()
        onNodeWithText("Аналитика").assertExists()
        onNodeWithText("Кривая доходности").assertExists()

        openTab(Screen.SETTINGS)
        onNodeWithText("режим счёта и автоматика").assertExists() // список разделов слева
        onNodeWithText("Режим счёта").assertExists()
    }

    @Test
    fun settingsDraftIsKeptAcrossTabsAndSavedOnlyOnSave() = appTest { env ->
        val store = env.runtime.settingsStore
        assertEquals(3, store.getProtection().maxOpenPositions)

        openTab(Screen.SETTINGS)
        onNodeWithTag(settingsCategoryTag(SettingsCategory.PROTECTION)).performClick()
        field("Открытых позиций").performTextReplacement("7")

        onNodeWithText("Не сохранено").assertExists()
        assertEquals(3, store.getProtection().maxOpenPositions, "до «Сохранить» в базе прежнее значение")

        // Ушли на другую вкладку и вернулись — набранное на месте.
        openTab(Screen.DASHBOARD)
        openTab(Screen.SETTINGS)
        onNodeWithText("Не сохранено").assertExists()
        onNodeWithTag(settingsCategoryTag(SettingsCategory.PROTECTION)).performClick()
        field("Открытых позиций").assertTextContains("7")

        onNodeWithText("Сохранить").performClick()
        assertEquals(7, store.getProtection().maxOpenPositions)
        onNodeWithText("Не сохранено").assertDoesNotExist()
    }

    @Test
    fun invalidNumberBlocksSavingAndDiscardRestoresTheField() = appTest { env ->
        val store = env.runtime.settingsStore
        openTab(Screen.SETTINGS)
        onNodeWithTag(settingsCategoryTag(SettingsCategory.PROTECTION)).performClick()

        field("Открытых позиций").performTextReplacement("9")
        field("Открытых позиций").performTextReplacement("")
        onNodeWithText("Введите целое число").assertExists()
        onNodeWithText("есть поля с ошибкой").assertExists()
        onNodeWithText("Сохранить").performClick() // кнопка выключена — нажатие ничего не делает
        assertEquals(3, store.getProtection().maxOpenPositions)

        onNodeWithText("Отменить").performClick()
        onNodeWithText("Не сохранено").assertDoesNotExist()
        field("Открытых позиций").assertTextContains("3")
        assertEquals(3, store.getProtection().maxOpenPositions)
    }

    @Test
    fun realModeIsRefusedWithoutSeed() = appTest { env ->
        val store = env.runtime.settingsStore
        openTab(Screen.SETTINGS)
        onNodeWithText("REAL").performClick()

        onNodeWithText("Включить реальную торговлю?").assertExists()
        onNodeWithText("Включить REAL").performClick() // seed-фразы нет — кнопка выключена
        assertEquals(true, store.getDemoMode(), "без seed-фразы REAL не включается")

        onNodeWithText("Отмена").performClick()
        onNodeWithText("Включить реальную торговлю?").assertDoesNotExist()
        assertEquals(true, store.getDemoMode())
    }

    @Test
    fun realModeNeedsConfirmationAndOtherModesApplyAtOnce() = appTest(
        seed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    ) { env ->
        val store = env.runtime.settingsStore
        openTab(Screen.SETTINGS)

        onNodeWithText("REAL").performClick()
        assertEquals(true, store.getDemoMode(), "одно нажатие на REAL режим не меняет — сначала подтверждение")
        onNodeWithText("Включить REAL").performClick()
        assertEquals(false, store.getDemoMode())
        onNodeWithText("Реальные свопы через Jupiter с вашего кошелька — деньги настоящие.").assertExists()

        // Обратно в безопасные режимы — сразу, без вопросов.
        onNodeWithText("SHADOW").performClick()
        assertEquals(true, store.getDemoMode())
        assertTrue(store.getShadowQuotes())
    }

    @Test
    fun closedTradeAppearsWithoutSwitchingTabs() = appTest(height = 2600) { env ->
        onNodeWithText("Закрытых сделок пока нет").assertExists()

        val now = System.currentTimeMillis()
        env.db.tradeQueries.insert(
            id = "t1", strategy_id = "s1", strategy_name = "Пробой + откат", mint = "TestMint1111", symbol = "TESTCOIN",
            pair_address = "p", entry_price = 1.0, exit_price = 1.2, qty = 100.0, qty_remaining = 0.0, size_usd = 100.0,
            size_sol = 0.0, stop_loss = 0.9, take_profit = 1.3, peak_price = 1.2, tp1_done = 0, tp2_done = 0,
            entry_liquidity_usd = 0.0, pnl = 20.0, pnl_percent = 20.0, fee = null, status = "CLOSED", open_reason = "тест",
            close_reason = "Тейк", is_demo = 1, opened_at = now - 600_000, closed_at = now,
        )

        // Монитор закрыл сделку (здесь — запись в базу) — главная показывает её сама, вкладку менять не нужно.
        waitUntil("закрытая сделка на главной", 10_000) { onAllNodesWithText("TESTCOIN").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Закрытых сделок пока нет").assertDoesNotExist()
    }

    @Test
    fun deletingStrategyAsksFirst() = appTest { env ->
        val strategies = env.runtime.strategyStore
        val before = strategies.loadAll().size
        openTab(Screen.STRATEGIES)

        onAllNodesWithContentDescription("Удалить")[0].performClick()
        onNodeWithText("Отмена").performClick()
        assertEquals(before, strategies.loadAll().size, "«Отмена» стратегию не удаляет")

        onAllNodesWithContentDescription("Удалить")[0].performClick()
        onNodeWithText("Удалить").performClick()
        assertEquals(before - 1, strategies.loadAll().size)
    }
}
