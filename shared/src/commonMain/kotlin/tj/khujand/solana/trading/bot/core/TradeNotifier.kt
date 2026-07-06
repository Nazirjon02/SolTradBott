package tj.khujand.solana.trading.bot.core

/**
 * Интерфейс уведомлений о сделках (реализация — telegram/TelegramNotifier).
 * Ядро не зависит от Telegram напрямую, чтобы движок работал и без токена.
 */
interface TradeNotifier {
    suspend fun send(text: String)

    suspend fun sendOpenAlert(
        symbol: String,
        strategyName: String,
        entryPrice: Double,
        sizeUsd: Double,
        stopLoss: Double,
        takeProfit: Double,
        isDemo: Boolean,
        reason: String,
    ) = send(
        "🟢 ВХОД ${if (isDemo) "(DEMO)" else ""} $symbol\n" +
            "Стратегия: $strategyName\nЦена: $entryPrice\nРазмер: $${sizeUsd}\n" +
            "SL: $stopLoss | TP: $takeProfit\n$reason"
    )

    suspend fun sendCloseAlert(
        symbol: String,
        strategyName: String,
        entryPrice: Double,
        exitPrice: Double,
        pnlUsd: Double,
        pnlPercent: Double,
        reason: String,
        isDemo: Boolean,
    ) = send(
        "${if (pnlUsd >= 0) "✅" else "🔻"} ВЫХОД ${if (isDemo) "(DEMO)" else ""} $symbol — $reason\n" +
            "Стратегия: $strategyName\n$entryPrice → $exitPrice\nPnL: $${pnlUsd} (${pnlPercent}%)"
    )
}

/** Заглушка, когда Telegram не сконфигурирован. */
object NoopNotifier : TradeNotifier {
    override suspend fun send(text: String) { /* Telegram не подключен */ }
}
