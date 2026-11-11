package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Расшифровка реальных транзакций (записаны 2026-11-11, `resources/chain`): уведомления logsSubscribe с
 * публичного узла и логи getTransaction в том же виде. Ожидаемые количества — из списка сделок GeckoTerminal
 * по тем же транзакциям (поля from/to_token_amount), т.е. независимый источник, а не пересказ кода.
 */
class SwapLogDecoderTest {

    private class Note(val slot: Long, val signature: String, val failed: Boolean, val logs: List<String>)

    private fun note(name: String): Note {
        val text = SwapLogDecoderTest::class.java.getResource("/chain/$name")!!.readText()
        val result = Json.parseToJsonElement(text).jsonObject["params"]!!.jsonObject["result"]!!.jsonObject
        val value = result["value"]!!.jsonObject
        return Note(
            slot = result["context"]!!.jsonObject["slot"]!!.jsonPrimitive.long,
            signature = value["signature"]!!.jsonPrimitive.content,
            failed = value["err"] !is JsonNull,
            logs = (value["logs"] as JsonArray).map { it.jsonPrimitive.content },
        )
    }

    private fun trades(name: String, pool: ChainPool): List<ChainTrade> {
        val n = note(name)
        return SwapLogDecoder.decode(n.logs).swaps.mapNotNull { it.toTrade(pool, n.signature, n.slot, 0, 0, true) }
    }

    private fun approx(expected: Double, actual: Double) =
        assertEquals(expected, actual, expected * 1e-9 + 1e-12, "ожидали $expected, получили $actual")

    // ─── PumpSwap ────────────────────────────────────────────────────────────

    private val hold = ChainPool(
        "GUsid5mPW6KiExjQDDggoTHrFv4ecGqqAgYwiHa4Ujbb", ChainDex.PUMPSWAP,
        "4iJ7xEQrUvVUQH9KVStvw3FaY2PZDd7RtR4L9zx2pump", WSOL_MINT, 6, 9,
        "4iJ7xEQrUvVUQH9KVStvw3FaY2PZDd7RtR4L9zx2pump", QuoteAsset.SOL,
    )
    private val payday = ChainPool(
        "9rQAUw4uNyfsqsJ9F3BPo3hBFY1V6ptaEtPkRhhH3zpL", ChainDex.PUMPSWAP,
        "uHMWNFjUNxbPy1q43WrdbzygmJwdeh6AiQm1NKdxPAY", WSOL_MINT, 6, 9,
        "uHMWNFjUNxbPy1q43WrdbzygmJwdeh6AiQm1NKdxPAY", QuoteAsset.SOL,
    )

    @Test
    fun `покупка PumpSwap — в пул вошло quote_amount_in_with_lp_fee, время блока из события`() {
        val n = note("pumpswap_buy.json")
        val swap = SwapLogDecoder.decode(n.logs).swaps.single()
        assertEquals(hold.address, swap.pool)
        assertEquals(1794402954L, swap.blockTimeSec)
        val t = assertNotNull(swap.toTrade(hold, n.signature, n.slot, 0, 0, true))
        assertTrue(t.isBuy)
        approx(96_338.618816, t.tokenAmount)
        approx(0.935661331, t.quoteAmount) // без комиссий протокола и создателя
    }

    @Test
    fun `buy_exact_quote_in — поля меняются местами, но берём то же, что GeckoTerminal`() {
        val t = trades("pumpswap_buy_exact_quote_in.json", payday).single()
        assertTrue(t.isBuy)
        approx(24_813.438671, t.tokenAmount) // GT: to_token_amount
        approx(0.097676377, t.quoteAmount) // GT: from_token_amount
    }

    @Test
    fun `продажа PumpSwap — пользователь получил user_quote_amount_out`() {
        val t = trades("pumpswap_sell_payday.json", payday).single()
        assertFalse(t.isBuy)
        approx(3_052.108366, t.tokenAmount) // GT: from_token_amount
        approx(0.011888097, t.quoteAmount) // GT: to_token_amount
        val s = trades("pumpswap_sell.json", hold).single()
        assertFalse(s.isBuy)
        approx(24_435.909937, s.tokenAmount)
        approx(0.236937633, s.quoteAmount)
    }

