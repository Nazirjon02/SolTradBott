package tj.khujand.solana.trading.bot.core.strategy

import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalyzerService
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Юнит-тест стратегии «Вход по баллу». Анализатор инъектируется с пустым источником свечей,
 * поэтому балл детерминирован (0), а проверяется именно логика ГЕЙТА по порогу и сборка [Signal].
 */
class ScoreStrategyTest {

    private val candidate = TokenCandidate(
        mint = "MINT", symbol = "TEST", name = "Test", pairAddress = "POOL", dexId = "raydium",
        priceUsd = 1.0, liquidityUsd = 0.0, marketCap = 0.0, volumeH1Usd = 0.0,
        buysH1 = 0, sellsH1 = 0, priceChangeM5 = 0.0, priceChangeH1 = 0.0,
        tokenAgeMinutes = 0L, score = 0.0, scannedAt = 0L,
    )

    private fun strategy(threshold: Int) = ScoreStrategy(
        config = StrategyConfig(id = "s", type = StrategyType.SCORE.name, scoreThreshold = threshold),
        analyzer = MarketAnalyzerService(candleSource = { _, _, _, _ -> emptyList() }),
    )

    @Test fun isSelfGated() {
        // Порог балла — единственный гейт: общий MIN_CONFIDENCE к стратегии не применяется.
        assertTrue(strategy(50).selfGated)
    }

    @Test fun entersWhenScoreMeetsThreshold() = runBlocking {
        // Порог 0 → любой балл (0..100) проходит → сигнал есть, поля входа заполнены корректно.
        val signal = strategy(0).analyze(candidate, emptyList(), emptyList())
        assertNotNull(signal)
        assertEquals("MINT", signal.mint)
        assertEquals(1.0, signal.entryPrice)
        assertTrue(signal.stopLoss < signal.entryPrice, "стоп ниже входа")
        assertTrue(signal.takeProfit > signal.entryPrice, "тейк выше входа")
    }

    @Test fun skipsWhenScoreBelowThreshold() = runBlocking {
        // Порог 1 при балле 0 → вход отклонён.
        val signal = strategy(1).analyze(candidate, emptyList(), emptyList())
        assertNull(signal)
    }

    @Test fun skipsWhenPriceMissing() = runBlocking {
        // Без цены вход невозможен даже при пороге 0.
        val signal = strategy(0).analyze(candidate.copy(priceUsd = 0.0), emptyList(), emptyList())
        assertNull(signal)
    }
}
