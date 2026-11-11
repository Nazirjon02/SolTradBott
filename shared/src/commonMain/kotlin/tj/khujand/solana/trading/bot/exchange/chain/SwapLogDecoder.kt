package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import tj.khujand.solana.trading.bot.crypto.encodeBase58

/**
 * Свопы из логов транзакции (то, что присылает `logsSubscribe`).
 *
 * Форматы сверены с реальными транзакциями и списком сделок GeckoTerminal 2026-11-11:
 *  - PumpSwap — Anchor-события BuyEvent/SellEvent строкой `Program data:` (программа пишет их и в лог, и
 *    self-CPI); в событии есть время блока и адрес пула;
 *  - Raydium AMM v4 — `Program log: ray_log: …` (bincode: SwapBaseIn/SwapBaseOut), адреса пула нет;
 *  - Raydium CPMM — Anchor SwapEvent с адресом пула и mint входа/выхода;
 *  - Raydium CLMM — Anchor SwapEvent с адресом пула и направлением zero_for_one.
 *
 * Событие принимается только от «своей» программы: дискриминатор Anchor-события зависит лишь от имени
 * («event:SwapEvent»), и такие же строки пишут чужие программы (в логах встречались). Какая программа
 * пишет строку — видно по стеку вызовов из строк `Program X invoke [n]` / `success` / `failed`.
 */
@OptIn(ExperimentalEncodingApi::class)
object SwapLogDecoder {
    private const val DATA = "Program data: "
    private const val RAY_LOG = "Program log: ray_log: "
    private const val TRUNCATED = "Log truncated"

    private val PUMP_BUY = hex("67f4521f2cf57777")
    private val PUMP_SELL = hex("3e2f370aa503dc2a")
    private val SWAP_EVENT = hex("40c6cde8260871e2")

    private const val RAY_SWAP_BASE_IN = 3
    private const val RAY_SWAP_BASE_OUT = 4
    private const val RAY_DIR_PC_TO_COIN = 1L
    private const val RAY_DIR_COIN_TO_PC = 2L

    fun decode(logs: List<String>): DecodedLogs {
        val stack = ArrayList<String>(8)
        val swaps = ArrayList<RawSwap>(2)
        var truncated = false
        var unparsed = 0
        for (line in logs) {
            when {
                line.startsWith(DATA) -> {
                    val dex = stack.lastOrNull()?.let { ChainDex.byProgram(it) } ?: continue
                    if (dex == ChainDex.RAYDIUM_AMM_V4) continue
                    when (val r = decodeData(dex, line.substring(DATA.length))) {
                        is Parse.Swap -> swaps += r.swap
                        Parse.Bad -> unparsed++
                        Parse.Other -> Unit
                    }
                }
                line.startsWith(RAY_LOG) -> {
                    if (stack.lastOrNull() != ChainDex.RAYDIUM_AMM_V4.programId) continue
                    when (val r = decodeRayLog(line.substring(RAY_LOG.length))) {
                        is Parse.Swap -> swaps += r.swap
                        Parse.Bad -> unparsed++
                        Parse.Other -> Unit
                    }
                }
                line == TRUNCATED -> truncated = true
                line.startsWith("Program ") -> trackStack(line, stack)
            }
        }
        return DecodedLogs(swaps, truncated, unparsed)
    }

