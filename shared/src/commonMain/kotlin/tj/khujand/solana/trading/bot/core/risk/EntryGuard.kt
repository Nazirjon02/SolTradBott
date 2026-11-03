package tj.khujand.solana.trading.bot.core.risk

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.exchange.dex.LAMPORTS_PER_SOL
import tj.khujand.solana.trading.bot.exchange.dex.QuoteResult
import tj.khujand.solana.trading.bot.exchange.dex.SOL_MINT
import tj.khujand.solana.trading.bot.exchange.dex.SplMintInfo
import tj.khujand.solana.trading.bot.exchange.dex.SwapQuote
import tj.khujand.solana.trading.bot.exchange.dex.SwapQuotes

/** Сколько помним отказ по свойствам mint-аккаунта (authority, Token-2022): они почти не меняются. */
private const val STRUCTURAL_REJECT_TTL_MS = 30 * 60_000L

/** Сколько помним отказ по котировкам: ликвидность и маршруты меняются быстрее. */
private const val QUOTE_REJECT_TTL_MS = 5 * 60_000L

/** Предел кеша отказов, чтобы за месяцы работы он не рос бесконечно. */
private const val REJECT_CACHE_LIMIT = 500

/** Slippage котировки обратной продажи: на сумму `outAmount` он не влияет, только на порог исполнения. */
private const val PROBE_SELL_SLIPPAGE_BPS = 300

/**
 * Расширения Token-2022 (номер ExtensionType из spl-token-2022), при которых купленное могут отнять,
 * обложить комиссией или не дать продать.
 */
internal val RISKY_TOKEN2022_EXTENSIONS: Map<Int, String> = mapOf(
    1 to "комиссия за перевод",                 // TransferFeeConfig
    6 to "счета по умолчанию заморожены",       // DefaultAccountState
    9 to "токен непереводимый",                 // NonTransferable
    12 to "перманентный делегат",               // PermanentDelegate — может забрать токены у любого
    14 to "хук перевода",                       // TransferHook — программа может запретить продажу
    26 to "переводы можно поставить на паузу",  // Pausable
)

/**
 * Общая проверка монеты перед ЛЮБОЙ покупкой — для всех стратегий и режимов (DEMO, SHADOW, REAL).
 * Раньше authority проверял только «Мем-плейбук», а влияние на цену и возможность продажи — никто.
 *
 * 1) mint/freeze authority отозваны (иначе монету могут допечатать или заморозить наши токены);
 * 2) нет опасных расширений Token-2022 ([RISKY_TOKEN2022_EXTENSIONS]);
 * 3) котировка покупки на наш размер: влияние на цену ≤ [ProtectionSettings.maxPriceImpactPct];
 * 4) котировка обратной продажи купленного количества есть (нет маршрута — honeypot или пул без выхода),
 *    и потери «купить и сразу продать» ≤ [ProtectionSettings.maxRoundTripLossPct].
 *
 * Нет данных (RPC, сеть, лимит Jupiter) — вход отклоняется (fail-closed), но такой отказ не запоминается.
 * Отказ по свойствам монеты помним 30 мин, по котировкам — 5: стратегия видит сетап каждые 30 с,
 * а запросы к Jupiter без ключа ограничены 0.5 в секунду.
 */
