package tj.khujand.solana.trading.bot.domain.dars

import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверяет сетевую обёртку [MarketAnalyzerService] через внедряемый источник свечей (без сети):
 * рыночный фон берётся из строки сканера, а отчёты сортируются по баллу (лучшая монета — первой).
 */
class MarketAnalyzerServiceTest {

    private val cfg = DarsConfig(
        higherTf = "hour", higherTfAggregate = 1,
        entryTf = "minute", entryTfAggregate = 5,
        candleLimit = 200,
        swingPivotPct = 1.5,
        requireHtfTrend = true,
        dominanceRatio = 1.5,
        minCorrectionLenPct = 70.0,
        rejectAtResistance = true,
        resistanceProximityPct = 2.0,
        minLegs = 4,
        failClosed = false,
        useImpulseCorrection = true,
        useTrendLevels = true,
        useFalseBreakout = true,
        useTriangle = true,
    )

    private fun cleanSetup(): List<Candle> {
        val closes = buildList {
            add(100.0)
            addAll(listOf(102.0, 104.0, 106.0, 108.0, 110.0, 112.0, 114.0, 116.0, 118.0, 120.0))
            addAll(listOf(119.0, 118.0, 117.0, 116.0, 115.0, 114.5, 113.5, 113.0))
            addAll(listOf(115.0, 117.0, 119.0, 121.0, 123.0, 125.0, 127.0, 129.0, 131.0, 133.0))
            addAll(listOf(132.0, 131.0, 130.0, 129.0, 128.0, 127.0, 126.0, 125.0))
            add(127.5)
        }
        return closes.mapIndexed { i, close ->
            val prev = if (i == 0) close else closes[i - 1]
            val up = close >= prev
            Candle(i * 300_000L, prev, maxOf(prev, close) * 1.002, minOf(prev, close) * 0.998, close, if (up) 1000.0 else 200.0)
        }
    }

    private fun uptrendHtf(): List<Candle> = (0..29).map { i ->
        val close = 80.0 + i * (50.0 / 29.0)
        Candle(i * 3_600_000L, close - 0.5, close + 0.6, close - 0.7, close, 500.0)
    }

    private fun flat(): List<Candle> = (0..40).map { i -> Candle(i * 300_000L, 100.0, 100.2, 99.8, 100.0, 100.0) }

    @Test
    fun ranksBestCoinFirst() = runBlocking {
        val service = MarketAnalyzerService { pool, tf, _, _ ->
            when {
                tf == "hour" -> uptrendHtf()          // восходящий старший ТФ для всех
                pool == "good" -> cleanSetup()         // чистый сетап
                else -> flat()                         // боковик → низкий балл
            }
        }
        val ranked = service.analyzeAll(listOf(candidate("flat", "FLAT"), candidate("good", "GOOD")), cfg, concurrency = 2)

        assertEquals(2, ranked.size)
        assertEquals("GOOD", ranked.first().symbol, "Лучшая по баллу монета должна идти первой")
        assertTrue(ranked.first().score >= ranked.last().score)
    }

    private fun candidate(pool: String, symbol: String) = TokenCandidate(
        mint = "$pool-mint", symbol = symbol, name = symbol, pairAddress = pool, dexId = "raydium",
        priceUsd = 127.5, liquidityUsd = 100_000.0, marketCap = 500_000.0, volumeH1Usd = 50_000.0,
        buysH1 = 100, sellsH1 = 50, priceChangeM5 = 1.0, priceChangeH1 = 8.0,
        tokenAgeMinutes = 600, score = 0.0, scannedAt = 0L,
    )
}
