package tj.khujand.solana.trading.bot.util

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import tj.khujand.solana.trading.bot.domain.TokenHistory
import tj.khujand.solana.trading.bot.domain.TokenStatus

actual fun exportTradesToCsv(trades: List<TokenHistory>): String {
    return try {
        val docs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        ).firstOrNull() as? String ?: return "Ошибка: не найдена папка Documents"

        val fileName = "trades_export_${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}.csv"
        val path = "$docs/$fileName"
        val url = NSURL.fileURLWithPath(path)

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

        val content = (listOf(header) + rows).joinToString("\n")
        val nsString = content as NSString
        nsString.writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)

        "Экспортировано ${trades.size} записей → $path"
    } catch (e: Exception) {
        "Ошибка экспорта: ${e.message}"
    }
}

private fun String.escapeCsv(): String =
    if (contains(',') || contains('"') || contains('\n')) "\"${replace("\"", "\"\"")}\"" else this
