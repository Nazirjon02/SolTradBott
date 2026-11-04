package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.domain.backtest.Backtester
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/** Взведённый вход: план от детектора, решение по цене и вход в бэктесте по касанию уровня. */
class ArmedSetupTest {

    private val cfg = StrategyConfig(id = "bp", type = StrategyType.BREAKOUT_PULLBACK.name, bpRequireHtfTrend = false)

    /** Тот же сетап «Пробоя + отката», но последний бар ещё не прошёл максимум прошлого (1.36). */
    private fun waitingForTrigger(): List<Candle> = BreakoutFixtures.base() + BreakoutFixtures.impulse() +
        BreakoutFixtures.pullback() + Candle(31 * 300_000L, 1.32, 1.34, 1.31, 1.33, 90.0)

    @Test
    fun detectorArmsWhenOnlyTriggerIsMissing() {
        val r = assertIs<BreakoutPullbackResult.Reject>(BreakoutPullbackDetector.detect(waitingForTrigger(), cfg))
        assertEquals(6, r.stage, r.reason)
        val plan = assertNotNull(r.arm, "всё, кроме триггера, сошлось — план должен быть")
        assertEquals(1.36, plan.triggerLevel, 1e-9) // максимум прошлого бара
        assertEquals(1.36 * 1.03, plan.maxEntryPrice, 1e-9)
        assertTrue(plan.stopPrice < 1.30 && plan.stopPrice > 1.0, "стоп под минимумом отката: ${plan.stopPrice}")
        assertTrue(plan.invalidationPrice < 1.05, "точка слома — под уровнем пробоя: ${plan.invalidationPrice}")
    }

    @Test
    fun noPlanWhenStructureIsNotThere() {
        // Пробоя на объёме не было — взводить нечего.
        val candles = BreakoutFixtures.base() + BreakoutFixtures.impulse(volume = 110.0) +
            BreakoutFixtures.pullback(volume = 30.0) + Candle(31 * 300_000L, 1.32, 1.34, 1.31, 1.33, 30.0)
        assertNull(assertIs<BreakoutPullbackResult.Reject>(BreakoutPullbackDetector.detect(candles, cfg)).arm)
    }

    private val plan = TriggerPlan(
        triggerLevel = 1.10, maxEntryPrice = 1.133, invalidationPrice = 0.95, stopPrice = 1.00,
        confidence = 0.7, reason = "тест",
    )

    @Test
    fun priceDecidesWhatToDoWithArmedSetup() {
        assertEquals(ArmedAction.HOLD, armedAction(plan, 1.05))
        assertEquals(ArmedAction.FIRE, armedAction(plan, 1.10))
        assertEquals(ArmedAction.FIRE, armedAction(plan, 1.12))
        assertEquals(ArmedAction.OVERSHOOT, armedAction(plan, 1.20))
        assertEquals(ArmedAction.INVALID, armedAction(plan, 0.95))
    }

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = Candle(i * 300_000L, o, h, l, c, 100.0)

    @Test
    fun backtestFillsAtLevelWhenPriceCrossesIt() {
        val fill = assertNotNull(Backtester.armedFill(plan, listOf(bar(0, 1.05, 1.12, 1.03, 1.11)), 0, 3))
        assertEquals(0, fill.index)
        assertEquals(1.10, fill.price, 1e-9) // по уровню, а не по максимуму свечи
        assertEquals(false, fill.stoppedInBar)
    }

    @Test
    fun backtestRespectsBreakGapAndExpiry() {
        // Медвежья свеча прошла точку слома раньше уровня — взвод снят.
        assertNull(Backtester.armedFill(plan, listOf(bar(0, 1.05, 1.06, 0.94, 0.96)), 0, 3))
        // Гэп выше потолка цены — не догоняем.
        assertNull(Backtester.armedFill(plan, listOf(bar(0, 1.20, 1.25, 1.18, 1.22)), 0, 3))
        // Гэп выше уровня, но под потолком — вход по открытию.
        assertEquals(1.12, Backtester.armedFill(plan, listOf(bar(0, 1.12, 1.15, 1.11, 1.14)), 0, 3)!!.price, 1e-9)
        // Уровень так и не пройден за срок взвода.
        val flat = (0 until 5).map { bar(it, 1.05, 1.08, 1.02, 1.06) } + bar(5, 1.06, 1.15, 1.05, 1.14)
        assertNull(Backtester.armedFill(plan, flat, 0, 3))
    }

    @Test
    fun backtestNoticesStopInsideEntryBar() {
        // Медвежья свеча: открытие → максимум (вход по уровню) → минимум ниже стопа.
        val fill = assertNotNull(Backtester.armedFill(plan, listOf(bar(0, 1.05, 1.12, 0.99, 1.0)), 0, 3))
        assertTrue(fill.stoppedInBar)
    }
}
