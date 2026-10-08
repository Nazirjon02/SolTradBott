package tj.khujand.solana.trading.bot.domain.backtest

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Монета для бэктеста. Метрики (цена, капитализация, ликвидность) — на момент загрузки:
 * исторических значений GeckoTerminal не отдаёт, поэтому в прошлом они оцениваются от текущих
 * (см. [Backtester.candidateAt]).
 */
data class BacktestCoin(
    val pool: String,
    val mint: String,
    val symbol: String,
    val name: String,
    val dexId: String,
    val createdAtMs: Long,
    val priceNow: Double,
    val marketCapNow: Double,
    val liquidityNow: Double,
)

/** История монеты: свечи рабочего ТФ и (если нужен стратегии) старшего ТФ, по возрастанию времени. */
data class CoinSeries(
    val coin: BacktestCoin,
    val candles: List<Candle>,
    val higherTf: List<Candle>,
)

/**
 * Параметры исполнения в бэктесте.
 * @param costPerSidePercent издержки на вход и на каждый выход (комиссия пула + слиппедж), % от цены.
 * @param oneTradePerCoin как в боте: по монете — только одна сделка (иначе — последовательные сделки).
 */
data class BacktestSettings(
    val costPerSidePercent: Double = 1.0,
    val oneTradePerCoin: Boolean = false,
)

/** Одна фиксация: доля исходной позиции [portion] (0..1) продана по [price]. */
data class BacktestFill(
    val timeMs: Long,
    val price: Double,
    val portion: Double,
    val reason: String,
)

/**
 * Сделка бэктеста.
 * @param rMultiple итог в R с учётом издержек (R = вход − начальный стоп).
 * @param returnPct итог в % к вложенному с учётом издержек.
 */
data class BacktestTrade(
    val symbol: String,
    val pool: String,
    val entryTimeMs: Long,
    val exitTimeMs: Long,
    val entryPrice: Double,
    val initialStop: Double,
    val fills: List<BacktestFill>,
    val rMultiple: Double,
    val returnPct: Double,
    val exitReason: String,
    val signalReason: String,
)

/**
 * Сводка по сделкам.
 * @param equityPct итог счёта в %, если каждая сделка рискует [RISK_PER_TRADE_PCT]% (сложный процент,
 *   сделки по порядку закрытия).
 * @param maxDrawdownPct максимальная просадка той же кривой, %.
 */
data class BacktestStats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val avgWinR: Double,
    val avgLossR: Double,
    val expectancyR: Double,
    val profitFactor: Double,
    val totalR: Double,
    val equityPct: Double,
    val maxDrawdownPct: Double,
    val avgHoldMinutes: Double,
    val exitReasons: List<Pair<String, Int>>,
) {
    companion object {
        /** Условный риск на сделку для кривой капитала (Van Tharp: результат в R × риск%). */
        const val RISK_PER_TRADE_PCT = 1.0

        fun from(trades: List<BacktestTrade>): BacktestStats {
            val wins = trades.filter { it.rMultiple > 0 }
            val losses = trades.filter { it.rMultiple <= 0 }
            val grossWin = wins.sumOf { it.rMultiple }
            val grossLoss = -losses.sumOf { it.rMultiple }

            var equity = 1.0
            var peak = 1.0
            var maxDd = 0.0
            for (t in trades.sortedBy { it.exitTimeMs }) {
                equity *= (1 + t.rMultiple * RISK_PER_TRADE_PCT / 100).coerceAtLeast(0.0)
                peak = maxOf(peak, equity)
                if (peak > 0) maxDd = maxOf(maxDd, (peak - equity) / peak)
            }

            return BacktestStats(
                trades = trades.size,
                wins = wins.size,
                losses = losses.size,
                winRate = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
                avgWinR = if (wins.isEmpty()) 0.0 else grossWin / wins.size,
                avgLossR = if (losses.isEmpty()) 0.0 else -grossLoss / losses.size,
                expectancyR = if (trades.isEmpty()) 0.0 else trades.sumOf { it.rMultiple } / trades.size,
                profitFactor = when {
                    grossLoss > 0 -> grossWin / grossLoss
                    grossWin > 0 -> Double.POSITIVE_INFINITY
                    else -> 0.0
                },
                totalR = trades.sumOf { it.rMultiple },
                equityPct = (equity - 1) * 100,
                maxDrawdownPct = maxDd * 100,
                avgHoldMinutes = if (trades.isEmpty()) 0.0
                else trades.map { (it.exitTimeMs - it.entryTimeMs) / 60_000.0 }.average(),
                exitReasons = trades.groupingBy { it.exitReason.substringBefore(" (") }.eachCount()
                    .toList().sortedByDescending { it.second },
            )
        }
    }
}

/** Отчёт бэктеста. [coinsWithData] — монеты, по которым хватило свечей; [bars] — проверено баров. */
data class BacktestReport(
    val strategyName: String,
    val coins: Int,
    val coinsWithData: Int,
    val bars: Int,
    val trades: List<BacktestTrade>,
    val stats: BacktestStats,
)
