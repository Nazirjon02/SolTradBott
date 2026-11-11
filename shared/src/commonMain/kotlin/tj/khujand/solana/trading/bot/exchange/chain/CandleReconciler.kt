package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.math.abs
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Расхождения одного поля по барам: относительная ошибка |наш − GT| / GT.
 * @param within01/within1 сколько баров совпали точнее 0,1% и 1%.
 */
data class FieldDiff(val n: Int, val within01: Int, val within1: Int, val median: Double, val p90: Double, val max: Double) {
    val share01: Double get() = if (n == 0) 0.0 else within01.toDouble() / n
    val share1: Double get() = if (n == 0) 0.0 else within1.toDouble() / n

    companion object {
        fun of(errors: List<Double>): FieldDiff {
            if (errors.isEmpty()) return FieldDiff(0, 0, 0, 0.0, 0.0, 0.0)
            val s = errors.sorted()
            fun q(p: Double) = s[((s.size - 1) * p).toInt()]
            return FieldDiff(s.size, s.count { it <= 0.001 }, s.count { it <= 0.01 }, q(0.5), q(0.9), s.last())
        }
    }
}

/**
 * Итог сверки баров пула с GeckoTerminal за окно.
 *
 * @param matched полные бары из цепочки, у которых есть бар GeckoTerminal на то же время.
 * @param chainOnly полный бар есть у нас, у GeckoTerminal — нет (он сделок не видел).
 * @param geckoOnly бар есть у GeckoTerminal на время, когда подписка была жива, а у нас сделок нет —
 *   пропущенные нами сделки (самый важный счётчик).
 * @param open считается только по барам, где open у нас надёжен ([ChainBar.openReliable]).
 * @param volumeRatio сумма нашего объёма / сумма объёма GeckoTerminal по совпавшим барам.
 */
data class ReconcileResult(
    val pool: String,
    val label: String,
    val matched: Int,
    val chainOnly: Int,
    val geckoOnly: Int,
    val open: FieldDiff,
    val high: FieldDiff,
    val low: FieldDiff,
    val close: FieldDiff,
    val volume: FieldDiff,
    val volumeRatio: Double,
    val chainTrades: Int,
)

/**
 * Сверка баров из цепочки с барами GeckoTerminal того же пула и таймфрейма (одинаковое правило open =
 * прошлый close). Сравниваются только полные бары ([BarSource.CHAIN]) и бары GeckoTerminal внутри времени,
 * когда подписка была жива. Чистая функция — без сети.
 */
object CandleReconciler {

    /**
     * @param chain серия из цепочки ([ChainBarSeries.bars]) — берутся только бары [BarSource.CHAIN].
     * @param gecko закрытые бары GeckoTerminal того же пула и таймфрейма.
     * @param covered покрыт ли бар подпиской целиком (для [ReconcileResult.geckoOnly]).
     * @param fromMs/toMs окно сверки по времени открытия бара.
     */
    fun compare(
        pool: String,
        label: String,
        chain: List<ChainBar>,
        gecko: List<Candle>,
        covered: (openMs: Long) -> Boolean,
        fromMs: Long = Long.MIN_VALUE,
        toMs: Long = Long.MAX_VALUE,
    ): ReconcileResult {
        val ours = chain.filter { it.source == BarSource.CHAIN && it.candle.openTimeMs in fromMs..toMs }
            .associateBy { it.candle.openTimeMs }
        val theirs = gecko.filter { it.openTimeMs in fromMs..toMs }.associateBy { it.openTimeMs }
        val o = ArrayList<Double>()
        val h = ArrayList<Double>()
        val l = ArrayList<Double>()
        val c = ArrayList<Double>()
        val v = ArrayList<Double>()
        var volOurs = 0.0
        var volTheirs = 0.0
        var matched = 0
        var chainOnly = 0
        var trades = 0
        for ((t, bar) in ours) {
            trades += bar.trades
            val g = theirs[t]
            if (g == null) {
                chainOnly++
                continue
            }
            matched++
            val m = bar.candle
            if (bar.openReliable) o += rel(m.open, g.open)
            h += rel(m.high, g.high)
            l += rel(m.low, g.low)
            c += rel(m.close, g.close)
            if (g.volume > 0) v += rel(m.volume, g.volume)
            volOurs += m.volume
            volTheirs += g.volume
        }
        val geckoOnly = theirs.keys.count { it !in ours && covered(it) }
        return ReconcileResult(
            pool = pool,
            label = label,
            matched = matched,
            chainOnly = chainOnly,
            geckoOnly = geckoOnly,
            open = FieldDiff.of(o),
            high = FieldDiff.of(h),
            low = FieldDiff.of(l),
            close = FieldDiff.of(c),
            volume = FieldDiff.of(v),
            volumeRatio = if (volTheirs > 0) volOurs / volTheirs else 0.0,
            chainTrades = trades,
        )
    }

    /** Сводка по многим пулам: ошибки собираются заново по всем барам (а не средним по пулам). */
    fun total(results: List<ReconcileResult>): Totals {
        val matched = results.sumOf { it.matched }
        return Totals(
            pools = results.count { it.matched > 0 },
            matched = matched,
            chainOnly = results.sumOf { it.chainOnly },
            geckoOnly = results.sumOf { it.geckoOnly },
            closeWithin01 = results.sumOf { it.close.within01 },
            closeWithin1 = results.sumOf { it.close.within1 },
            highWithin1 = results.sumOf { it.high.within1 },
            lowWithin1 = results.sumOf { it.low.within1 },
            openWithin1 = results.sumOf { it.open.within1 },
            openN = results.sumOf { it.open.n },
            volumeWithin1 = results.sumOf { it.volume.within1 },
            volumeN = results.sumOf { it.volume.n },
        )
    }

    data class Totals(
        val pools: Int,
        val matched: Int,
        val chainOnly: Int,
        val geckoOnly: Int,
        val closeWithin01: Int,
        val closeWithin1: Int,
        val highWithin1: Int,
        val lowWithin1: Int,
        val openWithin1: Int,
        val openN: Int,
        val volumeWithin1: Int,
        val volumeN: Int,
    )

    private fun rel(ours: Double, theirs: Double): Double = if (theirs == 0.0) 0.0 else abs(ours - theirs) / abs(theirs)
}