    @Test
    fun `многошаговый маршрут — берётся только сделка нашего пула`() {
        val n = note("pumpswap_multihop.json")
        val swaps = SwapLogDecoder.decode(n.logs).swaps
        assertEquals(2, swaps.size, "в транзакции две продажи PumpSwap в разных пулах")
        val t = swaps.mapNotNull { it.toTrade(payday, n.signature, n.slot, 0, 0, true) }.single()
        assertFalse(t.isBuy)
        approx(15_216.013545, t.tokenAmount)
        approx(0.059850865, t.quoteAmount)
    }

    // ─── Raydium ─────────────────────────────────────────────────────────────

    private val jeff = ChainPool(
        "JCXWfcZGjgJC8NZa5Qb6JP3cgvqW4vGUg4Tqj87G5ZnY", ChainDex.RAYDIUM_CPMM,
        WSOL_MINT, "HAsTKXYvv65zM7J6Cu5o5PcZsiKMQYo5oz4WPjgqwP6k", 9, 9,
        "HAsTKXYvv65zM7J6Cu5o5PcZsiKMQYo5oz4WPjgqwP6k", QuoteAsset.SOL,
    )
    private val spx = ChainPool(
        "9t1H1uDJ558iMPNkEPSN1fqkpC4XSPQ6cqSf6uEsTfTR", ChainDex.RAYDIUM_AMM_V4,
        "J3NKxxXZcnNiMjKw9hYb2K4LUxgwB6t1FtPtQVsv3KFr", WSOL_MINT, 8, 9,
        "J3NKxxXZcnNiMjKw9hYb2K4LUxgwB6t1FtPtQVsv3KFr", QuoteAsset.SOL,
        reserve0 = 254_345_253_420_013.0, reserve1 = 11_415_049_839_158.0,
    )
    private val ttwo = ChainPool(
        "6PTm7MkUmtsLU7w6dWrMHJAt8PnzoCgcZdKyTKEhUAPL", ChainDex.RAYDIUM_CLMM,
        "TTWofwAge91oFhZs7kpQdyrVRkmevgM88xijGvQFbKo", USDC_MINT, 6, 6,
        "TTWofwAge91oFhZs7kpQdyrVRkmevgM88xijGvQFbKo", QuoteAsset.USDC,
    )

    @Test
    fun `CPMM — SwapEvent с mint входа, монета на стороне token1`() {
        val buy = trades("cpmm_buy.json", jeff).single()
        assertTrue(buy.isBuy)
        approx(0.433297538, buy.quoteAmount)
        approx(26_456.758614791, buy.tokenAmount)
        val sell = trades("cpmm_sell.json", jeff).single()
        assertFalse(sell.isBuy)
        approx(19_465.773346268, sell.tokenAmount) // GT: from_token_amount
        approx(0.312429893, sell.quoteAmount) // GT: to_token_amount
    }

    @Test
    fun `AMM v4 — ray_log без адреса пула привязывается по резервам`() {
        val n = note("amm_v4_sell.json")
        val swap = SwapLogDecoder.decode(n.logs).swaps.single()
        assertNull(swap.pool)
        assertTrue(ammV4Match(swap, spx.reserve0, spx.reserve1))
        // Другой пул (резервы вдвое больше) — не наш.
        assertFalse(ammV4Match(swap, spx.reserve0 * 2, spx.reserve1 * 2))
        val t = assertNotNull(swap.toTrade(spx, n.signature, n.slot, 0, 0, false))
        assertFalse(t.isBuy) // direction 2: coin → pc
        approx(254.97213853, t.tokenAmount) // GT
        approx(1.143785715, t.quoteAmount) // GT
    }

    @Test
    fun `CLMM — zero_for_one задаёт направление, котировка USDC`() {
        val sell = trades("clmm_sell.json", ttwo).single()
        assertFalse(sell.isBuy)
        approx(0.754647, sell.tokenAmount)
        approx(171.950876, sell.quoteAmount)
        val buy = trades("clmm_buy.json", ttwo).single()
        assertTrue(buy.isBuy)
        approx(0.349127, buy.tokenAmount)
        approx(79.870246, buy.quoteAmount)
    }

