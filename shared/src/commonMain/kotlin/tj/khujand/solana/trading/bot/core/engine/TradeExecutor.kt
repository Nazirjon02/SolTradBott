package tj.khujand.solana.trading.bot.core.engine

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tj.khujand.solana.trading.bot.core.strategy.Signal
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.crypto.Signer
import tj.khujand.solana.trading.bot.crypto.createSignerFromSeedPhrase
import tj.khujand.solana.trading.bot.crypto.signTransactionBase64
import tj.khujand.solana.trading.bot.crypto.transactionSignatureBase58
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.data.db.Trade as TradeRow
import tj.khujand.solana.trading.bot.data.db.Tx_journal as JournalRow
import tj.khujand.solana.trading.bot.exchange.dex.AccountCache
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.JupiterSwapQuotes
import tj.khujand.solana.trading.bot.exchange.dex.LAMPORTS_PER_SOL
import tj.khujand.solana.trading.bot.exchange.dex.QuoteResult
import tj.khujand.solana.trading.bot.exchange.dex.SOL_MINT
import tj.khujand.solana.trading.bot.exchange.dex.SendResult
import tj.khujand.solana.trading.bot.exchange.dex.SwapQuote
import tj.khujand.solana.trading.bot.exchange.dex.SwapQuotes
import tj.khujand.solana.trading.bot.exchange.dex.TxOutcome

private const val DEMO_START_BALANCE = 10_000.0

/** Как исполняется сделка (колонка trade.exec_mode). */
const val EXEC_DEMO = "DEMO"
const val EXEC_SHADOW = "SHADOW"
const val EXEC_REAL = "REAL"

/** Столько неудачных попыток выхода подряд — и позиция считается «продажа заблокирована» (EXIT_BLOCKED). */
const val EXIT_BLOCKED_AFTER = 3

/** Причина неудачи «токенов нет на кошельке»: две такие подряд закрывают позицию без продажи. */
internal const val NO_TOKENS_NOTE = "токенов нет на кошельке"

/** Стартовый slippage продажи — после каждой неудачи растёт (см. [sellSlippageBps]). */
private const val BASE_SELL_SLIPPAGE_BPS = 300

/** Котировку старше этого не исполняем — запрашиваем заново. */
private const val QUOTE_MAX_AGE_MS = 15_000L

/** Сверка берёт запись журнала не раньше: этого обычному пути хватает на подтверждение и запись. */
private const val RECONCILE_MIN_AGE_MS = 90_000L

/** Подпись, которую сеть так и не увидела, старше этого уже не исполнится (блокхэш живёт ~1–1.5 мин). */
private const val TX_EXPIRY_MS = 3 * 60_000L

/** Сколько хранить завершённые записи журнала. */
private const val JOURNAL_KEEP_MS = 7 * 24 * 60 * 60_000L

private const val KIND_BUY = "BUY"
private const val KIND_SELL = "SELL"
private const val JOURNAL_PENDING = "PENDING"
private const val JOURNAL_CONFIRMED = "CONFIRMED"
private const val JOURNAL_FAILED = "FAILED"

private val journalJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Результат закрытия (полного или частичного).
 * @param soldNothing позиция закрыта без продажи — токенов на кошельке не оказалось (продано вне бота):
 *   выручка неизвестна, [exitPrice] и [pnlUsd] — 0.
 */
data class CloseResult(
    val exitPrice: Double,
    val closedQty: Double,
    val pnlUsd: Double,
    val isFull: Boolean,
    val soldNothing: Boolean = false,
)

/** Итог попытки продать: REAL — своп, SHADOW — котировка, DEMO — по рыночной цене. */
private sealed interface Sale {
    /** Продано на [proceedsUsd]; [soldAll] — на кошельке больше нечего продавать; [signature] — запись журнала. */
    data class Filled(val proceedsUsd: Double, val soldAll: Boolean, val signature: String? = null) : Sale

    /** Не продано (нет котировки или маршрута, своп упал) — позиция открыта, попытка идёт в счётчик неудач. */
    data class Failed(val reason: String) : Sale

    /** Транзакция ушла, но итог неизвестен — второй раз не продаём, итог узнает сверка журнала. */
    data object Pending : Sale

    /** Токенов на кошельке нет. */
    data object NothingToSell : Sale
}

/** Покупка из журнала: всё, чтобы записать сделку, даже если подтверждение пришло после рестарта. */
@Serializable
internal data class BuyIntent(
    val signal: Signal,
    val strategyId: String,
    val strategyName: String,
    val lamportsIn: Long,
    val quotedOut: Long,
    val solPriceUsd: Double,
    val entryLiquidityUsd: Double,
    val entryAtrFrac: Double,
    val tokenBefore: Long?,
)

/** Продажа из журнала: всё, чтобы учесть выход, если подтверждение опоздало. */
@Serializable
internal data class SellIntent(
    val tradeId: String,
    val requestedQty: Double,
    val fullRequested: Boolean,
    val reason: String,
    val tp1Done: Boolean,
    val tp2Done: Boolean,
    val proceedsUsd: Double,
    val soldAll: Boolean,
    val currentPrice: Double,
)

