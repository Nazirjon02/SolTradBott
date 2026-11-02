package tj.khujand.solana.trading.bot.core.engine

import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
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

/** Итог REAL-продажи: выручка в USD и продан ли весь остаток токена на кошельке. */
private class SaleResult(val proceedsUsd: Double, val soldAll: Boolean)

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

    // DEMO-баланс меняют параллельно стратегии (вход) и монитор/ручное закрытие (выход) на
    // Dispatchers.Default: без лока «прочитать → записать» одна из правок терялась.
    private val demoMutex = Mutex()

    // По локу на сделку: монитор и ручное закрытие (UI/Telegram/REST) не должны продать одну
    // позицию дважды. Лок держится и на время REAL-свопа — второй вызов дождётся и увидит итог.
    private val closeLocksGuard = Mutex()
    private val closeLocks = mutableMapOf<String, Mutex>()

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
     * @param entryAtrFrac ATR рабочего ТФ / цена на входе (для ATR-трейлинга; 0 = нет данных).
     */
    suspend fun openTrade(
        signal: Signal,
        config: StrategyConfig,
        sizeUsd: Double,
        entryLiquidityUsd: Double,
        entryAtrFrac: Double = 0.0,
    ): String? {
        if (sizeUsd <= 0 || signal.entryPrice <= 0) return null
        return if (isDemo()) openDemo(signal, config, sizeUsd, entryLiquidityUsd, entryAtrFrac)
        else openReal(signal, config, sizeUsd, entryLiquidityUsd, entryAtrFrac)
    }

    private suspend fun openDemo(
        signal: Signal,
        config: StrategyConfig,
        sizeUsd: Double,
        liq: Double,
        atrFrac: Double,
    ): String? = demoMutex.withLock {
        val balance = demoBalanceUsd()
        if (balance < sizeUsd) {
            activityLog.warn("⚠️ DEMO: недостаточно баланса ($balance < $sizeUsd)")
            return@withLock null
        }
        val qty = sizeUsd / signal.entryPrice
        val id = newTradeId(signal.symbol)
        insertTrade(id, signal, config, qty, sizeUsd, sizeSol = 0.0, liq, atrFrac, isDemo = true)
        setDemoBalance(balance - sizeUsd)
        activityLog.success("🟢 DEMO вход: ${signal.symbol} $${fmtNum(sizeUsd)} @ ${signal.entryPrice}")
        id
    }

    private suspend fun openReal(
        signal: Signal,
        config: StrategyConfig,
        sizeUsd: Double,
        liq: Double,
        atrFrac: Double,
    ): String? {
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
        val unsigned = swap.swapTransaction
            ?: run { activityLog.error("❌ REAL: Jupiter не вернул транзакцию свопа (${signal.symbol})"); return null }
        val signed = signTransactionBase64(unsigned, s)
            ?: run { activityLog.error("❌ REAL: не удалось подписать транзакцию покупки"); return null }
        // С момента отправки покупку нельзя бросать на полпути: отмена (стоп/пауза бота, правка
        // стратегии) между sendTransaction и записью оставила бы токены без сделки в БД — монитор
        // их бы не сопровождал и не продал.
        return withContext(NonCancellable) {
            sendAndRecordBuy(signal, config, signed, pubkey, tokenBefore, quote, lamports, solPrice, liq, atrFrac)
        }
    }

    private suspend fun sendAndRecordBuy(
        signal: Signal,
        config: StrategyConfig,
        signed: String,
        pubkey: String,
        tokenBefore: Long?,
        quote: JsonObject,
        lamports: Long,
        solPrice: Double,
        liq: Double,
        atrFrac: Double,
    ): String? {
        val txId = client.rpc.sendTransaction(signed)
            ?: run { activityLog.error("❌ REAL: RPC отклонил транзакцию покупки ${signal.symbol}"); return null }
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
        val sizeSol = inLamports.toDouble() / LAMPORTS_PER_SOL
        val sizeUsd = sizeSol * solPrice
        val id = newTradeId(signal.symbol)
        insertTrade(
            id, signal, config,
            qty = outRaw.toDouble(),
            sizeUsd = sizeUsd,
            sizeSol = sizeSol,
            entryLiquidityUsd = liq,
            entryAtrFrac = atrFrac,
            isDemo = false,
        )
        activityLog.success("🟢 REAL вход: ${signal.symbol} $${fmtNum(sizeUsd)} @ ${signal.entryPrice}, tx=$txId")
        return id
    }

    /**
     * Строка сделки + база выходов в R (начальный стоп и ATR входа) одной транзакцией:
     * стоп потом двигают безубыток/трейлинг, а R считается от начального.
     */
    private fun insertTrade(
        id: String,
        signal: Signal,
        config: StrategyConfig,
        qty: Double,
        sizeUsd: Double,
        sizeSol: Double,
        entryLiquidityUsd: Double,
        entryAtrFrac: Double,
        isDemo: Boolean,
    ) = db.transaction {
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
        db.tradeQueries.setRiskBasis(signal.stopLoss, entryAtrFrac, id)
    }

    // ─── Закрытие ────────────────────────────────────────────────────────────

    /** Сделка всё ещё открыта (null из [closeTrade] мог означать, что её уже закрыли параллельно). */
    fun isOpen(tradeId: String): Boolean =
        db.tradeQueries.getById(tradeId).executeAsOneOrNull()?.status == "OPEN"

    private suspend fun closeLock(tradeId: String): Mutex =
        closeLocksGuard.withLock { closeLocks.getOrPut(tradeId) { Mutex() } }

    /**
     * Закрывает долю позиции. @param portionOfOriginal % от ИСХОДНОГО объёма (100 = весь остаток).
     * Обновляет строку trade (partial → applyPartialClose, полный → closeTrade) и DEMO-баланс.
     *
     * Под локом сделки строка перечитывается из БД: [trade] — лишь снимок вызывающего, а позицию
     * могли уже закрыть или уменьшить параллельно. Закрытая позиция → null (проверяйте [isOpen]);
     * частичная фиксация, решённая по устаревшему снимку, пропускается — монитор пересчитает её
     * в следующем цикле по свежим данным.
     */
    suspend fun closeTrade(
        trade: TradeRow,
        currentPrice: Double,
        portionOfOriginal: Double,
        reason: String,
        tp1Done: Boolean = trade.tp1_done == 1L,
        tp2Done: Boolean = trade.tp2_done == 1L,
    ): CloseResult? = closeLock(trade.id).withLock {
        // Закрытие не прерываем отменой (стоп бота, пересоздание рантайма): продажа, исполненная
        // без записи в БД, оставила бы позицию «открытой» без токенов на кошельке.
        withContext(NonCancellable) { closeLocked(trade, currentPrice, portionOfOriginal, reason, tp1Done, tp2Done) }
    }

    private suspend fun closeLocked(
        trade: TradeRow,
        currentPrice: Double,
        portionOfOriginal: Double,
        reason: String,
        tp1Done: Boolean,
        tp2Done: Boolean,
    ): CloseResult? {
        val t = db.tradeQueries.getById(trade.id).executeAsOneOrNull()
        if (t == null || t.status != "OPEN") {
            closeLocksGuard.withLock { closeLocks.remove(trade.id) }
            return null
        }
        val partial = portionOfOriginal < 100.0
        val staleSnapshot = t.qty_remaining != trade.qty_remaining ||
            t.tp1_done != trade.tp1_done || t.tp2_done != trade.tp2_done
        if (partial && staleSnapshot) return null

        val requestedQty = if (!partial) t.qty_remaining
        else (t.qty * portionOfOriginal / 100.0).coerceAtMost(t.qty_remaining)
        if (requestedQty <= 0) return null
        var isFull = requestedQty >= t.qty_remaining - 1e-9

        val closedQty: Double
        val pnlUsd: Double
        val exitPrice: Double
        if (t.is_demo == 1L) {
            closedQty = requestedQty
            val costUsdPortion = t.size_usd * (closedQty / t.qty)
            exitPrice = currentPrice
            val proceeds = closedQty * currentPrice
            pnlUsd = proceeds - costUsdPortion
            demoMutex.withLock { setDemoBalance(demoBalanceUsd() + proceeds) }
        } else {
            val sale = sellReal(t, requestedQty) ?: return null
            // На кошельке оказалось меньше, чем в БД (qty записан по котировке покупки), и продано
            // всё, что было: позиция исчерпана — иначе остаток в БД висел бы открытым без токенов.
            if (sale.soldAll) isFull = true
            closedQty = if (isFull) t.qty_remaining else requestedQty
            val costUsdPortion = t.size_usd * (closedQty / t.qty)
            pnlUsd = sale.proceedsUsd - costUsdPortion
            // qty в REAL — raw-единицы токена, а entry_price — USD за целый токен: делить выручку
            // на qty нельзя (цена выхода выходила в 10^decimals раз меньше). Выход в тех же
            // единицах, что вход, — через фактическую доходность продажи.
            exitPrice = if (costUsdPortion > 0) t.entry_price * sale.proceedsUsd / costUsdPortion else currentPrice
        }

        val totalPnl = (t.pnl ?: 0.0) + pnlUsd
        if (isFull) {
            val pnlPercent = if (t.size_usd > 0) totalPnl / t.size_usd * 100 else 0.0
            db.tradeQueries.closeTrade(
                exit_price = exitPrice,
                pnl = totalPnl,
                pnl_percent = pnlPercent,
                fee = t.fee,
                close_reason = reason,
                closed_at = Clock.System.now().toEpochMilliseconds(),
                id = t.id,
            )
            // После полного закрытия лок не нужен: любой следующий вызов перечитает строку и увидит CLOSED.
            closeLocksGuard.withLock { closeLocks.remove(t.id) }
        } else {
            db.tradeQueries.applyPartialClose(
                qty_remaining = t.qty_remaining - closedQty,
                tp1_done = if (tp1Done) 1 else 0,
                tp2_done = if (tp2Done) 1 else 0,
                pnl = totalPnl,
                fee = t.fee,
                id = t.id,
            )
        }
        return CloseResult(exitPrice, closedQty, pnlUsd, isFull)
    }

    /** Продажа raw-количества токена через Jupiter. null — продажа не состоялась. */
    private suspend fun sellReal(trade: TradeRow, qtyRaw: Double): SaleResult? {
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
        // Цена SOL — ДО свопа: если Jupiter не ответит после продажи, выручка не должна обнулиться
        // (это записало бы -100% PnL по уже исполненной продаже).
        val solPriceBefore = client.getSolPriceUsd()
        val quote = client.jupiter.getQuote(
            inputMint = trade.mint,
            outputMint = SOL_MINT,
            amount = amount,
            slippageBps = 300, // выходим с запасом по slippage — важнее выйти, чем сэкономить
        ) ?: run { activityLog.error("❌ REAL sell: quote не получен (${trade.symbol})"); return null }

        val swap = client.jupiter.getSwap(quote = quote, userPublicKey = pubkey)
            ?: run { activityLog.error("❌ REAL sell: сборка swap не удалась (${trade.symbol})"); return null }
        val unsigned = swap.swapTransaction
            ?: run { activityLog.error("❌ REAL sell: Jupiter не вернул транзакцию (${trade.symbol})"); return null }
        val signed = signTransactionBase64(unsigned, s)
            ?: run { activityLog.error("❌ REAL sell: не удалось подписать транзакцию"); return null }
        val txId = client.rpc.sendTransaction(signed)
            ?: run { activityLog.error("❌ REAL sell: RPC отклонил транзакцию продажи ${trade.symbol}"); return null }
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
        val solPrice = solPriceBefore ?: client.getSolPriceUsd() ?: trade.entrySolPriceUsd()
        val proceedsUsd = outLamports.toDouble() / LAMPORTS_PER_SOL * solPrice
        return SaleResult(proceedsUsd, soldAll = onChain != null && amount >= onChain)
    }

    /** Цена SOL на момент входа (размер в USD / в SOL) — последний запасной вариант оценки выручки. */
    private fun TradeRow.entrySolPriceUsd(): Double = if (size_sol > 0) size_usd / size_sol else 0.0

    private fun newTradeId(symbol: String): String =
        "trade-${symbol.lowercase()}-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(1000, 9999)}"
}