    // ─── Шум и чужие события ─────────────────────────────────────────────────

    @Test
    fun `транзакция MEV-бота без вызова DEX — сделок нет`() {
        val d = SwapLogDecoder.decode(note("noise_probe.json").logs)
        assertTrue(d.swaps.isEmpty())
        assertFalse(d.truncated)
        assertEquals(0, d.unparsed)
    }

    @Test
    fun `транзакция с ошибкой помечена в уведомлении`() {
        assertTrue(note("failed_tx.json").failed)
    }

    @Test
    fun `SwapEvent чужой программы с тем же дискриминатором не читается`() {
        val d = SwapLogDecoder.decode(note("foreign_swap_event.json").logs)
        // Настоящие: своп CLMM чужого пула и покупка PumpSwap; два «SwapEvent» программы 2rwUXt… — мимо.
        assertEquals(listOf(ChainDex.RAYDIUM_CLMM, ChainDex.PUMPSWAP), d.swaps.map { it.dex })
        assertEquals(0, d.unparsed)
        val v4 = SwapLogDecoder.decode(note("amm_v4_with_foreign_event.json").logs)
        assertEquals(listOf(ChainDex.RAYDIUM_AMM_V4), v4.swaps.map { it.dex })
    }

    @Test
    fun `событие нужной программы внутри вложенного вызова чужой — не наше`() {
        val event = note("cpmm_buy.json").logs.first { it.startsWith("Program data: QMbN6CYIce") }
        val logs = listOf(
            "Program ${ChainDex.RAYDIUM_CPMM.programId} invoke [1]",
            "Program Other1111111111111111111111111111111111 invoke [2]",
            event,
            "Program Other1111111111111111111111111111111111 success",
            "Program ${ChainDex.RAYDIUM_CPMM.programId} success",
        )
        assertTrue(SwapLogDecoder.decode(logs).swaps.isEmpty())
        val own = listOf(logs[0], logs[1], logs[3], event, logs[4])
        assertEquals(1, SwapLogDecoder.decode(own).swaps.size)
    }

    @Test
    fun `обрезанные логи и событие с неверной арифметикой — учитываются, а не читаются молча`() {
        val buy = note("pumpswap_buy.json").logs
        val idx = buy.indexOfFirst { it.startsWith("Program data: Z/RSHyz1d3") }
        val bytes = java.util.Base64.getDecoder().decode(buy[idx].removePrefix("Program data: "))
        bytes[8 + 96] = (bytes[8 + 96] + 1).toByte() // quote_amount_in_with_lp_fee ≠ кривая + LP-комиссия
        val broken = buy.toMutableList().also {
            it[idx] = "Program data: " + java.util.Base64.getEncoder().encodeToString(bytes)
            it += "Log truncated"
        }
        val d = SwapLogDecoder.decode(broken)
        assertTrue(d.swaps.isEmpty())
        assertEquals(1, d.unparsed)
        assertTrue(d.truncated)
    }

    @Test
    fun `сделка чужого пула и чужой программы в пул не попадает`() {
        val n = note("pumpswap_buy.json")
        val swap = SwapLogDecoder.decode(n.logs).swaps.single()
        assertNull(swap.toTrade(payday, n.signature, n.slot, 0, 0, true), "другой пул")
        assertNull(swap.toTrade(jeff, n.signature, n.slot, 0, 0, true), "другая программа")
    }

    @Test
    fun `номер подписки из хвоста уведомления`() {
        val raw = """{"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"value":{"logs":[]}},"subscription":134854581}}"""
        assertEquals(134854581L, ChainCandleService.fastSubscriptionId(raw))
        assertNull(ChainCandleService.fastSubscriptionId("""{"id":1,"result":5}"""))
    }

    @Test
    fun `адрес узла — wss и хост без ключа`() {
        val url = "https://mainnet.helius-rpc.com/?api-key=SECRET"
        assertEquals("wss://mainnet.helius-rpc.com/?api-key=SECRET", ChainCandleService.wsUrlOf(url))
        assertEquals("mainnet.helius-rpc.com", ChainCandleService.hostOf(url))
        assertTrue(ChainCandleService.isHelius(url))
        assertNull(ChainCandleService.wsUrlOf(""))
    }
}
