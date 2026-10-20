package tj.khujand.solana.trading.bot.core.strategy

import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.BaseToken
import tj.khujand.solana.trading.bot.exchange.dex.SplMintInfo
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.exchange.dex.TokenPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Мем-плейбук» поверх детектора: фильтр ликвидности, on-chain проверки (инъекция источников),
 * кеш проверок и сборка [Signal]. Сам детектор покрыт MemeFibDetectorTest.
 */
class MemePlaybookStrategyTest {

    private val t0 = 1_700_000_000_000L

    private val candidate = TokenCandidate(
        mint = "MINT", symbol = "FROG", name = "Frog", pairAddress = "POOL", dexId = "pumpswap",
        priceUsd = 1.60, liquidityUsd = 40_000.0, marketCap = 800_000.0, volumeH1Usd = 10_000.0,
        buysH1 = 10, sellsH1 = 5, priceChangeM5 = 0.0, priceChangeH1 = 0.0,
        tokenAgeMinutes = 120L, score = 0.0, scannedAt = t0,
    )

    private val cfg = StrategyConfig(id = "mp", type = StrategyType.MEME_PLAYBOOK.name, stopLossPercent = 25.0)

    private val clean = SplMintInfo(hasMintAuthority = false, hasFreezeAuthority = false, decimals = 6, supply = 1)

    private fun pair(mint: String, createdAt: Long) =
        TokenPair(chainId = "solana", baseToken = BaseToken(address = mint, symbol = "FROG"), pairCreatedAt = createdAt)

    private fun strategy(
        mintInfo: SplMintInfo? = clean,
        pairs: List<TokenPair>? = emptyList(),
        config: StrategyConfig = cfg,
    ) = MemePlaybookStrategy(config, mintInfoSource = { mintInfo }, symbolPairsSource = { pairs })

    @Test fun entersCleanOriginalToken() = runBlocking {
        val signal = strategy().analyze(candidate, MemeFixtures.setup(), emptyList())
        assertNotNull(signal)
        assertEquals(1.60, signal.entryPrice)
        assertTrue(signal.stopLoss in 1.25..1.39, "структурный стоп ${signal.stopLoss}")
        assertTrue(signal.confidence >= 0.6, "проходит общий порог уверенности")
        assertTrue(signal.reason.startsWith("Мем-плейбук"), signal.reason)
    }

    @Test fun rejectsLiveMintOrFreezeAuthority() = runBlocking {
        assertNull(strategy(mintInfo = clean.copy(hasMintAuthority = true)).analyze(candidate, MemeFixtures.setup(), emptyList()))
        assertNull(strategy(mintInfo = clean.copy(hasFreezeAuthority = true)).analyze(candidate, MemeFixtures.setup(), emptyList()))
    }

    @Test fun failsClosedWithoutNetworkData() = runBlocking {
        assertNull(strategy(mintInfo = null).analyze(candidate, MemeFixtures.setup(), emptyList()))
        assertNull(strategy(pairs = null).analyze(candidate, MemeFixtures.setup(), emptyList()))
    }

    @Test fun rejectsCopycat() = runBlocking {
        // Монета с тем же тикером создана за сутки до нашей (наша — 2 ч назад от скана).
        val older = listOf(pair("OTHER", t0 - 24 * 3_600_000L))
        assertNull(strategy(pairs = older).analyze(candidate, MemeFixtures.setup(), emptyList()))
    }

    @Test fun youngerNamesakeDoesNotBlock() = runBlocking<Unit> {
        val younger = listOf(pair("MINT", t0 - 2 * 3_600_000L), pair("OTHER", t0 - 3_600_000L))
        assertNotNull(strategy(pairs = younger).analyze(candidate, MemeFixtures.setup(), emptyList()))
    }

    @Test fun rejectsThinPool() = runBlocking {
        // 1% капитализации < 3% по умолчанию.
        assertNull(strategy().analyze(candidate.copy(liquidityUsd = 8_000.0), MemeFixtures.setup(), emptyList()))
    }

    @Test fun checksCanBeDisabled() = runBlocking<Unit> {
        val off = cfg.copy(mpRequireRevokedAuthorities = false, mpRejectCopycats = false)
        assertNotNull(strategy(mintInfo = null, pairs = null, config = off).analyze(candidate, MemeFixtures.setup(), emptyList()))
    }

    @Test fun networkChecksRunOncePerMintAndOnlyForSetups() = runBlocking {
        var rpcCalls = 0
        var searchCalls = 0
        val s = MemePlaybookStrategy(
            cfg,
            mintInfoSource = { rpcCalls++; clean },
            symbolPairsSource = { searchCalls++; emptyList() },
        )
        assertNull(s.analyze(candidate, MemeFixtures.impulse(), emptyList())) // сетапа нет — в сеть не ходим
        assertEquals(0, rpcCalls)
        assertNotNull(s.analyze(candidate, MemeFixtures.setup(), emptyList()))
        assertNotNull(s.analyze(candidate, MemeFixtures.setup(), emptyList()))
        assertEquals(1, rpcCalls)
        assertEquals(1, searchCalls)
    }

    @Test fun factoryCreatesStrategy() {
        assertTrue(createStrategy(cfg) is MemePlaybookStrategy)
    }
}
