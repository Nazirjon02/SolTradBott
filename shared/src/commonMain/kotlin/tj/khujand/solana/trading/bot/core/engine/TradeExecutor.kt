package tj.khujand.solana.trading.bot.core.engine

import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.serialization.json.jsonPrimitive
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.crypto.Signer
import tj.khujand.solana.trading.bot.crypto.createSignerFromSeedPhrase
import tj.khujand.solana.trading.bot.crypto.signTransactionBase64
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient

private const val SOL_MINT = "So11111111111111111111111111111111111111112"
private const val LAMPORTS_PER_SOL = 1_000_000_000L
private const val DEMO_START_BALANCE = 10_000.0

/** Результат закрытия (полного или частичного). */
data class CloseResult(
    val exitPrice: Double,
    val closedQty: Double,
    val pnlUsd: Double,
    val isFull: Boolean,
)

/**
 * Исполнитель сделок: DEMO (paper-trading на виртуальном балансе в account_cache)
 * или REAL (свопы SOL→token→SOL через Jupiter с подписью seed-фразой).
 * Режим — глобальный переключатель SettingsStore.demoMode (управляется из UI/Telegram).
 */
class TradeExecutor(
    private val client: DexClient,
    private val db: DrxDatabase,
    private val settings: SettingsStore,
    private val accountCache: AccountCache,
    private val activityLog: ActivityLog,
) {
    private var cachedSigner: Signer? = null
    private var cachedSeed: String? = null

    fun isDemo(): Boolean = settings.getDemoMode() ?: true

    // ─── DEMO-баланс (в account_cache, coin=DEMO_USD) ────────────────────────

    fun demoBalanceUsd(): Double {
        val existing = accountCache.get(AccountCache.COIN_DEMO)
        if (existing != null) return existing.balanceUsd
        accountCache.set(AccountCache.COIN_DEMO, DEMO_START_BALANCE, DEMO_START_BALANCE)
        return DEMO_START_BALANCE
    }

    fun resetDemoBalance() {
        accountCache.set(AccountCache.COIN_DEMO, DEMO_START_BALANCE, DEMO_START_BALANCE)
    }

    private fun setDemoBalance(v: Double) = accountCache.set(AccountCache.COIN_DEMO, v, v)

    // ─── Кошелёк (REAL) ──────────────────────────────────────────────────────

    private fun signer(): Signer? {
        val seed = settings.getWalletSeed() ?: return null
        if (cachedSigner == null || cachedSeed != seed) {
            cachedSigner = runCatching { createSignerFromSeedPhrase(seed) }.getOrNull()
            cachedSeed = seed
        }
        return cachedSigner
    }

    fun walletPublicKey(): String? = signer()?.publicKeyBase58()

    private var lastBalanceRefreshMs: Long = 0L

    /**
     * Подтягивает реальный баланс кошелька в account_cache, но не чаще раза в [minIntervalMs].
     *
     * Без этого в REAL-режиме кеш заполнялся ТОЛЬКО когда кто-то вручную открывал «Баланс»,
     * а RiskManager считает размер позиции именно из кеша: пустой кеш → размер 0 → бот
     * не открывал ни одной сделки. В DEMO — no-op (там своя виртуальная касса).
     */
    suspend fun refreshRealBalanceIfStale(minIntervalMs: Long = 60_000L) {
        if (isDemo()) return
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastBalanceRefreshMs < minIntervalMs) return
        val pubkey = walletPublicKey() ?: return
        runCatching { accountCache.refreshSol(client, pubkey) }
            .onSuccess { lastBalanceRefreshMs = now }
            .onFailure { activityLog.warn("⚠️ Не удалось обновить баланс кошелька: ${it.message}") }
    }

    // ─── Открытие ────────────────────────────────────────────────────────────

    /**
     * Открывает позицию по сигналу. Возвращает id сделки или null (не удалось).
     * @param sizeUsd размер входа в USD (уже рассчитан RiskManager'ом).
     * @param entryLiquidityUsd ликвидность пула на входе (для liquidity-стопа).
     */
    suspend fun openTrade(
        signal: Signal,
        config: StrategyConfig,
        sizeUsd: Double,
        entryLiquidityUsd: Double,
    ): String? {
        if (sizeUsd <= 0 || signal.entryPrice <= 0) return null
        return if (isDemo()) openDemo(signal, config, sizeUsd, entryLiquidityUsd)
        else openReal(signal, config, sizeUsd, entryLiquidityUsd)
    }

    private fun openDemo(signal: Signal, config: StrategyConfig, sizeUsd: Double, liq: Double): String? {
        val balance = demoBalanceUsd()
        if (balance < sizeUsd) {
            activityLog.warn("⚠️ DEMO: недостаточно баланса ($balance < $sizeUsd)")
            return null
        }
        val qty = sizeUsd / signal.entryPrice
        val id = newTradeId(signal.symbol)
        insertTrade(id, signal, config, qty, sizeUsd, sizeSol = 0.0, liq, isDemo = true)
        setDemoBalance(balance - sizeUsd)
        activityLog.success("🟢 DEMO вход: ${signal.symbol} $${fmtNum(sizeUsd)} @ ${signal.entryPrice}")
        return id
    }

    private suspend fun openReal(signal: Signal, config: StrategyConfig, sizeUsd: Double, liq: Double): String? {
        val s = signer() ?: run {
            activityLog.error("❌ REAL: seed-фраза не задана или некорректна")
            return null
        }
        val pubkey = s.publicKeyBase58()
        val solPrice = client.getSolPriceUsd() ?: run {
            activityLog.error("❌ REAL: не удалось получить цену SOL")
            return null
        }
        val lamports = (sizeUsd / solPrice * LAMPORTS_PER_SOL).toLong().coerceAtLeast(1L)
        val balance = client.rpc.getBalanceLamports(pubkey) ?: 0L
        // Запас: приоритетная комиссия (auto) + рента ATA нового токена (~2.04 млн lamports).
        val feeBuffer = 5_000_000L
        if (balance < lamports + feeBuffer) {
            activityLog.warn("⚠️ REAL: недостаточно SOL (balance=$balance, need=${lamports + feeBuffer})")
            return null
        }
        // Баланс токена ДО покупки — чтобы после узнать фактический приход.
        val tokenBefore = client.rpc.getTokenBalanceRaw(pubkey, signal.mint)

        val quote = client.jupiter.getQuote(
            inputMint = SOL_MINT,
            outputMint = signal.mint,
            amount = lamports,
            slippageBps = (config.slippagePercent * 100).toInt().coerceAtLeast(1),
        ) ?: run { activityLog.error("❌ REAL: Jupiter quote не получен (${signal.symbol})"); return null }

        val swap = client.jupiter.getSwap(quote = quote, userPublicKey = pubkey)
            ?: run { activityLog.error("❌ REAL: сборка swap не удалась"); return null }
        val unsigned = swap.swapTransaction ?: return null
        val signed = signTransactionBase64(unsigned, s) ?: return null
        val txId = client.rpc.sendTransaction(signed) ?: return null
        val confirmed = client.rpc.confirmTransaction(txId)

        // Фактический приход токенов: quoted outAmount может завышать (slippage),
        // а «неподтверждённая» tx могла исполниться после таймаута.
        val tokenAfter = client.rpc.getTokenBalanceRaw(pubkey, signal.mint)
        val receivedRaw = if (tokenBefore != null && tokenAfter != null && tokenAfter > tokenBefore)
            tokenAfter - tokenBefore else -1L
        if (!confirmed) {
            if (receivedRaw <= 0) {
                activityLog.error(
                    "❌ REAL: транзакция покупки не подтверждена ($txId) — " +
                        "проверьте в эксплорере; если токены пришли, продайте вручную"
                )
                return null
            }
            activityLog.warn("⚠️ REAL: подтверждение опоздало, но токены получены — записываю сделку ($txId)")
        }

        // Кол-во токенов храним в raw-единицах — продажа оперирует ими же.
        // Приоритет — фактический приход с кошелька, quoted outAmount — fallback.
        val quotedOut = quote["outAmount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val outRaw = if (receivedRaw > 0) receivedRaw else quotedOut
        val inLamports = quote["inAmount"]?.jsonPrimitive?.content?.toLongOrNull() ?: lamports
        val id = newTradeId(signal.symbol)
        insertTrade(
            id, signal, config,
            qty = outRaw.toDouble(),
            sizeUsd = inLamports.toDouble() / LAMPORTS_PER_SOL * solPrice,
            sizeSol = inLamports.toDouble() / LAMPORTS_PER_SOL,
            entryLiquidityUsd = liq,
            isDemo = false,
        )
        activityLog.success("🟢 REAL вход: ${signal.symbol} $${fmtNum(sizeUsd)} @ ${signal.entryPrice}, tx=$txId")
        return id
    }

    private fun insertTrade(
        id: String,
        signal: Signal,
        config: StrategyConfig,
        qty: Double,
        sizeUsd: Double,
        sizeSol: Double,
        entryLiquidityUsd: Double,
        isDemo: Boolean,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.tradeQueries.insert(
            id = id,
            strategy_id = config.id,
            strategy_name = config.name,
            mint = signal.mint,
            symbol = signal.symbol,
            pair_address = signal.pairAddress,
            entry_price = signal.entryPrice,
            exit_price = null,
            qty = qty,
            qty_remaining = qty,
            size_usd = sizeUsd,
            size_sol = sizeSol,
            stop_loss = signal.stopLoss,
            take_profit = signal.takeProfit,
            peak_price = signal.entryPrice,
            tp1_done = 0,
            tp2_done = 0,
            entry_liquidity_usd = entryLiquidityUsd,
            pnl = null,
            pnl_percent = null,
            fee = null,
            status = "OPEN",
            open_reason = signal.reason,
            close_reason = null,
            is_demo = if (isDemo) 1 else 0,
            opened_at = now,
            closed_at = null,
        )
    }

    // ─── Закрытие ────────────────────────────────────────────────────────────

    /**
     * Закрывает долю позиции. @param portionOfOriginal % от ИСХОДНОГО объёма (100 = весь остаток).
     * Обновляет строку trade (partial → applyPartialClose, полный → closeTrade) и DEMO-баланс.
     */
    suspend fun closeTrade(
        trade: TradeRow,
        currentPrice: Double,
        portionOfOriginal: Double,
        reason: String,
        tp1Done: Boolean = trade.tp1_done == 1L,
        tp2Done: Boolean = trade.tp2_done == 1L,
    ): CloseResult? {
        val requestedQty = if (portionOfOriginal >= 100.0) trade.qty_remaining
        else (trade.qty * portionOfOriginal / 100.0).coerceAtMost(trade.qty_remaining)
        if (requestedQty <= 0) return null
        val isFull = requestedQty >= trade.qty_remaining - 1e-9

        val costUsdPortion = trade.size_usd * (requestedQty / trade.qty)

        val pnlUsd: Double
        val exitPrice: Double
        if (trade.is_demo == 1L) {
            exitPrice = currentPrice
            val proceeds = requestedQty * currentPrice
            pnlUsd = proceeds - costUsdPortion
            setDemoBalance(demoBalanceUsd() + costUsdPortion + pnlUsd)
        } else {
            val result = sellReal(trade, requestedQty) ?: return null
            pnlUsd = result.first - costUsdPortion
            exitPrice = if (requestedQty > 0) result.first / requestedQty else currentPrice
        }

        val totalPnl = (trade.pnl ?: 0.0) + pnlUsd
        if (isFull) {
            val pnlPercent = if (trade.size_usd > 0) totalPnl / trade.size_usd * 100 else 0.0
            db.tradeQueries.closeTrade(
                exit_price = exitPrice,
                pnl = totalPnl,
                pnl_percent = pnlPercent,
                fee = trade.fee,
                close_reason = reason,
                closed_at = Clock.System.now().toEpochMilliseconds(),
                id = trade.id,
            )
        } else {
            db.tradeQueries.applyPartialClose(
                qty_remaining = trade.qty_remaining - requestedQty,
                tp1_done = if (tp1Done) 1 else 0,
                tp2_done = if (tp2Done) 1 else 0,
                pnl = totalPnl,
                fee = trade.fee,
                id = trade.id,
            )
        }
        return CloseResult(exitPrice, requestedQty, pnlUsd, isFull)
    }

    /** Продажа raw-количества токена через Jupiter. Возвращает (выручка USD, txId) или null. */
    private suspend fun sellReal(trade: TradeRow, qtyRaw: Double): Pair<Double, String>? {
        val s = signer() ?: run {
            activityLog.error("❌ REAL sell: кошелёк недоступен")
            return null
        }
        val pubkey = s.publicKeyBase58()
        // Продаём не больше, чем реально лежит на кошельке: qty в БД могло быть
        // записано по котировке покупки, а фактический приход был меньше (slippage).
        val onChain = client.rpc.getTokenBalanceRaw(pubkey, trade.mint)
        if (onChain == 0L) {
            activityLog.error("❌ REAL sell: на кошельке нет ${trade.symbol} — проверьте позицию вручную")
            return null
        }
        var amount = qtyRaw.toLong().coerceAtLeast(1L)
        if (onChain != null && amount > onChain) amount = onChain
        val quote = client.jupiter.getQuote(
            inputMint = trade.mint,
            outputMint = SOL_MINT,
            amount = amount,
            slippageBps = 300, // выходим с запасом по slippage — важнее выйти, чем сэкономить
        ) ?: run { activityLog.error("❌ REAL sell: quote не получен (${trade.symbol})"); return null }

        val swap = client.jupiter.getSwap(quote = quote, userPublicKey = pubkey) ?: return null
        val unsigned = swap.swapTransaction ?: return null
        val signed = signTransactionBase64(unsigned, s) ?: return null
        val txId = client.rpc.sendTransaction(signed) ?: return null
        if (!client.rpc.confirmTransaction(txId)) {
            // Tx могла исполниться после таймаута — проверяем по списанию баланса.
            val after = client.rpc.getTokenBalanceRaw(pubkey, trade.mint)
            val landed = onChain != null && after != null && after <= onChain - amount
            if (!landed) {
                activityLog.error("❌ REAL sell: транзакция не подтверждена ($txId) — проверьте в эксплорере")
                return null
            }
            activityLog.warn("⚠️ REAL sell: подтверждение опоздало, баланс списан — считаю исполненной ($txId)")
        }

        val outLamports = quote["outAmount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val solPrice = client.getSolPriceUsd() ?: 0.0
        val proceedsUsd = outLamports.toDouble() / LAMPORTS_PER_SOL * solPrice
        return proceedsUsd to txId
    }

    private fun newTradeId(symbol: String): String =
        "trade-${symbol.lowercase()}-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(1000, 9999)}"
}