    /** `Program <id> invoke [n]` — вход в программу; `Program <id> success|failed: …` — выход. */
    private fun trackStack(line: String, stack: MutableList<String>) {
        val idEnd = line.indexOf(' ', 8)
        if (idEnd < 0) return
        val rest = line.substring(idEnd + 1)
        when {
            rest.startsWith("invoke [") -> stack += line.substring(8, idEnd)
            rest == "success" || rest.startsWith("failed") -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
    }

    private sealed interface Parse {
        data class Swap(val swap: RawSwap) : Parse
        /** Нужное событие, но прочитать не удалось. */
        data object Bad : Parse
        /** Другое событие той же программы (депозит, создание пула…). */
        data object Other : Parse
    }

    private fun decodeData(dex: ChainDex, payload: String): Parse {
        // sol_log_data пишет срезы через пробел; Anchor — один срез.
        val bytes = b64(payload.substringBefore(' ')) ?: return Parse.Other
        if (bytes.size < 8) return Parse.Other
        return when (dex) {
            ChainDex.PUMPSWAP -> when {
                bytes.startsWith(PUMP_BUY) -> pumpSwap(bytes, buy = true)
                bytes.startsWith(PUMP_SELL) -> pumpSwap(bytes, buy = false)
                else -> Parse.Other
            }
            ChainDex.RAYDIUM_CPMM -> if (bytes.startsWith(SWAP_EVENT)) cpmm(bytes) else Parse.Other
            ChainDex.RAYDIUM_CLMM -> if (bytes.startsWith(SWAP_EVENT)) clmm(bytes) else Parse.Other
            ChainDex.RAYDIUM_AMM_V4 -> Parse.Other
        }
    }

    /**
     * BuyEvent/SellEvent PumpSwap. После дискриминатора: timestamp i64 @0, base_amount @8, …, quote-поля @56–@104,
     * pool @112. Смысл quote-полей у вариантов инструкции разный (у `buy_exact_quote_in` quote_amount_in и
     * user_quote_amount_in меняются местами), поэтому берём согласованные между вариантами поля и проверяем
     * арифметику — иначе формат сменился, и событие лучше не читать, чем прочитать неверно.
     */
    private fun pumpSwap(d: ByteArray, buy: Boolean): Parse {
        val o = 8
        if (d.size < o + 144) return Parse.Bad
        val ts = i64(d, o)
        val base = u64(d, o + 8)
        val q56 = u64(d, o + 56)
        val lpFee = u64(d, o + 72)
        val protocolFee = u64(d, o + 88)
        val q96 = u64(d, o + 96)
        val q104 = u64(d, o + 104)
        val pool = encodeBase58(d.copyOfRange(o + 112, o + 144))
        return if (buy) {
            // quote_amount_in_with_lp_fee = кривая + LP-комиссия; кривая — quote_amount_in (buy)
            // или user_quote_amount_in (buy_exact_quote_in). В пул вошло именно with_lp_fee.
            if (q96 < lpFee) return Parse.Bad
            val curve = q96 - lpFee
            if (curve != q56 && curve != q104) return Parse.Bad
            Parse.Swap(RawSwap(ChainDex.PUMPSWAP, pool, amountIn = q96.toDouble(), amountOut = base.toDouble(),
                side0In = false, blockTimeSec = ts))
        } else {
            // quote_amount_out − LP-комиссия = quote_amount_out_without_lp_fee; пользователь получил
            // user_quote_amount_out — за вычетом ещё комиссий протокола и создателя.
            if (q56 < lpFee || q56 - lpFee != q96) return Parse.Bad
            if (q104 + protocolFee > q96) return Parse.Bad
            Parse.Swap(RawSwap(ChainDex.PUMPSWAP, pool, amountIn = base.toDouble(), amountOut = q104.toDouble(),
                side0In = true, blockTimeSec = ts))
        }
    }

    /**
     * SwapEvent CPMM: pool_id @0, input_vault_before @32, output_vault_before @40, input_amount @48,
     * output_amount @56, transfer-комиссии @64/@72, base_input @80, input_mint @81, output_mint @113, …
     * Старый формат без mint (81 байт) направления не сообщает — не читаем.
     */
    private fun cpmm(d: ByteArray): Parse {
        val o = 8
        if (d.size < o + 145) return Parse.Bad
        val pool = encodeBase58(d.copyOfRange(o, o + 32))
        val inAmount = u64(d, o + 48)
        val outAmount = u64(d, o + 56)
        val inMint = encodeBase58(d.copyOfRange(o + 81, o + 113))
        return Parse.Swap(RawSwap(ChainDex.RAYDIUM_CPMM, pool, amountIn = inAmount.toDouble(),
            amountOut = outAmount.toDouble(), inMint = inMint))
    }

    /**
     * SwapEvent CLMM: pool_state @0, sender @32, token_account_0 @64, token_account_1 @96, amount_0 @128,
     * transfer_fee_0 @136, amount_1 @144, transfer_fee_1 @152, zero_for_one @160 (token0 → token1).
     */
    private fun clmm(d: ByteArray): Parse {
        val o = 8
        if (d.size < o + 161) return Parse.Bad
        val pool = encodeBase58(d.copyOfRange(o, o + 32))
        val amount0 = u64(d, o + 128).toDouble()
        val amount1 = u64(d, o + 144).toDouble()
        val zeroForOne = d[o + 160].toInt() != 0
        return Parse.Swap(
            if (zeroForOne) RawSwap(ChainDex.RAYDIUM_CLMM, pool, amountIn = amount0, amountOut = amount1, side0In = true)
            else RawSwap(ChainDex.RAYDIUM_CLMM, pool, amountIn = amount1, amountOut = amount0, side0In = false)
        )
    }

    /**
     * ray_log AMM v4 (bincode, little-endian): log_type u8, затем 7 × u64. SwapBaseIn: amount_in, minimum_out,
     * direction, user_source, pool_coin, pool_pc, out_amount; SwapBaseOut: max_in, amount_out, direction,
     * user_source, pool_coin, pool_pc, deduct_in. direction 1 — pc → coin, 2 — coin → pc. Остальные типы
     * (Init/Deposit/Withdraw) — не сделки.
     */
    private fun decodeRayLog(payload: String): Parse {
        val d = b64(payload.trim()) ?: return Parse.Bad
        if (d.isEmpty()) return Parse.Bad
        val type = d[0].toInt()
        if (type != RAY_SWAP_BASE_IN && type != RAY_SWAP_BASE_OUT) return Parse.Other
        if (d.size < 57) return Parse.Bad
        val direction = u64(d, 17).toLong()
        val poolCoin = u64(d, 33).toDouble()
        val poolPc = u64(d, 41).toDouble()
        val (amountIn, amountOut) =
            if (type == RAY_SWAP_BASE_IN) u64(d, 1) to u64(d, 49) else u64(d, 49) to u64(d, 9)
        val coinIn = when (direction) {
            RAY_DIR_COIN_TO_PC -> true
            RAY_DIR_PC_TO_COIN -> false
            else -> return Parse.Bad
        }
        return Parse.Swap(RawSwap(ChainDex.RAYDIUM_AMM_V4, pool = null, amountIn = amountIn.toDouble(),
            amountOut = amountOut.toDouble(), side0In = coinIn, reserve0 = poolCoin, reserve1 = poolPc))
    }

    private fun b64(s: String): ByteArray? = try {
        Base64.decode(s)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** u64 little-endian. */
internal fun u64(d: ByteArray, o: Int): ULong {
    var r = 0UL
    for (i in 7 downTo 0) r = (r shl 8) or (d[o + i].toULong() and 0xFFUL)
    return r
}

/** i64 little-endian. */
internal fun i64(d: ByteArray, o: Int): Long = u64(d, o).toLong()
