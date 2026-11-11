package tj.khujand.solana.trading.bot.exchange.chain

/** Wrapped SOL — котировка большинства мем-пулов. */
const val WSOL_MINT = "So11111111111111111111111111111111111111112"

/** USDC — вторая котировка, которую принимаем (цена в USD = цена в USDC). */
const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

/** Программы DEX, сделки которых прототип умеет читать из логов транзакций. */
enum class ChainDex(val programId: String, val label: String) {
    PUMPSWAP("pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA", "PumpSwap"),
    RAYDIUM_AMM_V4("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8", "Raydium AMM v4"),
    RAYDIUM_CPMM("CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C", "Raydium CPMM"),
    RAYDIUM_CLMM("CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK", "Raydium CLMM");

    companion object {
        private val byId = entries.associateBy { it.programId }

        fun byProgram(programId: String): ChainDex? = byId[programId]
    }
}

/** Валюта котировки пула. */
enum class QuoteAsset(val mint: String, val decimals: Int) {
    SOL(WSOL_MINT, 9),
    USDC(USDC_MINT, 6);

    companion object {
        fun byMint(mint: String): QuoteAsset? = entries.firstOrNull { it.mint == mint }
    }
}

/**
 * Пул из списка наблюдения, который прототип умеет расшифровывать.
 *
 * Стороны — в терминах программы: сторона 0 — base у PumpSwap, coin у AMM v4, token0 у CPMM/CLMM;
 * сторона 1 — quote / pc / token1. Монета из списка наблюдения может стоять на любой из них.
 *
 * @param reserve0/reserve1 остатки хранилищ при разборе пула — нужны только AMM v4: в его ray_log нет адреса
 *   пула, и своп привязывается к пулу по близости резервов.
 */
data class ChainPool(
    val address: String,
    val dex: ChainDex,
    val mint0: String,
    val mint1: String,
    val decimals0: Int,
    val decimals1: Int,
    val tokenMint: String,
    val quote: QuoteAsset,
    val reserve0: Double = 0.0,
    val reserve1: Double = 0.0,
) {
    /** Монета — сторона 0 (иначе — сторона 1, а котировка — сторона 0). */
    val tokenIsSide0: Boolean get() = mint0 == tokenMint
    val tokenDecimals: Int get() = if (tokenIsSide0) decimals0 else decimals1
}

/**
 * Своп из логов в единицах программы (без десятичных).
 *
 * Количества — по правилу GeckoTerminal (сверено с его списком сделок 2026-11-11): [amountIn] — сколько вошло
 * в пул, [amountOut] — сколько пул отдал пользователю. У PumpSwap это: покупка — `quote_amount_in_with_lp_fee`
 * (без комиссий протокола и создателя), продажа — `user_quote_amount_out` (за вычетом всех комиссий).
 *
 * Направление задаётся одним из двух способов: [side0In] — по ролям программы (сторона 0 вошла в пул), или
 * [inMint] — mint вошедшей стороны (так пишет событие CPMM).
 *
 * @param pool адрес пула из события; null — в логе его нет (ray_log AMM v4).
 * @param blockTimeSec время блока из события (у PumpSwap поле timestamp = Clock::unix_timestamp, то же, что
 *   getBlockTime); null — в событии времени нет.
 * @param reserve0/reserve1 резервы пула до сделки, если лог их сообщает (ray_log) — для привязки к пулу.
 */
data class RawSwap(
    val dex: ChainDex,
    val pool: String?,
    val amountIn: Double,
    val amountOut: Double,
    val side0In: Boolean? = null,
    val inMint: String? = null,
    val blockTimeSec: Long? = null,
    val reserve0: Double? = null,
    val reserve1: Double? = null,
)

/**
 * Сделка по пулу из списка наблюдения.
 *
 * @param timeMs время блока: точное ([timeExact], из события PumpSwap) или оценка по слоту ([SlotClock]).
 * @param order порядок сделки: слот и номер в порядке прихода — внутри слота порядок транзакций не сообщается,
 *   узел присылает уведомления в порядке блока.
 * @param tokenAmount монет с учётом десятичных; [quoteAmount] — SOL или USDC.
 */
data class ChainTrade(
    val pool: String,
    val signature: String,
    val slot: Long,
    val order: Long,
    val timeMs: Long,
    val timeExact: Boolean,
    val isBuy: Boolean,
    val tokenAmount: Double,
    val quoteAmount: Double,
    val quote: QuoteAsset,
) {
    /** Цена монеты в валюте котировки. */
    val priceQuote: Double get() = quoteAmount / tokenAmount
}

/** Итог разбора логов одной транзакции. */
data class DecodedLogs(
    val swaps: List<RawSwap>,
    /** Узел обрезал логи («Log truncated»): события, попавшие за обрез, потеряны. */
    val truncated: Boolean = false,
    /** События нужных программ, которые не удалось прочитать: формат сменился или запись битая. */
    val unparsed: Int = 0,
)

/**
 * Сделка пула [pool], если своп относится к нему. Для PumpSwap/CPMM/CLMM своп несёт адрес пула; ray_log AMM v4 —
 * нет, и такой своп относится к пулу, только если [ammV4Match] это подтвердил.
 *
 * @return null — своп чужого пула, пустой или не той программы.
 */
fun RawSwap.toTrade(
    pool: ChainPool,
    signature: String,
    slot: Long,
    order: Long,
    timeMs: Long,
    timeExact: Boolean,
): ChainTrade? {
    if (dex != pool.dex) return null
    if (this.pool != null && this.pool != pool.address) return null
    if (amountIn <= 0.0 || amountOut <= 0.0) return null
    val tokenIn = when {
        inMint != null -> {
            if (inMint != pool.mint0 && inMint != pool.mint1) return null
            inMint == pool.tokenMint
        }
        side0In != null -> side0In == pool.tokenIsSide0
        else -> return null
    }
    val tokenRaw = if (tokenIn) amountIn else amountOut
    val quoteRaw = if (tokenIn) amountOut else amountIn
    return ChainTrade(
        pool = pool.address,
        signature = signature,
        slot = slot,
        order = order,
        timeMs = timeMs,
        timeExact = timeExact,
        isBuy = !tokenIn,
        tokenAmount = tokenRaw / pow10(pool.tokenDecimals),
        quoteAmount = quoteRaw / pow10(pool.quote.decimals),
        quote = pool.quote,
    )
}

/**
 * Относится ли ray_log AMM v4 к пулу с резервами [known0]/[known1] (стороны 0/1): в логе нет адреса пула, а
 * транзакция может упоминать наш пул и при этом менять соседний (арбитраж, многошаговый маршрут). Резервы
 * до сделки из лога должны быть близки к последним известным — у другого пула они почти наверняка другие.
 */
fun ammV4Match(swap: RawSwap, known0: Double, known1: Double, tolerance: Double = AMM_V4_RESERVE_TOLERANCE): Boolean {
    val r0 = swap.reserve0 ?: return false
    val r1 = swap.reserve1 ?: return false
    if (known0 <= 0.0 || known1 <= 0.0 || r0 <= 0.0 || r1 <= 0.0) return false
    fun near(a: Double, b: Double) = a / b in (1.0 / tolerance)..tolerance
    return near(r0, known0) && near(r1, known1)
}

/** Во сколько раз резервы ray_log могут отличаться от последних известных, чтобы своп считался нашим. */
const val AMM_V4_RESERVE_TOLERANCE = 1.5

internal fun pow10(n: Int): Double {
    var r = 1.0
    repeat(n.coerceIn(0, 30)) { r *= 10.0 }
    return r
}
