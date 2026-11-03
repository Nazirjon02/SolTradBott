package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Wrapped SOL — вход покупки и выход продажи во всех котировках бота. */
const val SOL_MINT = "So11111111111111111111111111111111111111112"

const val LAMPORTS_PER_SOL = 1_000_000_000L

/**
 * Котировка свопа Jupiter.
 * @param inAmount / [outAmount] — сколько отдаём и получаем, в raw-единицах (lamports для SOL).
 * @param priceImpactPct влияние сделки на цену, % (0.5 = 0.5%); null — котировка его не сообщила.
 * @param raw исходный JSON — из него собирается своп.
 * @param fetchedAtMs когда получена: старую котировку не исполняем.
 */
data class SwapQuote(
    val inAmount: Long,
    val outAmount: Long,
    val priceImpactPct: Double?,
    val raw: JsonObject,
    val fetchedAtMs: Long,
)

/** Итог запроса котировки. */
sealed interface QuoteResult {
    data class Ok(val quote: SwapQuote) : QuoteResult

    /** Jupiter ответил, что маршрута нет (монета не торгуется / не продаётся на такой объём). */
    data class NoRoute(val reason: String) : QuoteResult

    /** Сеть, лимит запросов, 5xx — о монете это ничего не говорит. */
    data class Failed(val reason: String) : QuoteResult
}

/**
 * Котировки и сведения о монете, нужные проверке входа и исполнению (SHADOW и REAL).
 * Интерфейс — чтобы тесты подставляли ответы без сети.
 */
interface SwapQuotes {
    /** Цена SOL в USD; null — недоступна. */
    suspend fun solPriceUsd(): Double?

    suspend fun quote(inputMint: String, outputMint: String, amount: Long, slippageBps: Int): QuoteResult

    /** Mint-аккаунт монеты (authority, decimals, расширения Token-2022); null — RPC недоступен. */
    suspend fun mintInfo(mint: String): SplMintInfo?
}

/** Время жизни кеша mint-аккаунта: decimals не меняются, authority отзывают редко. */
private const val MINT_INFO_TTL_MS = 10 * 60_000L

/** Предел кеша mint-аккаунтов, чтобы за месяцы работы он не рос бесконечно. */
private const val MINT_INFO_CACHE_LIMIT = 500

/** Боевая реализация: котировки Jupiter, цена SOL (с кешем в [DexClient]) и mint-аккаунт через RPC. */
class JupiterSwapQuotes(private val client: DexClient) : SwapQuotes {
    private val lock = Mutex()
    private val mintInfoCache = mutableMapOf<String, Pair<SplMintInfo, Long>>()

    override suspend fun solPriceUsd(): Double? = client.getSolPriceUsd()

    override suspend fun quote(inputMint: String, outputMint: String, amount: Long, slippageBps: Int): QuoteResult =
        client.jupiter.getQuoteResult(inputMint, outputMint, amount, slippageBps)

    override suspend fun mintInfo(mint: String): SplMintInfo? {
        val now = Clock.System.now().toEpochMilliseconds()
        lock.withLock { mintInfoCache[mint] }?.let { (info, at) -> if (now - at < MINT_INFO_TTL_MS) return info }
        // Сбой сети не кешируем — следующий вызов спросит RPC снова.
        val info = client.rpc.getSplMintInfo(mint) ?: return null
        lock.withLock {
            if (mintInfoCache.size >= MINT_INFO_CACHE_LIMIT) mintInfoCache.clear()
            mintInfoCache[mint] = info to now
        }
        return info
    }
}

/**
 * Котировка Jupiter из JSON. Влияние на цену: новое поле `priceImpact` (процентные пункты) или
 * устаревшее `priceImpactPct` (доля: 0.01 = 1%). Знак отбрасываем — важна величина.
 * null — в ответе нет сумм.
 */
internal fun parseSwapQuote(raw: JsonObject, fetchedAtMs: Long): SwapQuote? {
    val inAmount = raw["inAmount"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    val outAmount = raw["outAmount"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    val impact = raw["priceImpact"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { abs(it) }
        ?: raw["priceImpactPct"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { abs(it) * 100 }
    return SwapQuote(inAmount, outAmount, impact, raw, fetchedAtMs)
}
