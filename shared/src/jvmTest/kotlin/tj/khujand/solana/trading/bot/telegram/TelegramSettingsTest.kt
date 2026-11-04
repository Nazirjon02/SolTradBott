package tj.khujand.solana.trading.bot.telegram

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/** Меню «🛡 Защита» в Telegram: кнопка переключает ровно свою настройку и не трогает остальные. */
class TelegramSettingsTest {

    private fun store(): SettingsStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DrxDatabase.Schema.create(driver)
        return SettingsStore(DrxDatabase(driver))
    }

    @Test
    fun togglesFlipOnlyTheirSetting() {
        val s = store()
        s.setProtection(ProtectionSettings(maxDailyLossPct = 7.0, maxDrawdownPct = 15.0))

        assertTrue(toggleSetting(s, "revexit")!!.contains("включён"))
        assertTrue(s.getProtection().reversalExitEnabled)
        toggleSetting(s, "autostop")
        toggleSetting(s, "guard")
        val p = s.getProtection()
        assertTrue(p.autoStopOnLimit)
        assertEquals(false, p.guardEnabled)
        // Числа лимитов из чата не меняются.
        assertEquals(7.0, p.maxDailyLossPct)
        assertEquals(15.0, p.maxDrawdownPct)

        toggleSetting(s, "revexit")
        assertEquals(false, s.getProtection().reversalExitEnabled)
    }

    @Test
    fun resumeOnLaunchIsStoredSeparately() {
        val s = store()
        assertEquals(false, s.getResumeOnLaunch())
        toggleSetting(s, "resume")
        assertTrue(s.getResumeOnLaunch())
        assertEquals(ProtectionSettings(), s.getProtection(), "защиту кнопка автозапуска не трогает")
    }

    @Test
    fun unknownKeyIsIgnored() {
        val s = store()
        assertNull(toggleSetting(s, "nope"))
        assertEquals(ProtectionSettings(), s.getProtection())
    }
}
