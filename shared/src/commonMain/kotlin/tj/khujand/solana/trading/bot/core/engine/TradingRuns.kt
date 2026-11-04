package tj.khujand.solana.trading.bot.core.engine

import tj.khujand.solana.trading.bot.core.risk.RiskManager
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.util.formatLocalTime

// Забеги («Новый старт», порт TradingRuns из MRX): момент, с которого считаются статистика, итоги на
// экранах и риск-лимиты. Сделки при этом не удаляются — почему, см. TradingRun.sq.

/**
 * Начало идущего забега режима ([demo] — DEMO/SHADOW или REAL), мс. 0 — «Нового старта» в этом режиме
 * не было: считается вся история, как до появления забегов. Таблицу не прочитать — тоже 0: лимиты тогда
 * строже (видят всю историю), а не слепнут.
 */
fun DrxDatabase.runStartedAt(demo: Boolean): Long =
    runCatching { tradingRunQueries.current(if (demo) 1L else 0L).executeAsOneOrNull()?.started_at }.getOrNull() ?: 0L

/**
 * Итоги одного забега — для «Аналитики», Telegram `/runs`.
 * @param number номер по порядку в своём режиме: 0 — сделки до первого «Нового старта», дальше с 1.
 * @param endedAt null — забег идёт.
 * @param startBalance капитал на старте; null — неизвестен (период до первого старта).
 * @param strategies стратегии, чьи сделки закрылись в забеге, — от самой активной.
 */
data class RunSummary(
    val number: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val startBalance: Double?,
    val endBalance: Double?,
    val note: String,
    val trades: Int,
    val wins: Int,
    val pnl: Double,
    val fees: Double,
    val strategies: List<String>,
) {
    val current: Boolean get() = endedAt == null
    val winrate: Int get() = if (trades > 0) wins * 100 / trades else 0

    /** Результат сделок в % от стартового капитала; null — старт неизвестен. */
    val pnlPercent: Double? get() = startBalance?.takeIf { it > 0.0 }?.let { pnl / it * 100.0 }

    /** «№2 · идёт с 03.11 14:20» или «№1 · 28.10 09:00 → 03.11 14:20» (местное время). */
    fun periodLabel(): String = when {
        number == 0 -> "До первого старта · по ${formatLocalTime(endedAt ?: startedAt)}"
        endedAt == null -> "№$number · идёт с ${formatLocalTime(startedAt)}"
        else -> "№$number · ${formatLocalTime(startedAt)} → ${formatLocalTime(endedAt)}"
    }

    /** «12 сделок · winrate 58% · P&L +12.4 USD (+1.2%)». */
    fun resultLabel(): String {
        val sign = if (pnl > 0) "+" else ""
        val pct = pnlPercent?.let { " ($sign${fmtNum(it)}%)" } ?: ""
        return "$trades сделок · winrate $winrate% · P&L $sign${fmtNum(pnl)} USD$pct"
    }
}

/**
 * Забеги режима с итогами, первый — самый старый. Итоги — по закрытым сделкам, а не по разнице балансов:
 * пополнение кошелька посреди забега не должно выглядеть прибылью. Сделки до первого «Нового старта» —
 * забег №0 (если они есть). Пусто — стартов не было.
 */
fun runSummaries(db: DrxDatabase, demo: Boolean): List<RunSummary> {
    val flag = if (demo) 1L else 0L
    val runs = db.tradingRunQueries.byMode(flag).executeAsList()
    if (runs.isEmpty()) return emptyList()
    val closed = db.tradeQueries.closedByMode(flag).executeAsList()

    fun summary(number: Int, from: Long, until: Long?, start: Double?, end: Double?, note: String): RunSummary {
        val inRun = closed.filter { (it.closed_at ?: 0L) >= from && (until == null || (it.closed_at ?: 0L) < until) }
        return RunSummary(
            number = number,
            startedAt = from,
            endedAt = until,
            startBalance = start,
            endBalance = end,
            note = note,
            trades = inRun.size,
            wins = inRun.count { (it.pnl ?: 0.0) > 0.0 },
            pnl = inRun.sumOf { it.pnl ?: 0.0 },
            fees = inRun.sumOf { it.fee ?: 0.0 },
            strategies = inRun.groupingBy { it.strategy_name }.eachCount().entries
                .sortedByDescending { it.value }.map { it.key },
        )
    }

    val result = ArrayList<RunSummary>()
    val firstStart = runs.first().started_at
    val before = closed.firstOrNull()?.closed_at
    if (before != null && before < firstStart) {
        result += summary(0, before, firstStart, start = null, end = runs.first().start_balance, note = "до первого «Нового старта»")
    }
    runs.forEachIndexed { i, run ->
        result += summary(i + 1, run.started_at, run.ended_at, run.start_balance, run.end_balance, run.note)
    }
    return result
}

/**
 * «Новый старт» режима: закрывает идущий забег и открывает новый с [nowMs]. Сделки не удаляются.
 * @param equityUsd капитал сейчас (свободный баланс + позиции по рынку) — итог старого забега.
 * @param startBalance капитал на старте нового (для DEMO — новый баланс счёта, если его меняли).
 */
fun DrxDatabase.startNewRun(demo: Boolean, nowMs: Long, equityUsd: Double?, startBalance: Double?, note: String = "") {
    val flag = if (demo) 1L else 0L
    tradingRunQueries.transaction {
        tradingRunQueries.endCurrent(nowMs, equityUsd, flag)
        tradingRunQueries.insert(flag, nowMs, startBalance, note)
    }
}

/**
 * «Новый старт» текущего режима (UI, Telegram `/reset`): итог старого забега — капитал сейчас; новый
 * стартует с него же, а в DEMO с [newDemoBalance] (если задан) демо-счёт выставляется в эту сумму —
 * открытые позиции при этом остаются и ведутся дальше.
 * @return строка для ленты и Telegram.
 */
fun beginNewRun(db: DrxDatabase, executor: TradeExecutor, riskManager: RiskManager, newDemoBalance: Double?, nowMs: Long): String {
    val demo = executor.isDemo()
    val before = riskManager.equityUsd().takeIf { it > 0.0 }
    if (demo && newDemoBalance != null && newDemoBalance > 0.0) executor.setDemoBalance(newDemoBalance)
    val start = riskManager.equityUsd().takeIf { it > 0.0 }
    db.startNewRun(demo, nowMs, equityUsd = before, startBalance = start)
    val number = runSummaries(db, demo).lastOrNull()?.number ?: 1
    val mode = if (demo) "DEMO" else "REAL"
    val balance = start?.let { " Капитал на старте: ${fmtNum(it)} USD." } ?: ""
    return "🏁 Новый старт №$number ($mode): статистика и лимиты убытка считаются с этого момента, сделки не удалены.$balance"
}