/**
 * Исполнитель сделок:
 *  - DEMO — paper-trading на виртуальном балансе в account_cache по рыночной цене;
 *  - SHADOW — тот же виртуальный счёт, но вход и выход — по реальным котировкам Jupiter (комиссии пулов
 *    и проскальзывание видны в результате), транзакции не отправляются;
 *  - REAL — свопы SOL→token→SOL через Jupiter с подписью seed-фразой и журналом транзакций.
 * Режим — глобальные переключатели SettingsStore (demoMode, shadowQuotes), управляются из UI/Telegram.
 *
 * @param quotes котировки и mint-аккаунты (инъекция — для тестов SHADOW без сети).
 */
class TradeExecutor(
    private val client: DexClient,
    private val db: DrxDatabase,
    private val settings: SettingsStore,
    private val accountCache: AccountCache,
    private val activityLog: ActivityLog,
    private val quotes: SwapQuotes = JupiterSwapQuotes(client),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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

    // Итог записи журнала учитывает ровно один путь — обычный или сверка (проверка PENDING + запись).
    private val journalMutex = Mutex()

    fun isDemo(): Boolean = settings.getDemoMode() ?: true

    /** SHADOW: DEMO-счёт, но вход и выход — по реальным котировкам Jupiter (без отправки транзакций). */
    fun isShadow(): Boolean = isDemo() && settings.getShadowQuotes()

    /** Режим для подписей в UI/Telegram/REST: DEMO, SHADOW или REAL. */
    fun modeLabel(): String = when {
        !isDemo() -> EXEC_REAL
        isShadow() -> EXEC_SHADOW
        else -> EXEC_DEMO
    }

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

    /** Выставить демо-счёт (свободные USD) — «Новый старт» с новым балансом. */
    fun setDemoBalance(v: Double) = accountCache.set(AccountCache.COIN_DEMO, v, v)

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
        val now = nowMs()
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
     * @param buyQuote котировка покупки из проверки входа (EntryGuard): SHADOW и REAL исполняют
     *   по ней, если она свежая, иначе запрашивают новую.
     */
    suspend fun openTrade(
        signal: Signal,
        config: StrategyConfig,
        sizeUsd: Double,
        entryLiquidityUsd: Double,
        entryAtrFrac: Double = 0.0,
        buyQuote: SwapQuote? = null,
    ): String? {
        if (sizeUsd <= 0 || signal.entryPrice <= 0) return null
        return when {
            !isDemo() -> openReal(signal, config, sizeUsd, entryLiquidityUsd, entryAtrFrac, buyQuote)
            isShadow() -> openShadow(signal, config, sizeUsd, entryLiquidityUsd, entryAtrFrac, buyQuote)
            else -> openDemo(signal, config, sizeUsd, entryLiquidityUsd, entryAtrFrac)
        }
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
        insertTrade(id, signal, config.id, config.name, qty, sizeUsd, sizeSol = 0.0, liq, atrFrac, isDemo = true, EXEC_DEMO)
        setDemoBalance(balance - sizeUsd)
        activityLog.success("🟢 DEMO вход: ${signal.symbol} $${fmtNum(sizeUsd)} @ ${signal.entryPrice}")
        id
    }

    /**
     * SHADOW-вход: сколько потрачено и сколько токенов получено — по котировке Jupiter на наш размер,
     * как при настоящем свопе (комиссии пулов и проскальзывание уже внутри), но транзакция не уходит.
     * Цена входа в сделке — рыночная из сигнала, как в REAL: издержки видны через size_usd и qty.
     * qty — в целых токенах, как у всех DEMO-сделок.
     */
    private suspend fun openShadow(
        signal: Signal,
        config: StrategyConfig,
        sizeUsd: Double,
        liq: Double,
        atrFrac: Double,
        preQuote: SwapQuote?,
    ): String? {
        val solPrice = quotes.solPriceUsd()?.takeIf { it > 0 } ?: run {
            activityLog.warn("⚠️ SHADOW: нет цены SOL — вход ${signal.symbol} пропущен")
            return null
        }
        val quote = buyQuote(signal.mint, sizeUsd, solPrice, buySlippageBps(config), preQuote) ?: run {
            activityLog.warn("⚠️ SHADOW: Jupiter не дал котировку покупки ${signal.symbol}")
            return null
        }
        val decimals = quotes.mintInfo(signal.mint)?.decimals ?: run {
            activityLog.warn("⚠️ SHADOW: нет данных mint-аккаунта ${signal.symbol} (decimals)")
            return null
        }
        val tokens = quote.outAmount / 10.0.pow(decimals)
        val spentSol = quote.inAmount.toDouble() / LAMPORTS_PER_SOL
        val spentUsd = spentSol * solPrice
        if (tokens <= 0.0 || spentUsd <= 0.0) return null
        return demoMutex.withLock {
            val balance = demoBalanceUsd()
            if (balance < spentUsd) {
                activityLog.warn("⚠️ SHADOW: недостаточно демо-баланса ($balance < $spentUsd)")
                return@withLock null
            }
            val id = newTradeId(signal.symbol)
            insertTrade(id, signal, config.id, config.name, tokens, spentUsd, spentSol, liq, atrFrac, isDemo = true, EXEC_SHADOW)
            setDemoBalance(balance - spentUsd)
            val overMarketPct = (spentUsd / tokens / signal.entryPrice - 1) * 100
            activityLog.success(
                "🟢 SHADOW вход: ${signal.symbol} $${fmtNum(spentUsd)} @ ${signal.entryPrice} " +
                    "(по котировке дороже рынка на ${fmtNum(overMarketPct)}%)"
            )
            id
        }
    }

    private suspend fun openReal(
        signal: Signal,
        config: StrategyConfig,
        sizeUsd: Double,
        liq: Double,
        atrFrac: Double,
        preQuote: SwapQuote?,
    ): String? {
        val s = signer() ?: run {
            activityLog.error("❌ REAL: seed-фраза не задана или некорректна")
            return null
        }
        val pubkey = s.publicKeyBase58()
        val solPrice = quotes.solPriceUsd()?.takeIf { it > 0 } ?: run {
            activityLog.error("❌ REAL: не удалось получить цену SOL")
            return null
        }
        val quote = buyQuote(signal.mint, sizeUsd, solPrice, buySlippageBps(config), preQuote)
            ?: run { activityLog.error("❌ REAL: Jupiter quote не получен (${signal.symbol})"); return null }
        val lamports = quote.inAmount
        val balance = client.rpc.getBalanceLamports(pubkey) ?: 0L
        // Запас: приоритетная комиссия (auto) + рента ATA нового токена (~2.04 млн lamports).
        val feeBuffer = 5_000_000L
        if (balance < lamports + feeBuffer) {
            activityLog.warn("⚠️ REAL: недостаточно SOL (balance=$balance, need=${lamports + feeBuffer})")
            return null
        }
        // Баланс токена ДО покупки — чтобы после узнать фактический приход.
        val tokenBefore = client.rpc.getTokenBalanceRaw(pubkey, signal.mint)

        val swap = client.jupiter.getSwap(quote = quote.raw, userPublicKey = pubkey)
            ?: run { activityLog.error("❌ REAL: сборка swap не удалась"); return null }
        val unsigned = swap.swapTransaction
            ?: run { activityLog.error("❌ REAL: Jupiter не вернул транзакцию свопа (${signal.symbol})"); return null }
        val signed = signTransactionBase64(unsigned, s)
            ?: run { activityLog.error("❌ REAL: не удалось подписать транзакцию покупки"); return null }
        val signature = transactionSignatureBase58(signed)
            ?: run { activityLog.error("❌ REAL: не удалось прочитать подпись транзакции покупки"); return null }
        val intent = BuyIntent(
            signal = signal,
            strategyId = config.id,
            strategyName = config.name,
            lamportsIn = lamports,
            quotedOut = quote.outAmount,
            solPriceUsd = solPrice,
            entryLiquidityUsd = liq,
            entryAtrFrac = atrFrac,
            tokenBefore = tokenBefore,
        )
        // С момента записи в журнал покупку нельзя бросать на полпути: отмена (стоп/пауза бота, правка
        // стратегии) между sendTransaction и записью оставила бы токены без сделки в БД — монитор
        // их бы не сопровождал и не продал. Журнал — до отправки: даже потерянный ответ или падение
        // процесса оставят след, по которому сверка запишет сделку.
        return withContext(NonCancellable) {
            journalInsert(signature, KIND_BUY, signal.mint, signal.symbol, tradeId = null, journalJson.encodeToString(intent))
            sendAndRecordBuy(signature, signed, intent, pubkey)
        }
    }

    private suspend fun sendAndRecordBuy(signature: String, signed: String, intent: BuyIntent, pubkey: String): String? {
        val symbol = intent.signal.symbol
        when (val sent = client.rpc.sendTransaction(signed)) {
            is SendResult.Rejected -> {
                settleJournal(signature, JOURNAL_FAILED, note = sent.reason)
                activityLog.error("❌ REAL: RPC отклонил покупку $symbol — ${sent.reason}")
                return null
            }
            SendResult.Unknown -> {
                activityLog.warn("⚠️ REAL: ответ RPC на покупку $symbol потерян — итог проверит сверка ($signature)")
                return null
            }
            is SendResult.Sent -> Unit
        }
        val outcome = client.rpc.awaitSignature(signature)

        // Фактический приход токенов: quoted outAmount может завышать (slippage),
        // а «неподтверждённая» tx могла исполниться после таймаута.
        val tokenAfter = client.rpc.getTokenBalanceRaw(pubkey, intent.signal.mint)
        val receivedRaw = if (intent.tokenBefore != null && tokenAfter != null && tokenAfter > intent.tokenBefore)
            tokenAfter - intent.tokenBefore else -1L
        when (outcome) {
            TxOutcome.FAILED -> {
                settleJournal(signature, JOURNAL_FAILED, note = "транзакция упала")
                activityLog.error("❌ REAL: покупка $symbol не исполнилась — транзакция упала ($signature)")
                return null
            }
            TxOutcome.TIMEOUT -> {
                if (receivedRaw <= 0) {
                    activityLog.warn("⚠️ REAL: покупка $symbol не подтверждена вовремя — итог проверит сверка ($signature)")
                    return null
                }
                activityLog.warn("⚠️ REAL: подтверждение опоздало, но токены получены — записываю сделку ($signature)")
            }
            TxOutcome.CONFIRMED -> Unit
        }

        // Кол-во токенов храним в raw-единицах — продажа оперирует ими же.
        // Приоритет — фактический приход с кошелька, quoted outAmount — fallback.
        val qtyRaw = if (receivedRaw > 0) receivedRaw else intent.quotedOut
        val id = recordBuy(signature, intent, qtyRaw, openedAt = nowMs()) ?: return null
        val sizeUsd = intent.lamportsIn.toDouble() / LAMPORTS_PER_SOL * intent.solPriceUsd
        activityLog.success("🟢 REAL вход: $symbol $${fmtNum(sizeUsd)} @ ${intent.signal.entryPrice}, tx=$signature")
        return id
    }

    /**
     * Сделка по исполнившейся покупке и итог её записи журнала — одной транзакцией БД.
     * null — запись журнала уже закрыта другим путём (сделку записала сверка или наоборот).
     */
    private suspend fun recordBuy(signature: String, intent: BuyIntent, qtyRaw: Long, openedAt: Long): String? {
        val id = newTradeId(intent.signal.symbol)
        val sizeSol = intent.lamportsIn.toDouble() / LAMPORTS_PER_SOL
        val recorded = settleJournalWith(signature, JOURNAL_CONFIRMED, tradeId = id, note = "") {
            insertTrade(
                id, intent.signal, intent.strategyId, intent.strategyName,
                qty = qtyRaw.toDouble(),
                sizeUsd = sizeSol * intent.solPriceUsd,
                sizeSol = sizeSol,
                entryLiquidityUsd = intent.entryLiquidityUsd,
                entryAtrFrac = intent.entryAtrFrac,
                isDemo = false,
                execMode = EXEC_REAL,
                openedAt = openedAt,
            )
        }
        return if (recorded) id else null
    }

    /** Котировка покупки на [sizeUsd]: из проверки входа, если свежая, иначе новая. null — нет котировки. */
    private suspend fun buyQuote(
        mint: String,
        sizeUsd: Double,
        solPrice: Double,
        slippageBps: Int,
        pre: SwapQuote?,
    ): SwapQuote? {
        if (pre != null && pre.outAmount > 0 && nowMs() - pre.fetchedAtMs <= QUOTE_MAX_AGE_MS) return pre
        val lamports = (sizeUsd / solPrice * LAMPORTS_PER_SOL).toLong().coerceAtLeast(1L)
        return (quotes.quote(SOL_MINT, mint, lamports, slippageBps) as? QuoteResult.Ok)?.quote?.takeIf { it.outAmount > 0 }
    }

    /**
     * Строка сделки + база выходов в R (начальный стоп и ATR входа) и режим исполнения — одной
     * транзакцией: стоп потом двигают безубыток/трейлинг, а R считается от начального.
     */
    private fun insertTrade(
        id: String,
        signal: Signal,
        strategyId: String,
        strategyName: String,
        qty: Double,
        sizeUsd: Double,
        sizeSol: Double,
        entryLiquidityUsd: Double,
        entryAtrFrac: Double,
        isDemo: Boolean,
        execMode: String,
        openedAt: Long = nowMs(),
    ) = db.transaction {
        db.tradeQueries.insert(
            id = id,
            strategy_id = strategyId,
            strategy_name = strategyName,
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
            opened_at = openedAt,
            closed_at = null,
        )
        db.tradeQueries.setRiskBasis(signal.stopLoss, entryAtrFrac, id)
        db.tradeQueries.setExecMode(execMode, id)
    }

    // ─── Закрытие ────────────────────────────────────────────────────────────

    /** Сделка всё ещё открыта (null из [closeTrade] мог означать, что её уже закрыли параллельно). */
    fun isOpen(tradeId: String): Boolean =
        db.tradeQueries.getById(tradeId).executeAsOneOrNull()?.status == "OPEN"

    /** По сделке отправлена продажа, итог которой ещё неизвестен (ждёт сверки журнала). */
    fun hasPendingSell(tradeId: String): Boolean =
        db.txJournalQueries.hasPendingSell(tradeId).executeAsOne() > 0L

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
     *
     * Неудачная продажа (REAL/SHADOW) → null, а в сделке растёт счётчик неудач (exit_failures): со
     * следующей попыткой slippage выше, с [EXIT_BLOCKED_AFTER] подряд — пауза между попытками.
     * @param manual закрытие вручную: если токенов на кошельке нет, позиция закрывается сразу, без продажи.
     */
    suspend fun closeTrade(
        trade: TradeRow,
        currentPrice: Double,
        portionOfOriginal: Double,
        reason: String,
        tp1Done: Boolean = trade.tp1_done == 1L,
        tp2Done: Boolean = trade.tp2_done == 1L,
        manual: Boolean = false,
    ): CloseResult? = closeLock(trade.id).withLock {
        // Закрытие не прерываем отменой (стоп бота, пересоздание рантайма): продажа, исполненная
        // без записи в БД, оставила бы позицию «открытой» без токенов на кошельке.
        withContext(NonCancellable) {
            closeLocked(trade, currentPrice, portionOfOriginal, reason, tp1Done, tp2Done, manual)
        }
    }

    private suspend fun closeLocked(
        trade: TradeRow,
        currentPrice: Double,
        portionOfOriginal: Double,
        reason: String,
        tp1Done: Boolean,
        tp2Done: Boolean,
        manual: Boolean,
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
        val fullRequested = requestedQty >= t.qty_remaining - 1e-9

        // Прошлая продажа ещё «в полёте» — вторую не отправляем: итог узнает сверка журнала.
        if (t.is_demo == 0L && hasPendingSell(t.id)) return null

        val sale = when {
            t.is_demo == 0L -> sellReal(t, requestedQty, currentPrice, fullRequested, reason, tp1Done, tp2Done)
            t.exec_mode == EXEC_SHADOW -> sellShadow(t, requestedQty)
            else -> Sale.Filled(proceedsUsd = requestedQty * currentPrice, soldAll = false)
        }
        return when (sale) {
            is Sale.Filled -> recordSale(t, requestedQty, fullRequested, sale, currentPrice, reason, tp1Done, tp2Done)
            is Sale.Failed -> {
                registerExitFailure(t, sale.reason)
                null
            }
            Sale.Pending -> null
            // Пустой кошелёк дважды подряд (а не сбой одного ответа RPC) или ручное закрытие — закрываем
            // без продажи: иначе позиция висела бы открытой вечно, а монитор слал бы продажи в никуда.
            Sale.NothingToSell -> if (manual || t.exit_note == NO_TOKENS_NOTE) {
                closeWithoutSale(t, reason)
            } else {
                registerExitFailure(t, NO_TOKENS_NOTE)
                null
            }
        }
    }

    /**
     * Учитывает исполненную продажу: строка сделки (частично или полностью), DEMO/SHADOW-баланс, сброс
     * счётчика неудач и — для REAL — итог записи журнала, всё одной транзакцией БД.
     * null — запись журнала уже закрыта другим путём (эту продажу учла сверка).
     */
    private suspend fun recordSale(
        t: TradeRow,
        requestedQty: Double,
        fullRequested: Boolean,
        sale: Sale.Filled,
        currentPrice: Double,
        reason: String,
        tp1Done: Boolean,
        tp2Done: Boolean,
    ): CloseResult? {
        // На кошельке оказалось меньше, чем в БД (qty записан по котировке покупки), и продано
        // всё, что было: позиция исчерпана — иначе остаток в БД висел бы открытым без токенов.
        val isFull = fullRequested || sale.soldAll
        val closedQty = if (isFull) t.qty_remaining else requestedQty
        val costUsdPortion = t.size_usd * (closedQty / t.qty)
        val pnlUsd = sale.proceedsUsd - costUsdPortion
        // Цена выхода — в единицах цены входа (USD за целый токен), через фактическую доходность продажи:
        // в REAL qty — raw-единицы, и выручка/qty давала цену в 10^decimals раз меньше. Для DEMO формула
        // совпадает с рыночной ценой, для SHADOW — рыночный эквивалент с учётом издержек.
        val exitPrice = if (costUsdPortion > 0) t.entry_price * sale.proceedsUsd / costUsdPortion else currentPrice
        val totalPnl = (t.pnl ?: 0.0) + pnlUsd

        val writeSale: () -> Unit = {
            if (isFull) {
                val pnlPercent = if (t.size_usd > 0) totalPnl / t.size_usd * 100 else 0.0
                db.tradeQueries.closeTrade(
                    exit_price = exitPrice,
                    pnl = totalPnl,
                    pnl_percent = pnlPercent,
                    fee = t.fee,
                    close_reason = reason,
                    closed_at = nowMs(),
                    id = t.id,
                )
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
            db.tradeQueries.clearExitFailures(t.id)
            if (t.is_demo == 1L) setDemoBalance(demoBalanceUsd() + sale.proceedsUsd)
        }
        val applied = when {
            sale.signature != null -> settleJournalWith(sale.signature, JOURNAL_CONFIRMED, tradeId = t.id, note = "", onClaimed = writeSale)
            t.is_demo == 1L -> {
                demoMutex.withLock { db.transaction { writeSale() } }
                true
            }
            else -> {
                db.transaction { writeSale() }
                true
            }
        }
        if (!applied) return null
        // После полного закрытия лок не нужен: любой следующий вызов перечитает строку и увидит CLOSED.
        if (isFull) closeLocksGuard.withLock { closeLocks.remove(t.id) }
        return CloseResult(exitPrice, closedQty, pnlUsd, isFull)
    }

    /**
     * Закрывает позицию без продажи: токенов на кошельке нет (проданы вне бота). Выручка неизвестна —
     * в PnL остаются только уже зафиксированные частичные выходы.
     */
    private suspend fun closeWithoutSale(t: TradeRow, reason: String): CloseResult {
        val pnl = t.pnl
        db.tradeQueries.closeTrade(
            exit_price = null,
            pnl = pnl,
            pnl_percent = pnl?.let { if (t.size_usd > 0) it / t.size_usd * 100 else 0.0 },
            fee = t.fee,
            close_reason = "$reason — токенов на кошельке нет, закрыта без продажи",
            closed_at = nowMs(),
            id = t.id,
        )
        closeLocksGuard.withLock { closeLocks.remove(t.id) }
        activityLog.warn("⚠️ ${t.symbol}: токенов на кошельке нет — позиция закрыта без продажи (PnL неизвестен)")
        return CloseResult(exitPrice = 0.0, closedQty = t.qty_remaining, pnlUsd = 0.0, isFull = true, soldNothing = true)
    }

    /** Неудачная попытка выхода: счётчик подряд, время следующей попытки и причина — в строку сделки. */
    private fun registerExitFailure(t: TradeRow, note: String) {
        val failures = t.exit_failures.toInt() + 1
        db.tradeQueries.setExitFailure(failures.toLong(), nowMs() + exitRetryDelayMs(failures), note, t.id)
        activityLog.warn("⚠️ ${t.symbol}: продажа не прошла ($failures подряд) — $note")
    }

    /** SHADOW-выход: выручка — котировка Jupiter на продажу этого количества (транзакция не отправляется). */
    private suspend fun sellShadow(t: TradeRow, qty: Double): Sale {
        val decimals = quotes.mintInfo(t.mint)?.decimals ?: return Sale.Failed("нет данных mint-аккаунта (decimals)")
        val amountRaw = (qty * 10.0.pow(decimals)).toLong()
        if (amountRaw <= 0) return Sale.Failed("нечего продавать (0 raw-единиц)")
        val solPrice = quotes.solPriceUsd()?.takeIf { it > 0 } ?: return Sale.Failed("нет цены SOL")
        val slippage = sellSlippageBps(t.exit_failures.toInt(), settings.getProtection().maxSellSlippagePct)
        return when (val r = quotes.quote(t.mint, SOL_MINT, amountRaw, slippage)) {
            is QuoteResult.Ok -> Sale.Filled(r.quote.outAmount.toDouble() / LAMPORTS_PER_SOL * solPrice, soldAll = false)
            is QuoteResult.NoRoute -> Sale.Failed("нет маршрута продажи (${r.reason})")
            is QuoteResult.Failed -> Sale.Failed("котировка продажи недоступна: ${r.reason}")
        }
    }

    /**
     * REAL-продажа raw-количества токена через Jupiter. Slippage растёт с числом неудач подряд
     * ([sellSlippageBps]). Подпись пишется в журнал до отправки: если ответ потеряется или подтверждение
     * опоздает, второй продажи не будет ([Sale.Pending]) — итог узнает сверка.
     */
    private suspend fun sellReal(
        t: TradeRow,
        qtyRaw: Double,
        currentPrice: Double,
        fullRequested: Boolean,
        reason: String,
        tp1Done: Boolean,
        tp2Done: Boolean,
    ): Sale {
        val s = signer() ?: return Sale.Failed("кошелёк недоступен (seed-фраза)")
        val pubkey = s.publicKeyBase58()
        // Продаём не больше, чем реально лежит на кошельке: qty в БД могло быть
        // записано по котировке покупки, а фактический приход был меньше (slippage).
        val onChain = client.rpc.getTokenBalanceRaw(pubkey, t.mint)
        if (onChain == 0L) return Sale.NothingToSell
        var amount = qtyRaw.toLong().coerceAtLeast(1L)
        if (onChain != null && amount > onChain) amount = onChain
        // Цена SOL — ДО свопа: если Jupiter не ответит после продажи, выручка не должна обнулиться
        // (это записало бы -100% PnL по уже исполненной продаже).
        val solPrice = quotes.solPriceUsd()?.takeIf { it > 0 } ?: t.entrySolPriceUsd()
        val slippage = sellSlippageBps(t.exit_failures.toInt(), settings.getProtection().maxSellSlippagePct)
        val quote = when (val r = quotes.quote(t.mint, SOL_MINT, amount, slippage)) {
            is QuoteResult.Ok -> r.quote
            is QuoteResult.NoRoute -> return Sale.Failed("нет маршрута продажи (${r.reason})")
            is QuoteResult.Failed -> return Sale.Failed("котировка продажи недоступна: ${r.reason}")
        }
        val swap = client.jupiter.getSwap(quote = quote.raw, userPublicKey = pubkey)
            ?: return Sale.Failed("сборка свопа не удалась")
        val unsigned = swap.swapTransaction ?: return Sale.Failed("Jupiter не вернул транзакцию")
        val signed = signTransactionBase64(unsigned, s) ?: return Sale.Failed("не удалось подписать транзакцию")
        val signature = transactionSignatureBase58(signed) ?: return Sale.Failed("не удалось прочитать подпись транзакции")

        val proceedsUsd = quote.outAmount.toDouble() / LAMPORTS_PER_SOL * solPrice
        val soldAll = onChain != null && amount >= onChain
        val intent = SellIntent(
            tradeId = t.id,
            requestedQty = qtyRaw,
            fullRequested = fullRequested,
            reason = reason,
            tp1Done = tp1Done,
            tp2Done = tp2Done,
            proceedsUsd = proceedsUsd,
            soldAll = soldAll,
            currentPrice = currentPrice,
        )
        journalInsert(signature, KIND_SELL, t.mint, t.symbol, t.id, journalJson.encodeToString(intent))
        when (val sent = client.rpc.sendTransaction(signed)) {
            is SendResult.Rejected -> {
                settleJournal(signature, JOURNAL_FAILED, note = sent.reason)
                return Sale.Failed("RPC отклонил продажу (slippage ${slippage / 100}%): ${sent.reason}")
            }
            SendResult.Unknown -> {
                activityLog.warn("⚠️ REAL sell ${t.symbol}: ответ RPC потерян — итог проверит сверка ($signature)")
                return Sale.Pending
            }
            is SendResult.Sent -> Unit
        }
        when (client.rpc.awaitSignature(signature)) {
            TxOutcome.CONFIRMED -> Unit
            TxOutcome.FAILED -> {
                settleJournal(signature, JOURNAL_FAILED, note = "транзакция упала")
                return Sale.Failed("транзакция продажи упала (slippage ${slippage / 100}%)")
            }
            TxOutcome.TIMEOUT -> {
                // Tx могла исполниться после таймаута — проверяем по списанию баланса.
                val after = client.rpc.getTokenBalanceRaw(pubkey, t.mint)
                val landed = onChain != null && after != null && after <= onChain - amount
                if (!landed) {
                    activityLog.warn("⚠️ REAL sell ${t.symbol}: не подтверждена вовремя — итог проверит сверка ($signature)")
                    return Sale.Pending
                }
                activityLog.warn("⚠️ REAL sell: подтверждение опоздало, баланс списан — считаю исполненной ($signature)")
            }
        }
        return Sale.Filled(proceedsUsd, soldAll, signature)
    }

    /** Цена SOL на момент входа (размер в USD / в SOL) — последний запасной вариант оценки выручки. */
    private fun TradeRow.entrySolPriceUsd(): Double = if (size_sol > 0) size_usd / size_sol else 0.0

    // ─── Журнал REAL-транзакций и сверка ─────────────────────────────────────

    private fun journalInsert(signature: String, kind: String, mint: String, symbol: String, tradeId: String?, payload: String) {
        val now = nowMs()
        db.txJournalQueries.insert(signature, kind, mint, symbol, tradeId, payload, now, now)
    }

    private suspend fun settleJournal(signature: String, status: String, note: String) {
        settleJournalWith(signature, status, tradeId = null, note = note)
    }

    /**
     * Записывает итог поверх PENDING и, если запись ещё была PENDING, выполняет [onClaimed] в той же
     * транзакции БД. false — итог уже записан другим путём (обычным или сверкой): ничего не учитываем.
     */
    private suspend fun settleJournalWith(
        signature: String,
        status: String,
        tradeId: String?,
        note: String,
        onClaimed: () -> Unit = {},
    ): Boolean = journalMutex.withLock {
        val row = db.txJournalQueries.getBySignature(signature).executeAsOneOrNull()
        if (row == null || row.status != JOURNAL_PENDING) return@withLock false
        db.transaction {
            db.txJournalQueries.settle(status, tradeId, note, nowMs(), signature)
            onClaimed()
        }
        true
    }

    /**
     * Сверка журнала: итог PENDING-записей по подписи (берём записи старше [RECONCILE_MIN_AGE_MS] —
     * свежие ещё ведёт обычный путь). Покупка исполнилась, а сделки нет — записывает сделку, и монитор
     * начинает её сопровождать; продажа исполнилась позже, чем ждали, — учитывает выход; упала или так
     * и не попала в сеть — FAILED (монету снова можно покупать, позицию — продавать).
     * Возвращает сообщения для ленты и Telegram.
     */
    suspend fun reconcileJournal(): List<String> {
        val now = nowMs()
        db.txJournalQueries.deleteSettledBefore(now - JOURNAL_KEEP_MS)
        val due = db.txJournalQueries.getPending().executeAsList().filter { now - it.created_at >= RECONCILE_MIN_AGE_MS }
        if (due.isEmpty()) return emptyList()
        val statuses = client.rpc.getSignatureStatuses(due.map { it.signature }) ?: return emptyList()
        val messages = mutableListOf<String>()
        for (row in due) {
            val what = if (row.kind == KIND_BUY) "покупка" else "продажа"
            when (statuses[row.signature]) {
                true -> when (row.kind) {
                    KIND_BUY -> recoverBuy(row)?.let(messages::add)
                    KIND_SELL -> applyLateSale(row)?.let(messages::add)
                }
                false -> if (settleJournalWith(row.signature, JOURNAL_FAILED, null, "транзакция упала")) {
                    messages += "ℹ️ Сверка: $what ${row.symbol} не исполнилась — транзакция упала (${row.signature})"
                }
                null -> if (now - row.created_at >= TX_EXPIRY_MS &&
                    settleJournalWith(row.signature, JOURNAL_FAILED, null, "не попала в сеть")
                ) {
                    messages += "ℹ️ Сверка: $what ${row.symbol} не состоялась — транзакция не попала в сеть"
                }
            }
        }
        return messages
    }

    private suspend fun recoverBuy(row: JournalRow): String? {
        val intent = decodeOrFail<BuyIntent>(row) ?: return null
        val pubkey = walletPublicKey() ?: return null // кошелёк не задан — разберёмся, когда появится
        val after = client.rpc.getTokenBalanceRaw(pubkey, row.mint) ?: return null // RPC недоступен — в следующий раз
        val received = if (intent.tokenBefore != null) after - intent.tokenBefore else after
        return recordRecoveredBuy(row, received)
    }

    /**
     * Сделка по поздно подтверждённой покупке из журнала: [receivedRaw] — сколько токенов пришло.
     * Возвращает сообщение для ленты/Telegram или null (запись уже учтена).
     */
    internal suspend fun recordRecoveredBuy(row: JournalRow, receivedRaw: Long): String? {
        val intent = decodeOrFail<BuyIntent>(row) ?: return null
        if (receivedRaw <= 0) {
            if (!settleJournalWith(row.signature, JOURNAL_CONFIRMED, null, "исполнена, но токенов на кошельке нет")) return null
            return "⚠️ Сверка: покупка ${row.symbol} исполнилась, но токенов на кошельке нет — проверьте кошелёк (${row.signature})"
        }
        recordBuy(row.signature, intent, receivedRaw, openedAt = row.created_at) ?: return null
        return "↩️ Покупка ${row.symbol} подтвердилась с опозданием — сделка записана и сопровождается (${row.signature})"
    }

    /** Поздно подтверждённая продажа из журнала: учитывает выход по сделке. Сообщение или null. */
    internal suspend fun applyLateSale(row: JournalRow): String? {
        val intent = decodeOrFail<SellIntent>(row) ?: return null
        val tradeId = row.trade_id ?: intent.tradeId
        return closeLock(tradeId).withLock {
            withContext(NonCancellable) {
                val t = db.tradeQueries.getById(tradeId).executeAsOneOrNull()
                if (t == null || t.status != "OPEN") {
                    settleJournalWith(row.signature, JOURNAL_CONFIRMED, tradeId, "сделка уже закрыта")
                    return@withContext null
                }
                val sale = Sale.Filled(intent.proceedsUsd, intent.soldAll, row.signature)
                val result = recordSale(
                    t, intent.requestedQty.coerceAtMost(t.qty_remaining), intent.fullRequested, sale,
                    intent.currentPrice, intent.reason, intent.tp1Done, intent.tp2Done,
                ) ?: return@withContext null
                "↩️ Продажа ${t.symbol} подтвердилась с опозданием — " +
                    "${if (result.isFull) "позиция закрыта" else "частичный выход учтён"} (${intent.reason}), " +
                    "PnL ${fmtNum(result.pnlUsd)} USD"
            }
        }
    }

    /** Payload записи журнала; не читается — запись закрывается FAILED, чтобы сверка не крутила её вечно. */
    private suspend inline fun <reified T> decodeOrFail(row: JournalRow): T? {
        val decoded = runCatching { journalJson.decodeFromString<T>(row.payload) }.getOrNull()
        if (decoded == null) settleJournalWith(row.signature, JOURNAL_FAILED, null, "повреждённая запись журнала")
        return decoded
    }

    private fun newTradeId(symbol: String): String =
        "trade-${symbol.lowercase()}-${nowMs()}-${Random.nextInt(1000, 9999)}"
}

/** Slippage покупки стратегии в bps — им же запрашивается котировка в проверке входа. */
fun buySlippageBps(config: StrategyConfig): Int = (config.slippagePercent * 100).toInt().coerceAtLeast(1)

/**
 * Slippage продажи: 3% и удвоение после каждой неудачи подряд (6%, 12%…), но не выше потолка
 * [maxPct] из настроек. Выйти из падающей монеты важнее, чем сэкономить на slippage.
 */
internal fun sellSlippageBps(failures: Int, maxPct: Double): Int {
    val cap = (maxPct * 100).toInt().coerceAtLeast(BASE_SELL_SLIPPAGE_BPS)
    var bps = BASE_SELL_SLIPPAGE_BPS
    repeat(failures.coerceIn(0, 10)) { bps = (bps * 2).coerceAtMost(cap) }
    return bps
}

/**
 * Пауза перед следующей попыткой выхода: первые [EXIT_BLOCKED_AFTER] − 1 неудач — без паузы (следующий
 * цикл монитора: стоп важен), дальше «продажа заблокирована»: 1, 2, 4, 8 мин, не больше 10.
 */
internal fun exitRetryDelayMs(failures: Int): Long {
    if (failures < EXIT_BLOCKED_AFTER) return 0L
    val step = (failures - EXIT_BLOCKED_AFTER).coerceAtMost(4)
    return (60_000L shl step).coerceAtMost(10 * 60_000L)
}
