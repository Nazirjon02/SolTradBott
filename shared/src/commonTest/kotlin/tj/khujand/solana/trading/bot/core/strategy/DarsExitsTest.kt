package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Пункт 5 методички: стоп «за структурой» + гейт риск/прибыль.
 * config по умолчанию: stopLossPercent=15 (мех.стоп=85 при входе 100),
 * takeProfitPercent=30 (мех.тейк=130).
 */
class DarsExitsTest {

    private val cfg = StrategyConfig(id = "t")

    @Test
    fun goodRiskRewardPasses() {
        // Стоп под структурой (−10% → 90), цель у сопротивления +30% → 130. R:R=3.
        val e = resolveDarsExits(entry = 100.0, targetFrac = 0.30, stopFrac = 0.10, config = cfg, minRiskReward = DarsStrategy.MIN_RISK_REWARD)
        assertNotNull(e)
        assertEquals(90.0, e.stopLoss, 1e-9)  // 90 ближе входа, чем мех.85 → берём структурный
        assertEquals(130.0, e.takeProfit, 1e-9)
    }

    @Test
    fun tooCloseTargetRejected() {
        // Цель у сопротивления всего +2%, стоп −10% → R:R=0.2 < 1.5 → отказ (негативное матожидание).
        val e = resolveDarsExits(entry = 100.0, targetFrac = 0.02, stopFrac = 0.10, config = cfg, minRiskReward = DarsStrategy.MIN_RISK_REWARD)
        assertNull(e)
    }

    @Test
    fun wideStructuralStopClampedToBudget() {
        // Структура далеко (−25%), но конфиг разрешает риск максимум 15% → стоп зажимается к 85.
        // Тейк по механике (пробой к новым максимумам, targetFrac=null) = 130. R:R=30/15=2.
        val e = resolveDarsExits(entry = 100.0, targetFrac = null, stopFrac = 0.25, config = cfg, minRiskReward = DarsStrategy.MIN_RISK_REWARD)
        assertNotNull(e)
        assertEquals(85.0, e.stopLoss, 1e-9)   // зажат бюджетом риска (не рискуем 25%)
        assertEquals(130.0, e.takeProfit, 1e-9)
    }

    @Test
    fun noStructuralFallsBackToMechanicalStop() {
        val e = resolveDarsExits(entry = 100.0, targetFrac = 0.30, stopFrac = null, config = cfg, minRiskReward = DarsStrategy.MIN_RISK_REWARD)
        assertNotNull(e)
        assertEquals(85.0, e.stopLoss, 1e-9)
        assertEquals(130.0, e.takeProfit, 1e-9)
    }
}
