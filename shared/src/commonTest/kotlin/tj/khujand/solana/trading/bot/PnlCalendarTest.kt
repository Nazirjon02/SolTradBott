package tj.khujand.solana.trading.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Календарь P&L: сетка месяца с понедельника и суммы по дням. */
class PnlCalendarTest {

    private fun trade(pnl: Double, closedAt: Long) = TradeHistoryItem(
        symbol = "T", strategy = "s", entryPrice = 1.0, exitPrice = 1.0, pnl = pnl, pnlPercent = pnl,
        sizeUsd = 100.0, isDemo = true, duration = "1м", isWin = pnl > 0, openReason = "", closeReason = "",
        closedAt = closedAt,
    )

    @Test
    fun monthGridStartsOnMonday() {
        // 1 ноября 2026 — воскресенье: шесть пустых клеток перед ним, 30 дней, сетка из целых недель.
        val cells = monthCells(2026, 11)
        assertEquals(42, cells.size)
        assertEquals(6, cells.indexOfFirst { it != null })
        assertEquals(LocalDate(2026, 11, 1), cells[6])
        assertEquals(LocalDate(2026, 11, 30), cells.last { it != null })
        assertNull(cells.last())
    }

    @Test
    fun daysInMonthKnowsLeapYears() {
        assertEquals(29, daysInMonth(2028, 2))
        assertEquals(28, daysInMonth(2026, 2))
        assertEquals(31, daysInMonth(2026, 12))
    }

    @Test
    fun dailyPnlSumsTradesOfTheSameDay() {
        val day = 86_400_000L
        val base = 20_000 * day // 4 октября 2024, 00:00 UTC
        val byDay = dailyPnl(
            listOf(trade(10.0, base + 1_000), trade(-4.0, base + day - 1), trade(7.0, base + day)),
            TimeZone.UTC,
        )
        assertEquals(DayPnl(6.0, 2), byDay[LocalDate(2024, 10, 4)])
        assertEquals(DayPnl(7.0, 1), byDay[LocalDate(2024, 10, 5)])
    }
}
