package tj.khujand.solana.trading.bot.domain

import tj.khujand.solana.trading.bot.util.AppSettings

object DemoAccountManager {
    private const val KEY_DEMO_BALANCE = "demo_account_balance_v1"
    private const val DEFAULT_BALANCE = 10_000.0
    const val DEMO_TRADE_AMOUNT = 100.0

    fun getBalance(): Double {
        return AppSettings.getDoubleSafe(KEY_DEMO_BALANCE, DEFAULT_BALANCE)
    }

    fun resetBalance() {
        AppSettings.putDouble(KEY_DEMO_BALANCE, DEFAULT_BALANCE)
    }

    fun applyDemoBuy() {
        val current = getBalance()
        AppSettings.putDouble(KEY_DEMO_BALANCE, current - DEMO_TRADE_AMOUNT)
    }

    fun applyProfitLoss(amountUsd: Double) {
        val current = getBalance()
        AppSettings.putDouble(KEY_DEMO_BALANCE, current + amountUsd)
    }

    fun applyCloseResult(profitUsd: Double) {
        val current = getBalance()
        AppSettings.putDouble(KEY_DEMO_BALANCE, current + DEMO_TRADE_AMOUNT + profitUsd)
    }
}
