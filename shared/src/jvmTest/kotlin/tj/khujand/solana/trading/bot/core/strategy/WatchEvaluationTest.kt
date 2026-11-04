package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate

/** Разбор стратегии для доски наблюдения отдаёт тот же сигнал, что и вход, и цифры недостающего условия. */
class WatchEvaluationTest {

    private val cfg = StrategyConfig(id = "vb", name = "Пробой на объёме", timeframe = "5m", stopLossPercent = 15.0)

    private fun candidate() = TokenCandidate(
        mint = "mint", symbol = "MEME", name = "Meme", pairAddress = "pool", dexId = "raydium",
        priceUsd = 1.36, liquidityUsd = 1e5, marketCap = 1e6, volumeH1Usd = 1e4, buysH1 = 0, sellsH1 = 0,
        priceChangeM5 = 0.0, priceChangeH1 = 0.0, tokenAgeMinutes = 600, score = 0.0, scannedAt = 0L,
    )

    @Test
    fun volumeBreakoutEvaluationMatchesAnalyze() = runBlocking {
        val strategy = VolumeBreakoutStrategy(cfg, nowMs = { Long.MAX_VALUE })
        val ready = strategy.evaluate(candidate(), VolumeBreakoutFixtures.setup(), emptyList())
        assertEquals(VolumeBreakoutDetector.STAGES, ready.passed)
        assertNull(ready.missing)
        assertEquals(ready.signal, strategy.analyze(candidate(), VolumeBreakoutFixtures.setup(), emptyList()))

        val f = VolumeBreakoutFixtures
        val quiet = f.accumulation() + f.impulse() + f.pullback() + f.retest() + f.breakout(volume = 200.0)
        val partial = strategy.evaluate(candidate(), quiet, emptyList())
        assertEquals(5, partial.passed)
        assertTrue(partial.missing!!.contains("объём пробоя"), partial.missing)
        assertEquals(1.32, partial.entryLevel!!, 1e-9)
    }
}
