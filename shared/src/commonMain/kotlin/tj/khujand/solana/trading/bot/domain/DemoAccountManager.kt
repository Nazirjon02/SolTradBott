package tj.khujand.solana.trading.bot.domain

import tj.khujand.solana.trading.bot.util.AppSettings

// ════════════════════════════════════════════════════════════════════════════════
// ДЕМО-АККАУНТ (виртуальный баланс для тестирования стратегий)
// ════════════════════════════════════════════════════════════════════════════════
// Работает когда Jupiter ВЫКЛЮЧЕН (jupiterEnabled = false)
// Позволяет тестировать фильтры и стратегии без реальных денег
// ════════════════════════════════════════════════════════════════════════════════
object DemoAccountManager {
    private const val KEY_DEMO_BALANCE = "demo_account_balance_v1"
    
    // ⭐ СТАРТОВЫЙ БАЛАНС: $10,000 (виртуальные деньги)
    private const val DEFAULT_BALANCE = 10_000.0
    
    // ⭐ РАЗМЕР ОДНОЙ ДЕМО-ПОЗИЦИИ: $100
    // При каждой "покупке" токена списывается $100 с демо-баланса
    const val DEMO_TRADE_AMOUNT = 100.0

    // ──── Получить текущий баланс ────
    fun getBalance(): Double {
        return AppSettings.getDoubleSafe(KEY_DEMO_BALANCE, DEFAULT_BALANCE)
    }

    // ──── Сбросить баланс к $10,000 ────
    fun resetBalance() {
        AppSettings.putDouble(KEY_DEMO_BALANCE, DEFAULT_BALANCE)
    }

    // ──── ДЕМО-ПОКУПКА: списать $100 при добавлении токена ────
    fun applyDemoBuy() {
        val current = getBalance()
        AppSettings.putDouble(KEY_DEMO_BALANCE, current - DEMO_TRADE_AMOUNT)
    }

    // ──── Применить прибыль/убыток (не используется активно) ────
    fun applyProfitLoss(amountUsd: Double) {
        val current = getBalance()
        AppSettings.putDouble(KEY_DEMO_BALANCE, current + amountUsd)
    }

    // ──── ЗАКРЫТИЕ ПОЗИЦИИ: вернуть $100 + прибыль/убыток ────
    // Пример: profitUsd = +30 → баланс += $130 (вернули $100 + заработали $30)
    // Пример: profitUsd = -20 → баланс += $80  (вернули $100 - потеряли $20)
    fun applyCloseResult(profitUsd: Double) {
        val current = getBalance()
        AppSettings.putDouble(KEY_DEMO_BALANCE, current + DEMO_TRADE_AMOUNT + profitUsd)
    }
}
// ════════════════════════════════════════════════════════════════════════════════
