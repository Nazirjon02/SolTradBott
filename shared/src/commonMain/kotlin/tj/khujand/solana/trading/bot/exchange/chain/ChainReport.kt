package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.math.roundToLong
import kotlin.time.Instant

/** Текст отчёта сверки свечей из цепочки с GeckoTerminal (Markdown). */
object ChainReport {

    /** Счётчики потока за всё время работы. */
    data class Stats(
        val trades: Long,
        val truncated: Long,
        val unparsed: Long,
        val failedTx: Long,
        val noise: Long,
        val reconnects: Int,
    )

    fun render(
        results: List<ReconcileResult>,
        t: CandleReconciler.Totals,
        u: UsageSnapshot,
        s: Stats,
        nowMs: Long,
    ): String = buildString {
        appendLine("## Сверка 5m-свечей из цепочки с GeckoTerminal — ${Instant.fromEpochMilliseconds(nowMs)}")
        appendLine()
        appendLine("- Работа: ${hours(u.runningMs)} ч, переподключений ${s.reconnects}")
        appendLine(
            "- Трафик: ${mb(u.bytesTotal)} МБ, HTTP-вызовов ${u.httpCallsTotal}, соединений ${u.connectionsTotal} → " +
                "${u.creditsTotal.roundToLong()} кредитов; в сутки: в среднем ${u.creditsPerDayAvg.roundToLong()}, " +
                "по последним 10 мин ${u.creditsPerDayNow.roundToLong()}"
        )
        appendLine(
            "- Сделок принято ${s.trades}; транзакций без сделки нашего пула ${s.noise}, с ошибкой ${s.failedTx}, " +
                "с обрезанными логами ${s.truncated}, нечитаемых событий ${s.unparsed}"
        )
        if (t.matched == 0) {
            appendLine("- Полных баров для сверки пока нет")
            return@buildString
        }
        appendLine(
            "- Итого: ${t.pools} пулов, ${t.matched} совпавших баров; close ±0,1% — ${pct(t.closeWithin01, t.matched)}, " +
                "±1% — ${pct(t.closeWithin1, t.matched)}; high ±1% — ${pct(t.highWithin1, t.matched)}; " +
                "low ±1% — ${pct(t.lowWithin1, t.matched)}; open ±1% — ${pct(t.openWithin1, t.openN)}; " +
                "объём ±1% — ${pct(t.volumeWithin1, t.volumeN)}"
        )
        appendLine("- Баров GeckoTerminal без наших сделок (пропуски): ${t.geckoOnly}; наших баров без бара GT: ${t.chainOnly}")
        appendLine()
        appendLine("| Пул | баров | close ±0,1% | close ±1% | high ±1% | low ±1% | open ±1% | объём ±1% | объём наш/GT | медиана ошибки close / объёма | пропуски / лишние |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in results.sortedByDescending { it.matched }) {
            if (r.matched == 0 && r.geckoOnly == 0 && r.chainOnly == 0) continue
            appendLine(
                "| ${r.label} | ${r.matched} | ${pct(r.close.within01, r.close.n)} | ${pct(r.close.within1, r.close.n)} | " +
                    "${pct(r.high.within1, r.high.n)} | ${pct(r.low.within1, r.low.n)} | ${pct(r.open.within1, r.open.n)} | " +
                    "${pct(r.volume.within1, r.volume.n)} | ${ratio(r.volumeRatio)} | " +
                    "${pct2(r.close.median)} / ${pct2(r.volume.median)} | ${r.geckoOnly} / ${r.chainOnly} |"
            )
        }
    }

    private fun pct(a: Int, n: Int): String = if (n == 0) "—" else "${(a * 1000L / n) / 10.0}%"

    private fun pct2(x: Double): String = "${(x * 10_000).roundToLong() / 100.0}%"

    private fun ratio(x: Double): String = "${(x * 1000).roundToLong() / 1000.0}"

    private fun mb(bytes: Long): String = "${(bytes / 10_000L) / 100.0}"

    private fun hours(ms: Long): String = "${(ms / 360_000L) / 10.0}"
}
