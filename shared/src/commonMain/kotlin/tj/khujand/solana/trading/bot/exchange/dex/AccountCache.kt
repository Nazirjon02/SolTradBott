package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.time.Clock
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/**
 * Кеш баланса кошелька в БД (аналог AccountCache в MRX):
 * RiskManager и UI читают кеш, а не дёргают RPC на каждый расчёт.
 * Обновляется фоном движком (REAL) или DemoAccountManager (DEMO).
 */
class AccountCache(private val db: DrxDatabase) {

    data class CoinBalance(val coin: String, val balance: Double, val balanceUsd: Double, val updatedAt: Long)

    fun get(coin: String): CoinBalance? =
        db.accountCacheQueries.getCoin(coin).executeAsOneOrNull()?.let {
            CoinBalance(it.coin, it.balance, it.balance_usd, it.updated_at)
        }

    fun all(): List<CoinBalance> =
        db.accountCacheQueries.getAll().executeAsList().map {
            CoinBalance(it.coin, it.balance, it.balance_usd, it.updated_at)
        }

    fun set(coin: String, balance: Double, balanceUsd: Double) {
        db.accountCacheQueries.setBalance(coin, balance, balanceUsd, Clock.System.now().toEpochMilliseconds())
    }

    /** Виртуальный демо-счёт в USD (0, если ещё не инициализирован). */
    fun demoUsd(): Double = get(COIN_DEMO)?.balanceUsd ?: 0.0

    /**
     * Реальный баланс кошелька в USD — сумма всех монет, КРОМЕ виртуального демо-счёта.
     * Демо-счёт (DEMO_USD) хранится в этом же кеше, поэтому в REAL-режиме его нужно
     * исключать, иначе фантомные $10 000 раздули бы размер реальной позиции и риск-лимиты.
     */
    fun realUsd(): Double = all().filter { it.coin != COIN_DEMO }.sumOf { it.balanceUsd }

    /** Обновить SOL-баланс из RPC + цену SOL из Jupiter (REAL-режим). */
    suspend fun refreshSol(client: DexClient, walletPublicKey: String): CoinBalance? {
        val sol = client.getSolBalance(walletPublicKey) ?: return null
        val priceUsd = client.getSolPriceUsd() ?: 0.0
        set(COIN_SOL, sol, sol * priceUsd)
        return get(COIN_SOL)
    }

    fun clear() = db.accountCacheQueries.deleteAll()

    companion object {
        const val COIN_SOL = "SOL"
        const val COIN_USDC = "USDC"
        const val COIN_DEMO = "DEMO_USD"
    }
}
