package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Сервис без сети: подтверждение подписки и настоящие уведомления (resources/chain) → сделки, бары, счётчики.
 * Соединения нет — сообщения подаются напрямую, как их отдал бы WebSocket.
 */
class ChainCandleServiceTest {

    private val payday = ChainPool(
        "9rQAUw4uNyfsqsJ9F3BPo3hBFY1V6ptaEtPkRhhH3zpL", ChainDex.PUMPSWAP,
        "uHMWNFjUNxbPy1q43WrdbzygmJwdeh6AiQm1NKdxPAY", WSOL_MINT, 6, 9,
        "uHMWNFjUNxbPy1q43WrdbzygmJwdeh6AiQm1NKdxPAY", QuoteAsset.SOL,
    )

    private fun fixture(name: String, sub: Long): String =
        ChainCandleServiceTest::class.java.getResource("/chain/$name")!!.readText()
            .replace("\"subscription\":0}", "\"subscription\":$sub}")

    @Test
    fun `уведомления превращаются в сделки и 5m-бар по правилу GeckoTerminal`() = runBlocking {
        // Сделки фикстур — 13:16:02–13:16:10 UTC 2026-11-11, бар 13:15–13:20 (1794402900 с).
        var now = 1_794_402_890_000L
        val service = ChainCandleService(
            rpcUrl = { null },
            watchlist = { emptyList() },
            solPriceUsd = { null },
            geckoCandles = { _, _, _, _ -> emptyList() },
            dailyCreditLimit = { 25_000 },
            geckoSpacingMs = { 0L },
            nowMs = { now },
        )
        service.testSolPrice(100.0)
        service.testAddPool(payday, "PAYDAY", reqId = 7)
        service.testMessage("""{"jsonrpc":"2.0","result":777,"id":7}""") // подписка подтверждена до бара
        now += 2_000
        service.testMessage(fixture("pumpswap_buy_exact_quote_in.json", 777))
        service.testMessage(fixture("pumpswap_sell_payday.json", 777))
        service.testMessage(fixture("pumpswap_multihop.json", 777))
        service.testMessage(fixture("noise_probe.json", 777)) // быстрый путь: без разбора JSON
        service.testMessage(fixture("failed_tx.json", 777))
        service.testMessage(fixture("pumpswap_buy.json", 999)) // чужая подписка — не наш пул

        val stat = service.poolStats().single()
        assertEquals(3, stat.trades)
        assertEquals(1, stat.noise)
        assertEquals(1, stat.failedTx)
        assertTrue(stat.bytes > 15_000)

        now = 1_794_403_200_000L + ChainBarSeries.CLOSE_LAG_MS // бар закрыт
        val bar = service.bars(payday.address, 5).single()
        assertEquals(BarSource.CHAIN, bar.source)
        assertEquals(3, bar.trades)
        val c = bar.candle
        assertEquals(1_794_402_900_000L, c.openTimeMs)
        // Цена в USD = SOL за монету × 100: покупка 0,097676377/24813,438671, продажи 0,011888097/3052,108366 и
        // 0,059850865/15216,013545 (последняя по времени — close).
        val buy = 0.097676377 / 24_813.438671 * 100
        val sell1 = 0.011888097 / 3_052.108366 * 100
        val sell2 = 0.059850865 / 15_216.013545 * 100
        assertEquals(buy, c.open, 1e-12)
        assertEquals(maxOf(buy, sell1, sell2), c.high, 1e-12)
        assertEquals(minOf(buy, sell1, sell2), c.low, 1e-12)
        assertEquals(sell2, c.close, 1e-12)
        assertEquals((0.097676377 + 0.011888097 + 0.059850865) * 100, c.volume, 1e-9)
        assertEquals(c, service.candles(payday.address, "5m").single())
    }
}
