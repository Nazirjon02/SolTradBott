package tj.khujand.solana.trading.bot.domain.dars

import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.BaseToken
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Тесты оркестратора [DarsEntryEngine] через внедряемый источник свечей (без сети).
 * Проверяют тренд-гейт старшего ТФ (Урок 2): fail-closed при нехватке данных и запрет лонга
 * против нисходящего тренда.
 */
class DarsEntryEngineTest {

    private val token = TokenPair(
        chainId = "solana",
        dexId = "raydium",
        pairAddress = "pool-test",
        baseToken = BaseToken("mint-test", "Test", "TST"),
    )

    /** Достаточно свечей рабочего ТФ (импульс/коррекция), чтобы дойти до тренд-гейта. */
    private fun entryCandles(): List<Candle> =
        (0..40).map { i ->
            val close = 100.0 + (i % 8)
            Candle(i * 60_000L, open = close - 0.3, high = close + 0.4, low = close - 0.5, close = close, volume = 300.0)
        }

    private fun candle(i: Int, close: Double) =
        Candle(i * 3_600_000L, open = close - 0.3, high = close + 0.4, low = close - 0.5, close = close, volume = 300.0)

    @Test
    fun failClosedRejectsWhenHigherTfInsufficient() = runBlocking {
        // Старший ТФ отдаёт < 6 свечей → тренд неизвестен → fail-closed отклоняет.
        val engine = DarsEntryEngine(candleSource = { _, tf, _, _ ->
            if (tf == "minute") entryCandles() else List(3) { candle(it, 100.0) }
        })
        val settings = FilterSettings(darsFailClosed = true)
        val signal = engine.evaluate(token, settings)
        assertFalse(signal.passed, "При нехватке свечей старшего ТФ входа быть не должно")
    }

    @Test
    fun rejectsLongAgainstDowntrendHigherTf() = runBlocking {
        // Старший ТФ явно нисходящий (монотонное падение) → лонг запрещён (Урок 2).
        val engine = DarsEntryEngine(candleSource = { _, tf, _, _ ->
            if (tf == "minute") entryCandles()
            else (0..29).map { candle(it, 130.0 - it * 1.5) } // 130 → ~87
        })
        val settings = FilterSettings(darsRequireHtfTrend = true)
        val signal = engine.evaluate(token, settings)
        assertFalse(signal.passed, "Против нисходящего старшего ТФ лонга быть не должно")
    }
}
