package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tj.khujand.solana.trading.bot.crypto.encodeBase58

/**
 * Пул из списка наблюдения сканера: что известно до разбора аккаунта.
 * @param activity объём за час, USD — порядок подписки (дешёвые пулы первыми, см. [CreditBudget]).
 */
data class WatchedPool(val address: String, val mint: String, val symbol: String, val dexId: String, val activity: Double = 0.0)

/** Аккаунт из getMultipleAccounts. */
data class RawAccount(val owner: String, val data: ByteArray)

/** Итог разбора пула: пул, который умеем читать, или причина, почему нет. */
sealed interface PoolResolution {
    data class Ok(val pool: ChainPool) : PoolResolution
    data class Skip(val reason: SkipReason) : PoolResolution
}

/** Почему пул не подписан. Тексты — для экрана настроек. */
enum class SkipReason(val text: String) {
    UNSUPPORTED_DEX("DEX не поддержан (Meteora, Orca…)"),
    NOT_FOUND("аккаунт пула не найден"),
    BAD_LAYOUT("не разобран аккаунт пула"),
    QUOTE("котировка не SOL/USDC"),
    EXPENSIVE("дорогой пул: шум MEV-ботов"),
    OVER_BUDGET("не помещается в лимит кредитов"),
    DAY_LIMIT("лимит кредитов на сутки исчерпан"),
    SUBSCRIBE_FAILED("узел отказал в подписке"),
}

/**
 * Разбор аккаунтов пулов: какая программа владеет пулом, какие у него mint, десятичные и хранилища.
 *
 * Смещения сверены с реальными аккаунтами 2026-11-11 (тестовые данные — `jvmTest/resources/chain`):
 *  - PumpSwap Pool: base_mint @43, quote_mint @75, хранилища @139/@171; десятичные — из mint-аккаунтов;
 *  - Raydium AMM v4: coin_decimals @32, pc_decimals @40 (u64), coin_vault @336, pc_vault @368, coin_mint @400,
 *    pc_mint @432; резервы — остатки хранилищ (нужны для привязки ray_log);
 *  - Raydium CPMM: token0_mint @168, token1_mint @200, mint0_decimals @331, mint1_decimals @332;
 *  - Raydium CLMM: token_mint0 @73, token_mint1 @105, mint_decimals0 @233, mint_decimals1 @234.
 */
@OptIn(ExperimentalEncodingApi::class)
object PoolLayouts {

    /** Предварительный разбор: что ещё нужно дочитать вторым запросом (десятичные mint, остатки хранилищ). */
    data class Partial(
        val address: String,
        val dex: ChainDex,
        val mint0: String,
        val mint1: String,
        val decimals0: Int?,
        val decimals1: Int?,
        val vault0: String? = null,
        val vault1: String? = null,
    ) {
        /** Аккаунты для второго запроса. */
        fun needs(): List<String> = buildList {
            if (decimals0 == null) add(mint0)
            if (decimals1 == null && QuoteAsset.byMint(mint1) == null) add(mint1)
            if (dex == ChainDex.RAYDIUM_AMM_V4) {
                vault0?.let(::add)
                vault1?.let(::add)
            }
        }
    }

    fun parsePool(address: String, acc: RawAccount): Partial? {
        val dex = ChainDex.byProgram(acc.owner) ?: return null
        val d = acc.data
        fun pk(o: Int) = encodeBase58(d.copyOfRange(o, o + 32))
        return when (dex) {
            ChainDex.PUMPSWAP -> if (d.size < 203) null else
                Partial(address, dex, pk(43), pk(75), null, QuoteAsset.byMint(pk(75))?.decimals, pk(139), pk(171))
            ChainDex.RAYDIUM_AMM_V4 -> if (d.size < 464) null else
                Partial(address, dex, pk(400), pk(432), u64(d, 32).toInt(), u64(d, 40).toInt(), pk(336), pk(368))
            ChainDex.RAYDIUM_CPMM -> if (d.size < 333) null else
                Partial(address, dex, pk(168), pk(200), d[331].toInt() and 0xFF, d[332].toInt() and 0xFF, pk(72), pk(104))
            ChainDex.RAYDIUM_CLMM -> if (d.size < 235) null else
                Partial(address, dex, pk(73), pk(105), d[233].toInt() and 0xFF, d[234].toInt() and 0xFF, pk(137), pk(169))
        }
    }

    /**
     * Окончательный пул: монета [tokenMint] на одной стороне, SOL или USDC — на другой.
     * @param extra аккаунты второго запроса: mint (десятичные @44) и хранилища (остаток @64).
     */
    fun finish(p: Partial, tokenMint: String, extra: Map<String, RawAccount>): PoolResolution {
        val quoteMint = when (tokenMint) {
            p.mint0 -> p.mint1
            p.mint1 -> p.mint0
            else -> return PoolResolution.Skip(SkipReason.BAD_LAYOUT)
        }
        val quote = QuoteAsset.byMint(quoteMint) ?: return PoolResolution.Skip(SkipReason.QUOTE)
        if (QuoteAsset.byMint(tokenMint) != null) return PoolResolution.Skip(SkipReason.QUOTE)
        fun decimals(mint: String, known: Int?): Int? =
            known ?: QuoteAsset.byMint(mint)?.decimals ?: extra[mint]?.data?.takeIf { it.size > 44 }?.let { it[44].toInt() and 0xFF }
        val d0 = decimals(p.mint0, p.decimals0) ?: return PoolResolution.Skip(SkipReason.BAD_LAYOUT)
        val d1 = decimals(p.mint1, p.decimals1) ?: return PoolResolution.Skip(SkipReason.BAD_LAYOUT)
        fun amount(vault: String?): Double =
            vault?.let { extra[it] }?.data?.takeIf { it.size >= 72 }?.let { u64(it, 64).toDouble() } ?: 0.0
        return PoolResolution.Ok(
            ChainPool(
                address = p.address,
                dex = p.dex,
                mint0 = p.mint0,
                mint1 = p.mint1,
                decimals0 = d0,
                decimals1 = d1,
                tokenMint = tokenMint,
                quote = quote,
                reserve0 = if (p.dex == ChainDex.RAYDIUM_AMM_V4) amount(p.vault0) else 0.0,
                reserve1 = if (p.dex == ChainDex.RAYDIUM_AMM_V4) amount(p.vault1) else 0.0,
            )
        )
    }

    /** Ответ getMultipleAccounts (encoding base64) → адрес → аккаунт; отсутствующие аккаунты пропускаются. */
    fun parseMultipleAccounts(addresses: List<String>, response: JsonElement): Map<String, RawAccount> {
        val values = response.jsonObject["result"]?.jsonObject?.get("value")?.jsonArray ?: return emptyMap()
        val out = HashMap<String, RawAccount>()
        for ((i, v) in values.withIndex()) {
            if (i >= addresses.size || v is JsonNull || v !is JsonObject) continue
            val owner = v["owner"]?.jsonPrimitive?.content ?: continue
            val b64 = v["data"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: continue
            val bytes = try {
                Base64.decode(b64)
            } catch (_: IllegalArgumentException) {
                continue
            }
            out[addresses[i]] = RawAccount(owner, bytes)
        }
        return out
    }
}
