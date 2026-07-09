package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.test.Test
import kotlin.test.assertTrue

/** Урок 4: скоринг не должен тянуть наверх пампнутые монеты. */
class TokenScannerScoreTest {

    @Test
    fun calmTokenScoresHigherThanPumped() {
        // Одинаковые ликвидность/объём/давление покупок, разный часовой импульс.
        val calm = computeScanScore(liquidity = 100_000.0, volumeH1 = 50_000.0, buySellRatio = 1.5, changeH1 = 5.0)
        val pumped = computeScanScore(liquidity = 100_000.0, volumeH1 = 50_000.0, buySellRatio = 1.5, changeH1 = 120.0)
        assertTrue(calm > pumped, "Спокойный токен должен получать балл выше пампнутого ($calm ≤ $pumped)")
    }

    @Test
    fun sharpDumpIsPenalized() {
        val calm = computeScanScore(liquidity = 100_000.0, volumeH1 = 50_000.0, buySellRatio = 1.5, changeH1 = 0.0)
        val dumped = computeScanScore(liquidity = 100_000.0, volumeH1 = 50_000.0, buySellRatio = 1.5, changeH1 = -80.0)
        assertTrue(calm > dumped, "Резкое падение тоже должно снижать балл")
    }
}
