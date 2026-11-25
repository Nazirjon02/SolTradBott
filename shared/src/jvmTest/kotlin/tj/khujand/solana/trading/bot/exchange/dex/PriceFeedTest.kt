package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Источник цены для решений: Jupiter — основной, DexScreener — запасной, возраст цены Jupiter — по отставанию
 * blockId монеты от blockId SOL в том же ответе. Сеть подменена [FakePriceSources], часы — [now].
 */
class PriceFeedTest {

    private var now = 1_000_000L

    /** Ответы источников: Jupiter — цена и слот по монете (SOL на слоте [solSlot]), DexScreener — цена и Age. */
    private class FakePriceSources(private val clock: () -> Long) : PriceSources {
        var jupiterUp = true
        var solSlot = 1_000L
        var cdnAgeMs = 0L
        val jupiter = mutableMapOf<String, JupiterTokenPrice>()
        val dex = mutableMapOf<String, Pair<Double, Long>>() // mint → цена, Age мс
        val jupiterCalls = mutableListOf<List<String>>()
        val dexCalls = mutableListOf<String>()

        override suspend fun jupiter(mints: List<String>): JupiterPriceBatch? {
            jupiterCalls += mints
            if (!jupiterUp) return null
            val tokens = mints.mapNotNull { m -> if (m == SOL_MINT) m to JupiterTokenPrice(100.0, solSlot) else jupiter[m]?.let { m to it } }
            return JupiterPriceBatch(tokens.toMap(), cdnAgeMs, clock())
        }

        override suspend fun dexScreener(mint: String): DexScreenerPrice? {
            dexCalls += mint
            return dex[mint]?.let { (p, age) -> DexScreenerPrice(p, age, clock()) }
        }
    }

    private val src = FakePriceSources { now }
    private val feed = PriceFeed(src) { now }

    @Test fun freshJupiterPriceIsUsedAndDexScreenerIsNotAsked() = runBlocking {
        src.jupiter["A"] = JupiterTokenPrice(2.0, blockId = 1_000L)
        src.dex["A"] = (2.2 to 5_000L)
        val p = assertNotNull(feed.freshPrice("A", ENTRY_MAX_PRICE_AGE_MS))
        assertEquals(PriceSource.JUPITER, p.source)
        assertEquals(2.0, p.priceUsd)
        assertTrue(src.dexCalls.isEmpty())
        assertTrue(SOL_MINT in src.jupiterCalls.single(), "в запрос добавляется SOL — опорный слот")
    }

    @Test fun jupiterWithoutPriceFallsBackToDexScreener() = runBlocking {
        // Jupiter монету знает, но цены не даёт (так было с Bullseye) — берём DexScreener.
        src.jupiter["A"] = JupiterTokenPrice(usdPrice = null, blockId = 900L)
        src.dex["A"] = (2.2 to 12_000L)
        val p = assertNotNull(feed.freshPrice("A", ENTRY_MAX_PRICE_AGE_MS))
        assertEquals(PriceSource.DEXSCREENER, p.source)
        assertEquals(2.2, p.priceUsd)
        assertEquals(12_000L, p.ageMs(now), "возраст DexScreener — его кеш Cloudflare")
    }

    @Test fun jupiterDownFallsBackToDexScreener() = runBlocking {
        src.jupiterUp = false
        src.dex["A"] = (2.2 to 0L)
        assertEquals(PriceSource.DEXSCREENER, feed.freshPrice("A", ENTRY_MAX_PRICE_AGE_MS)?.source)
    }

    @Test fun staleJupiterPriceFallsBackToDexScreener() = runBlocking {
        // Последняя сделка монеты на 200 слотов раньше SOL: 200 × 400 мс (номинал до калибровки) = 80 с > 60 с.
        src.jupiter["A"] = JupiterTokenPrice(2.0, blockId = 800L)
        src.dex["A"] = (2.1 to 3_000L)
        assertEquals(80_000L, feed.jupiterPrices(listOf("A")).getValue("A").ageMs(now))
        val p = assertNotNull(feed.freshPrice("A", ENTRY_MAX_PRICE_AGE_MS))
        assertEquals(PriceSource.DEXSCREENER, p.source)
        // Для открытой позиции порог мягче (2 мин): та же цена Jupiter ещё годится.
        assertEquals(PriceSource.JUPITER, feed.freshPrice("A", EXIT_MAX_PRICE_AGE_MS)?.source)
    }

