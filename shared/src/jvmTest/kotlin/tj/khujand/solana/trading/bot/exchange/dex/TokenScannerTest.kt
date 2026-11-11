package tj.khujand.solana.trading.bot.exchange.dex

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сканер как общий пул наблюдения: лучшие по баллу, свежие метрики без переоткрытия источников,
 * монета не пропадает при смене источника, осушенный пул выбывает. Источники и часы — подставные.
 */
class TokenScannerTest {

    private val t0 = 1_800_000_000_000L
    private var now = t0

    private class FakeSources : ScanSources {
        var discovered: List<String> = emptyList()
        val pairs = LinkedHashMap<String, TokenPair>()
        var extraPairs: List<TokenPair> = emptyList()
        var down = false
        var discoverCalls = 0
        val asked = ArrayList<List<String>>()

        override suspend fun discoverMints(): List<String> {
            discoverCalls++
            return discovered
        }

        override suspend fun pairsFor(mints: List<String>): PairsResult {
            asked += mints
            if (down) return PairsResult(emptyList(), emptySet())
            return PairsResult(mints.mapNotNull { pairs[it] } + extraPairs, mints.toSet())
        }
    }

    private val sources = FakeSources()
    private val cache = TokenCache(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { driver ->
            DrxDatabase.Schema.create(driver)
            DrxDatabase(driver)
        }
    )

    private fun scanner() = TokenScanner(cache, sources, nowMs = { now })

    private fun pair(
        mint: String,
        liquidity: Double = 50_000.0,
        price: String = "1.0",
        base: String = mint,
        pool: String = "pool-$mint",
    ) = TokenPair(
        chainId = "solana",
        dexId = "raydium",
        pairAddress = pool,
        baseToken = BaseToken(address = base, name = base, symbol = base),
        priceUsd = price,
        volume = Volume(h24 = 5_000_000.0, h1 = 10_000.0),
        liquidity = Liquidity(usd = liquidity),
        pairCreatedAt = t0 - 6 * 3_600_000L,
        marketCap = 500_000.0,
        fdv = 500_000.0,
        txns = Txns(h24 = BuySell(1_000, 900), h1 = BuySell(100, 50), m5 = BuySell(0, 0)),
        priceChange = PriceChange(h1 = 0.0),
    )

    private fun discover(vararg coins: TokenPair) {
        sources.discovered = coins.map { it.baseToken!!.address!! }
        coins.forEach { sources.pairs[it.baseToken!!.address!!] = it }
    }

    private val open = ScanFilters(filtersEnabled = false)

    @Test
    fun strategyGetsBestByScoreNotFirstFound() = runBlocking {
        // 12 монет, балл растёт с ликвидностью: лучшие — последние в выдаче источника.
        discover(*(1..12).map { pair("M$it", liquidity = 5_000.0 * it) }.toTypedArray())
        val top = scanner().scan(open.copy(maxCandidates = 3))
        assertEquals(listOf("M12", "M11", "M10"), top.map { it.mint })
    }

    @Test
    fun metricsAreRefreshedWithoutAskingSourcesAgain() = runBlocking {
        discover(pair("A"))
        val s = scanner()
        assertEquals(1.0, s.scan(open).single().priceUsd)

        sources.pairs["A"] = pair("A", price = "1.5")
        now += 10_000L
        assertEquals(1.0, s.scan(open).single().priceUsd, "метрики моложе 20 с — запроса нет")
        assertEquals(1, sources.asked.size)

        now += 15_000L
        assertEquals(1.5, s.scan(open).single().priceUsd, "стратегия видит свежую цену, а не цену первого скана")
        assertEquals(1, sources.discoverCalls, "источники новых монет опрашиваются раз в 5 минут")
    }

    @Test
    fun coinStaysWatchedWhenSourceStopsShowingIt() = runBlocking {
        discover(pair("A"))
        val s = scanner()
        s.scan(open)

        // Источник сменил выдачу: показывает только B. A остаётся под наблюдением.
        sources.discovered = listOf("B")
        sources.pairs["B"] = pair("B")
        now += TokenScanner.DISCOVERY_INTERVAL_MS
        assertEquals(setOf("A", "B"), s.scan(open).map { it.mint }.toSet())

        // Через два часа после последнего показа срок A выходит; B источник показывает до сих пор.
        now = t0 + TokenScanner.RETENTION_MS + 60_000L
        assertEquals(listOf("B"), s.scan(open).map { it.mint })
        assertEquals(listOf("B"), cache.all().map { it.mint }, "выбывшая монета убрана и из списка в БД")
    }

    @Test
    fun drainedPoolLeavesAtOnce() = runBlocking {
        discover(pair("A"), pair("B"))
        val s = scanner()
        s.scan(open)

        sources.pairs["A"] = pair("A", liquidity = 500.0)
        now += 25_000L
        assertEquals(listOf("B"), s.scan(open).map { it.mint })
        assertEquals(listOf("B"), cache.all().map { it.mint })
    }

    @Test
    fun pairWhereCoinIsQuoteTokenIsNotTheCoin() = runBlocking {
        // По монете A источник метрик вернул только пул, где A — котируемая: базовая там — чужая монета X.
        sources.discovered = listOf("A")
        sources.pairs["A"] = pair("A", base = "X")
        assertTrue(scanner().scan(open).isEmpty(), "ни A (её пары нет), ни X (её никто не находил) в пул не попадают")
    }

    @Test
    fun mostLiquidPoolOfTheCoinIsUsed() = runBlocking {
        discover(pair("A", liquidity = 10_000.0, pool = "thin"))
        sources.extraPairs = listOf(pair("A", liquidity = 80_000.0, pool = "deep"))
        assertEquals("deep", scanner().scan(open).single().pairAddress)
    }

    @Test
    fun everyStrategyFiltersTheSharedPoolItsOwnWay() = runBlocking {
        discover(pair("SMALL", liquidity = 5_000.0), pair("BIG", liquidity = 50_000.0))
        val s = scanner()
        assertEquals(listOf("BIG"), s.scan(ScanFilters(minLiquidityUsd = 10_000.0)).map { it.mint })
        assertEquals(setOf("SMALL", "BIG"), s.scan(open).map { it.mint }.toSet())
        assertEquals(1, sources.asked.size, "вторая стратегия не тратит запросы — пул общий")
    }

    @Test
    fun metricsSourceOutageKeepsThePool() = runBlocking {
        discover(pair("A"))
        val s = scanner()
        s.scan(open)
        sources.down = true
        now += 25_000L
        assertEquals(listOf("A"), s.scan(open).map { it.mint }, "сбой DexScreener — не повод считать монету мёртвой")
    }

    @Test
    fun poolSurvivesRestart() = runBlocking {
        discover(pair("A"))
        scanner().scan(open)

        // Новый процесс: источники пока ничего не показали, но метрики по A отдают.
        sources.discovered = emptyList()
        now += 60_000L
        assertEquals(listOf("A"), scanner().scan(open).map { it.mint })
    }

    @Test
    fun majorsAndCoinsWithoutCreationDateAreSkipped() = runBlocking {
        val sol = "So11111111111111111111111111111111111111112"
        sources.discovered = listOf(sol, "NODATE")
        sources.pairs[sol] = pair(sol)
        sources.pairs["NODATE"] = pair("NODATE").copy(pairCreatedAt = null)
        assertTrue(scanner().scan(open).isEmpty())
        assertEquals(listOf(listOf("NODATE")), sources.asked, "SOL даже не запрашивается")
    }
}