class EntryGuard(
    private val quotes: SwapQuotes,
    private val settings: () -> ProtectionSettings,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    sealed interface Verdict {
        /**
         * Входить можно. [buyQuote] — котировка покупки на этот размер (null — проверка выключена):
         * SHADOW и REAL исполняют по ней, не запрашивая заново.
         */
        data class Pass(val buyQuote: SwapQuote?, val roundTripLossPct: Double?) : Verdict

        /** Отказ; [cached] — причина взята из памяти (в ленту второй раз не пишем). */
        data class Reject(val reason: String, val cached: Boolean = false) : Verdict
    }

    private val lock = Mutex()
    private val rejected = mutableMapOf<String, Pair<String, Long>>() // mint → причина и «помнить до»

    /**
     * @param sizeUsd размер входа — котировки считаются на него (влияние на цену зависит от объёма).
     * @param buySlippageBps slippage покупки стратегии: REAL соберёт своп из этой же котировки.
     */
    suspend fun check(mint: String, sizeUsd: Double, buySlippageBps: Int): Verdict {
        val s = settings()
        if (!s.guardEnabled) return Verdict.Pass(buyQuote = null, roundTripLossPct = null)
        val now = nowMs()
        lock.withLock { rejected[mint] }?.let { (reason, until) ->
            if (now < until) return Verdict.Reject(reason, cached = true)
        }

        // 1–2. Mint-аккаунт.
        if (s.requireRevokedAuthorities || s.rejectRiskyToken2022) {
            val info = quotes.mintInfo(mint) ?: return Verdict.Reject("нет данных mint-аккаунта (RPC)")
            mintRejection(info, s)?.let { return remember(mint, it, STRUCTURAL_REJECT_TTL_MS) }
        }

        // 3. Покупка на наш размер.
        val solPrice = quotes.solPriceUsd()?.takeIf { it > 0 } ?: return Verdict.Reject("нет цены SOL")
        val lamports = (sizeUsd / solPrice * LAMPORTS_PER_SOL).toLong()
        if (lamports <= 0) return Verdict.Reject("нулевой размер входа")
        val buy = when (val r = quotes.quote(SOL_MINT, mint, lamports, buySlippageBps)) {
            is QuoteResult.Ok -> r.quote
            is QuoteResult.NoRoute -> return remember(mint, "Jupiter не нашёл маршрут покупки (${r.reason})", QUOTE_REJECT_TTL_MS)
            is QuoteResult.Failed -> return Verdict.Reject("котировка покупки недоступна: ${r.reason}")
        }
        if (buy.outAmount <= 0 || buy.inAmount <= 0) return remember(mint, "пустая котировка покупки", QUOTE_REJECT_TTL_MS)
        val impact = buy.priceImpactPct
        if (s.maxPriceImpactPct > 0 && impact != null && impact > s.maxPriceImpactPct) {
            return remember(mint, "влияние на цену ${fmt(impact)}% > ${fmt(s.maxPriceImpactPct)}%", QUOTE_REJECT_TTL_MS)
        }

        // 4. Обратная продажа всего купленного.
        val sell = when (val r = quotes.quote(mint, SOL_MINT, buy.outAmount, PROBE_SELL_SLIPPAGE_BPS)) {
            is QuoteResult.Ok -> r.quote
            is QuoteResult.NoRoute ->
                return remember(mint, "нет маршрута продажи — монету не продать (${r.reason})", QUOTE_REJECT_TTL_MS)
            is QuoteResult.Failed -> return Verdict.Reject("котировка продажи недоступна: ${r.reason}")
        }
        val loss = roundTripLossPct(buy.inAmount, sell.outAmount)
        if (s.maxRoundTripLossPct > 0 && loss > s.maxRoundTripLossPct) {
            return remember(mint, "потери «туда-обратно» ${fmt(loss)}% > ${fmt(s.maxRoundTripLossPct)}%", QUOTE_REJECT_TTL_MS)
        }
        return Verdict.Pass(buyQuote = buy, roundTripLossPct = loss)
    }

    private suspend fun remember(mint: String, reason: String, ttlMs: Long): Verdict.Reject {
        lock.withLock {
            if (rejected.size >= REJECT_CACHE_LIMIT) rejected.clear()
            rejected[mint] = reason to nowMs() + ttlMs
        }
        return Verdict.Reject(reason)
    }

    private fun fmt(v: Double): String = (kotlin.math.round(v * 100) / 100.0).toString()
}

/** Причина отказа по mint-аккаунту или null — монета проходит. */
internal fun mintRejection(info: SplMintInfo, s: ProtectionSettings): String? {
    if (s.requireRevokedAuthorities) {
        if (info.hasMintAuthority) return "mint authority не отозвана — могут допечатать"
        if (info.hasFreezeAuthority) return "freeze authority не отозвана — могут заморозить"
    }
    if (s.rejectRiskyToken2022) {
        val risky = info.extensions.mapNotNull { RISKY_TOKEN2022_EXTENSIONS[it] }
        if (risky.isNotEmpty()) return "Token-2022: ${risky.joinToString()}"
    }
    return null
}

/** Потери «купить на [spentLamports] и сразу продать за [backLamports]», % (отрицательные — выгода). */
internal fun roundTripLossPct(spentLamports: Long, backLamports: Long): Double =
    if (spentLamports <= 0) 100.0 else (1 - backLamports.toDouble() / spentLamports) * 100
