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

    /** Суммарный баланс в USD — база для расчёта размера позиции (% от баланса). */
    fun totalUsd(): Double = all().sumOf { it.balanceUsd }

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
    }
}
