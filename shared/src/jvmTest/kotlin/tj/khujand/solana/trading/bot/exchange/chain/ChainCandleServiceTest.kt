package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.Candle

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

    @Test
    fun `подписку, снятую узлом, не считаем покрытием и подписываемся заново`() = runBlocking {
        // PublicNode: подписка подтверждена, по ней не приходит ничего, через минуты — «Subscription timed out»
        // (формат сообщения — из записи 2026-11-12). Без разбора этого сообщения бар 13:15–13:20 считался бы полным:
        // покрытие открыто с 13:10, а сделок в нём на самом деле никто не прислал.
        var now = 1_794_402_600_000L // 13:10 UTC
        val diag = ArrayList<String>()
        val service = ChainCandleService(
            rpcUrl = { null },
            watchlist = { emptyList() },
            solPriceUsd = { null },
            geckoCandles = { _, _, _, _ -> emptyList() },
            dailyCreditLimit = { 25_000 },
            geckoSpacingMs = { 0L },
            onDiagnostic = { diag += it },
            nowMs = { now },
        )
        service.testSolPrice(100.0)
        service.testAddPool(payday, "PAYDAY", reqId = 7)
        service.testMessage("""{"jsonrpc":"2.0","result":777,"id":7}""")
        now = 1_794_403_500_000L // 13:25 — узел снимает подписку, по которой ничего не пришло
        service.testMessage(
            """{"jsonrpc":"2.0","method":"eth_subscription","params":{"error":{"code":-32000,""" +
                """"message":"Subscription timed out"},"subscription":777},"id":7}"""
        )
        assertTrue(diag.any { "узел снял подписку (PAYDAY): Subscription timed out" in it }, "диагностика: $diag")
        assertEquals(false, service.poolStats().single().subscribed)
        // По снятой подписке уведомления больше не принимаются (сделка в фикстуре — этого пула).
        service.testMessage(fixture("pumpswap_multihop.json", 777))
        assertEquals(0, service.poolStats().single().trades)

        // Повторная подписка — первый запрос сервиса (id 1); подтверждена в 13:40, сделки бара 13:15 дошли позже.
        now = 1_794_404_400_000L
        service.testMessage("""{"jsonrpc":"2.0","result":888,"id":1}""")
        now += 5_000
        service.testMessage(fixture("pumpswap_buy_exact_quote_in.json", 888))
        service.testMessage(fixture("pumpswap_sell_payday.json", 888))
        assertEquals(2, service.poolStats().single().trades)
        val bar = service.bars(payday.address, 5).single()
        assertEquals(1_794_402_900_000L, bar.candle.openTimeMs)
        assertEquals(BarSource.PARTIAL, bar.source) // подписки, которая донесла бы его сделки, не было
    }

    /** Сервис с подтверждённой подпиской на PAYDAY и тремя сделками бара 13:15–13:20 UTC 2026-11-11. */
    private suspend fun paydayWithTrades(clock: () -> Long, setNow: (Long) -> Unit, gecko: () -> List<Candle> = { emptyList() }): ChainCandleService {
        val service = ChainCandleService(
            rpcUrl = { null },
            watchlist = { emptyList() },
            solPriceUsd = { null },
            geckoCandles = { _, _, _, _ -> gecko() },
            dailyCreditLimit = { 25_000 },
            geckoSpacingMs = { 0L },
            nowMs = clock,
        )
        service.testSolPrice(100.0)
        setNow(1_794_402_890_000L)
        service.testAddPool(payday, "PAYDAY", reqId = 7)
        service.testMessage("""{"jsonrpc":"2.0","result":777,"id":7}""")
        setNow(1_794_402_892_000L)
        service.testMessage(fixture("pumpswap_buy_exact_quote_in.json", 777))
        service.testMessage(fixture("pumpswap_sell_payday.json", 777))
        service.testMessage(fixture("pumpswap_multihop.json", 777))
        return service
    }

    @Test
    fun `стратегиям — бары пула с живой подпиской поверх чужой истории`() = runBlocking {
        var now = 0L
        val service = paydayWithTrades({ now }, { now = it })
        val barOpen = 1_794_402_900_000L
        now = barOpen + 300_000 + 25_000 // бар закрыт и устоялся (20 с, как у GeckoTerminal)
        val closedBy = now - 20_000
        assertNull(
            service.closedBars(payday.address, 300_000, barOpen, 0.00039, closedBy),
            "с 13:14:52 от узла ни одного сообщения — соединение могло тихо умереть, сделки бара могли не дойти",
        )
        service.testMessage(fixture("noise_probe.json", 777)) // соединение живо
        val c = service.closedBars(payday.address, 300_000, barOpen, 0.00039, closedBy)!!.single()
        val buy = 0.097676377 / 24_813.438671 * 100
        val sell1 = 0.011888097 / 3_052.108366 * 100
        val sell2 = 0.059850865 / 15_216.013545 * 100
        assertEquals(barOpen, c.openTimeMs)
        assertEquals(0.00039, c.open, 1e-15) // close последнего бара GeckoTerminal перед отрезком
        assertEquals(maxOf(0.00039, buy, sell1, sell2), c.high, 1e-12)
        assertEquals(minOf(0.00039, buy, sell1, sell2), c.low, 1e-12)
        assertEquals(sell2, c.close, 1e-12)
        assertEquals((0.097676377 + 0.011888097 + 0.059850865) * 100, c.volume, 1e-9)
        // Подписка началась в 13:14:52 — бар 13:10 она не покрывает.
        assertNull(service.closedBars(payday.address, 300_000, barOpen - 300_000, 0.00039, closedBy))
        assertNull(service.closedBars("unknown", 300_000, barOpen, 0.00039, closedBy))
    }

    @Test
    fun `Raydium CLMM и пул без подтверждённой подписки стратегиям бары не дают`() = runBlocking {
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
        val clmm = payday.copy(address = "CLMMpool111111111111111111111111111111111111", dex = ChainDex.RAYDIUM_CLMM)
        val unconfirmed = payday.copy(address = "Pending1111111111111111111111111111111111111")
        service.testAddPool(clmm, "CLMM", reqId = 8)
        service.testAddPool(unconfirmed, "WAIT", reqId = 9)
        service.testMessage("""{"jsonrpc":"2.0","result":888,"id":8}""")
        now = 1_794_403_200_000L + 30_000
        service.testMessage(fixture("noise_probe.json", 888))
        val barOpen = 1_794_402_900_000L
        assertNull(service.closedBars(clmm.address, 300_000, barOpen, 1.0, now - 20_000), "CLMM не сверен — только GeckoTerminal")
        assertNull(service.closedBars(unconfirmed.address, 300_000, barOpen, 1.0, now - 20_000))
    }

    @Test
    fun `после остановки и нового запуска время простоя покрытием не считается`() = runBlocking {
        var now = 0L
        val service = paydayWithTrades({ now }, { now = it })
        now = 1_794_402_975_000L // 13:16:15 — последнее сообщение перед остановкой
        service.testMessage(fixture("noise_probe.json", 777))
        // Сервис выключили и в 13:40 включили снова: новое соединение сперва закрывает прежнее покрытие, подписка —
        // заново. Сделки 13:16–13:40 могли пройти мимо — бар 13:15 неполный, стратегиям его блокчейн не даёт.
        now = 1_794_404_400_000L
        service.testConnectionStarting()
        service.testResubscribe(payday.address, reqId = 21)
        service.testMessage("""{"jsonrpc":"2.0","result":999,"id":21}""")
        now += 30_000
        service.testMessage(fixture("noise_probe.json", 999))
        assertEquals(BarSource.PARTIAL, service.bars(payday.address, 5).single().source)
        assertNull(service.closedBars(payday.address, 300_000, 1_794_402_900_000L, 0.00039, now - 20_000))
    }

    @Test
    fun `сверка не считает лишними наши бары старше окна ответа GeckoTerminal`() = runBlocking {
        var now = 0L
        val barOpen = 1_794_402_900_000L
        // GeckoTerminal отдал окно с 13:20 (как последние 200 баров долго живущего пула): наш бар 13:15 — вне окна.
        val service = paydayWithTrades({ now }, { now = it }) {
            listOf(Candle(barOpen + 300_000, 0.004, 0.004, 0.004, 0.004, 5.0))
        }
        now = barOpen + 30 * 60_000L // 13:45: бары до 13:30 GeckoTerminal успел дописать
        val r = service.reconcileNow().single()
        assertEquals(0, r.chainOnly, "бар вне окна ответа — не «лишний»")
        assertEquals(0, r.matched)
        assertEquals(1, r.geckoOnly) // бар 13:20 у GeckoTerminal есть, подписка была жива, а сделок у нас нет
    }
}
