package tj.khujand.solana.trading.bot.core

import kotlinx.serialization.json.JsonObject
import tj.khujand.solana.trading.bot.exchange.dex.QuoteResult
import tj.khujand.solana.trading.bot.exchange.dex.SOL_MINT
import tj.khujand.solana.trading.bot.exchange.dex.SplMintInfo
import tj.khujand.solana.trading.bot.exchange.dex.SwapQuote
import tj.khujand.solana.trading.bot.exchange.dex.SwapQuotes

/** Mint с отозванными authority, 6 знаков — «чистая» монета по умолчанию. */
internal val CLEAN_MINT = SplMintInfo(hasMintAuthority = false, hasFreezeAuthority = false, decimals = 6, supply = 1_000_000_000_000L)

/**
 * Котировки Jupiter без сети. Покупка: [tokensPerLamport] raw-единиц токена за lamport. Продажа — по тому
 * же курсу назад, умноженному на [sellReturn] (0.98 = −2% на комиссиях и проскальзывании).
 * [buy] / [sell] — свой ответ вместо расчётного (нет маршрута, сбой сети).
 */
internal class FakeSwapQuotes(
    var solPrice: Double? = 100.0,
    var mint: SplMintInfo? = CLEAN_MINT,
    var tokensPerLamport: Double = 100.0,
    var sellReturn: Double = 0.98,
    var impactPct: Double? = 0.5,
    var buy: ((Long) -> QuoteResult)? = null,
    var sell: ((Long) -> QuoteResult)? = null,
    var nowMs: () -> Long = { System.currentTimeMillis() },
) : SwapQuotes {
    /** Журнал вызовов: "mint", "buy:<lamports>", "sell:<raw>@<slippageBps>". */
    val calls = mutableListOf<String>()

    override suspend fun solPriceUsd(): Double? = solPrice

    override suspend fun mintInfo(mint: String): SplMintInfo? {
        calls += "mint"
        return this.mint
    }

    override suspend fun quote(inputMint: String, outputMint: String, amount: Long, slippageBps: Int): QuoteResult {
        val isBuy = inputMint == SOL_MINT
        calls += if (isBuy) "buy:$amount" else "sell:$amount@$slippageBps"
        (if (isBuy) buy else sell)?.let { return it(amount) }
        val out = if (isBuy) amount * tokensPerLamport else amount / tokensPerLamport * sellReturn
        return QuoteResult.Ok(SwapQuote(amount, out.toLong(), impactPct, JsonObject(emptyMap()), nowMs()))
    }
}