    @Test fun noFreshPriceAnywhereGivesNull() = runBlocking {
        src.jupiterUp = false
        assertNull(feed.freshPrice("A", ENTRY_MAX_PRICE_AGE_MS), "оба источника молчат")
        src.dex["A"] = (2.0 to 90_000L) // кеш Cloudflare старше минуты
        assertNull(feed.freshPrice("A", ENTRY_MAX_PRICE_AGE_MS), "у DexScreener цена устарела")
    }

    @Test fun cdnAgeCountsAndPriceAgesWhileWeHoldIt() = runBlocking {
        src.cdnAgeMs = 4_000L
        src.jupiter["A"] = JupiterTokenPrice(2.0, blockId = 1_000L)
        val p = feed.jupiterPrices(listOf("A")).getValue("A")
        assertEquals(4_000L, p.ageMs(now))
        now += 57_000L
        assertTrue(!p.isFresh(now, ENTRY_MAX_PRICE_AGE_MS), "4 с в CDN + 57 с у нас = 61 с — уже старая для входа")
    }

    @Test fun batchIsOneRequestAndCachedForFiveSeconds() = runBlocking {
        listOf("A", "B", "C").forEach { src.jupiter[it] = JupiterTokenPrice(1.0, 1_000L) }
        assertEquals(3, feed.jupiterPrices(listOf("A", "B", "C", "A")).size)
        assertEquals(1, src.jupiterCalls.size, "все монеты — одним запросом")
        assertEquals(setOf("A", "B", "C", SOL_MINT), src.jupiterCalls.single().toSet())
        now += 4_000L
        feed.jupiterPrices(listOf("A", "B"))
        assertEquals(1, src.jupiterCalls.size, "в пределах 5 с — из кеша")
        now += 2_000L
        feed.jupiterPrices(listOf("A"))
        assertEquals(2, src.jupiterCalls.size)
    }

    @Test fun slotLengthIsCalibratedFromSol() = runBlocking {
        src.jupiter["A"] = JupiterTokenPrice(2.0, blockId = 1_000L)
        feed.jupiterPrices(listOf("A"))
        // Через 30 с SOL ушёл на 150 слотов вперёд → слот 200 мс; монета отстаёт на 100 слотов → 20 с.
        now += 30_000L
        src.solSlot = 1_150L
        src.jupiter["A"] = JupiterTokenPrice(2.0, blockId = 1_050L)
        val p = feed.jupiterPrices(listOf("A")).getValue("A")
        assertEquals(200.0, feed.slotDurationMs(), 1e-9)
        assertEquals(20_000L, p.ageMs(now))
    }

    @Test fun parsesJupiterPriceV3Response() {
        val body = """
            {"So11111111111111111111111111111111111111112":{"usdPrice":69.97,"blockId":473371870,"decimals":9},
             "5LDow":{"liquidity":50824.2,"usdPrice":0.0020258,"blockId":473371872},
             "DtrAr":{"liquidity":0.00035,"blockId":473361318},
             "Gone":null}
        """.trimIndent()
        val batch = assertNotNull(parsePriceBatch(body, cdnAgeMs = 2_000L, fetchedAtMs = 5L))
        assertEquals(0.0020258, batch.tokens.getValue("5LDow").usdPrice)
        assertEquals(473371872L, batch.tokens.getValue("5LDow").blockId)
        assertNull(batch.tokens.getValue("DtrAr").usdPrice, "монета без цены — usdPrice нет")
        assertTrue("Gone" !in batch.tokens)
        assertEquals(2_000L, batch.cdnAgeMs)
        assertNull(parsePriceBatch("not json", 0L, 0L))
    }
}
