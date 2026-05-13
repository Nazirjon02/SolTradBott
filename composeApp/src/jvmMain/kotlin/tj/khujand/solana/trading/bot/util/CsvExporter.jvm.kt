package tj.khujand.solana.trading.bot.util

import tj.khujand.solana.trading.bot.domain.TokenHistory
import tj.khujand.solana.trading.bot.domain.TokenStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

actual fun exportTradesToCsv(trades: List<TokenHistory>): String {
    val dir = Path.of(System.getProperty("user.home"), ".SolTradBot")
    Files.createDirectories(dir)

    val epochMs = System.currentTimeMillis()
    val file = dir.resolve("trades_export_$epochMs.csv")

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
    Files.writeString(
        file, content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )

    return "Экспортировано ${trades.size} записей → ${file.toAbsolutePath()}"
}

private fun String.escapeCsv(): String {
    return if (contains(',') || contains('"') || contains('\n')) {
        "\"${replace("\"", "\"\"")}\""
    } else this
}
