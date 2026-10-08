package tj.khujand.solana.trading.bot.core.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig

class PositionSizeTest {

    private val riskCfg = StrategyConfig(
        id = "s",
        positionSize = 10.0,
        riskSizingEnabled = true,
        riskPerTradePercent = 1.0,
        maxPoolSharePercent = 1.0,
    )

    @Test
    fun percentOfBalanceWhenRiskSizingOff() {
        val cfg = StrategyConfig(id = "s", positionSize = 5.0)
        assertEquals(500.0, positionSizeUsd(10_000.0, cfg, entry = 1.0, stop = 0.9, liquidityUsd = 1_000.0), 1e-9)
    }

    @Test
    fun sizeFromRiskKeepsLossConstant() {
        // Риск 1% от 10 000 = 100 USD; стоп −8% → размер 1250 (потолок 10% = 1000 → 1000).
        assertEquals(1_000.0, positionSizeUsd(10_000.0, riskCfg, 1.0, 0.92, liquidityUsd = 0.0), 1e-9)
        // Стоп −12.5% → 100 / 0.125 = 800, ниже потолка.
        assertEquals(800.0, positionSizeUsd(10_000.0, riskCfg, 1.0, 0.875, liquidityUsd = 0.0), 1e-9)
    }

    @Test
    fun poolShareCapsSize() {
        // Пул $20k, не больше 1% → 200 USD, даже если риск позволяет больше.
        assertEquals(200.0, positionSizeUsd(10_000.0, riskCfg, 1.0, 0.875, liquidityUsd = 20_000.0), 1e-9)
    }

    @Test
    fun invalidStopFallsBackToCap() {
        assertEquals(1_000.0, positionSizeUsd(10_000.0, riskCfg, 1.0, 1.1, liquidityUsd = 0.0), 1e-9)
        assertEquals(1_000.0, positionSizeUsd(10_000.0, riskCfg, 0.0, 0.0, liquidityUsd = 0.0), 1e-9)
    }

    @Test
    fun zeroBalanceGivesZero() {
        assertEquals(0.0, positionSizeUsd(0.0, riskCfg, 1.0, 0.9, liquidityUsd = 0.0), 1e-9)
    }
}
