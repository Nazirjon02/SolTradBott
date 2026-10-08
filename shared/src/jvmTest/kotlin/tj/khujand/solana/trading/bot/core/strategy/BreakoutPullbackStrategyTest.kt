package tj.khujand.solana.trading.bot.core.strategy

import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Стратегия «Пробой + откат» поверх детектора: гейт тренда старшего ТФ (инъекция свечей)
 * и сборка [Signal]. Сам детектор покрыт BreakoutPullbackDetectorTest.
 */
class BreakoutPullbackStrategyTest {

    private val candidate = TokenCandidate(
        mint = "MINT", symbol = "BP", name = "Breakout", pairAddress = "POOL", dexId = "raydium",
        priceUsd = 1.38, liquidityUsd = 50_000.0, marketCap = 500_000.0, volumeH1Usd = 10_000.0,
        buysH1 = 10, sellsH1 = 5, priceChangeM5 = 0.0, priceChangeH1 = 0.0,
        tokenAgeMinutes = 600L, score = 0.0, scannedAt = 0L,
    )

    private val cfg = StrategyConfig(id = "bp", type = StrategyType.BREAKOUT_PULLBACK.name)

    /** Старший ТФ: ровная лестница вверх (higher highs / higher lows). */
    private val htfUp = (0 until 30).map { i ->
        val base = 1.0 + i * 0.02 + if (i % 4 < 2) 0.0 else -0.015
        Candle(i * 900_000L, base, base + 0.01, base - 0.01, base + 0.005, 100.0)
    }

    private val htfDown = htfUp.reversed().mapIndexed { i, c -> c.copy(openTimeMs = i * 900_000L) }

    private fun strategy(htf: List<Candle>, config: StrategyConfig = cfg) =
        BreakoutPullbackStrategy(config, htfSource = { _, _, _ -> htf })

    @Test fun entersOnSetupInUptrend() = runBlocking {
        val signal = strategy(htfUp).analyze(candidate, BreakoutFixtures.setup(), emptyList())
        assertNotNull(signal)
        assertEquals(1.38, signal.entryPrice)
        assertTrue(signal.stopLoss < signal.entryPrice && signal.stopLoss > signal.entryPrice * 0.85, "структурный стоп ${signal.stopLoss}")
        assertTrue(signal.confidence >= 0.6, "проходит общий порог уверенности")
        assertTrue(signal.reason.startsWith("Пробой+откат"), signal.reason)
    }

    @Test fun rejectsAgainstHigherTimeframeDowntrend() = runBlocking {
        assertNull(strategy(htfDown).analyze(candidate, BreakoutFixtures.setup(), emptyList()))
    }

    @Test fun failsClosedWithoutHigherTimeframeData() = runBlocking {
        assertNull(strategy(emptyList()).analyze(candidate, BreakoutFixtures.setup(), emptyList()))
    }

    @Test fun trendGateCanBeDisabled() = runBlocking<Unit> {
        val signal = strategy(emptyList(), cfg.copy(bpRequireHtfTrend = false))
            .analyze(candidate, BreakoutFixtures.setup(), emptyList())
        assertNotNull(signal)
    }

    @Test fun noSetupNoSignal() = runBlocking {
        assertNull(strategy(htfUp).analyze(candidate, BreakoutFixtures.base(), emptyList()))
    }

    @Test fun factoryCreatesStrategy() {
        assertTrue(createStrategy(cfg) is BreakoutPullbackStrategy)
    }
}
