package tj.khujand.solana.trading.bot.util

import tj.khujand.solana.trading.bot.domain.TokenHistory
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.getAppContext

actual fun exportTradesToCsv(trades: List<TokenHistory>): String {
    return try {
        val context = getAppContext()
        val dir = context.cacheDir.resolve("SolTradBot").also { it.mkdirs() }
        val file = dir.resolve("trades_export_${System.currentTimeMillis()}.csv")

        val header = "Symbol,EntryDate,ExitDate,EntryPrice,ExitPrice,ProfitUsd,PriceChangePct,Status,Type,Invested,ExitAmount,Note"
        val rows = trades.map { t ->
            listOf(
                t.symbol.escapeCsv(),
                t.entryDate.escapeCsv(),
                t.exitDate.escapeCsv(),
                formatNumber(t.entryPrice, 8),
                formatNumber(t.exitPrice, 8),
                formatNumber(t.profitUsd, 2),
                formatNumber(t.priceChangePercent, 2),
                if (t.status == TokenStatus.STOPPED_TP) "TP" else "SL",
                if (t.isRealTrade) "REAL" else "DEMO",
                formatNumber(t.investedUsd, 2),
                formatNumber(t.exitAmountUsd, 2),
                t.note.escapeCsv(),
            ).joinToString(",")
        }

        file.writeText((listOf(header) + rows).joinToString("\n"), Charsets.UTF_8)
        "Экспортировано ${trades.size} записей → ${file.absolutePath}"
    } catch (e: Exception) {
        "Ошибка экспорта: ${e.message}"
    }
}

private fun String.escapeCsv(): String =
    if (contains(',') || contains('"') || contains('\n')) "\"${replace("\"", "\"\"")}\"" else this
