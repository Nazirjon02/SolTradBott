package tj.khujand.solana.trading.bot.exchange.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.exchange.dex.Candle

/** Часы слотов, учёт кредитов, бюджет подписок и сверка — чистая логика без сети. */
class ChainClockBudgetTest {

    // ─── SlotClock ───────────────────────────────────────────────────────────

    @Test
    fun `время слота — точное у опоры, интерполяция между опорами, наклон за краем`() {
        val c = SlotClock()
        assertNull(c.estimateMs(100))
        // Реальные пары (слот, время блока) из событий PumpSwap 2026-11-11.
        c.anchor(468_136_673, 1_794_402_954)
        c.anchor(468_136_711, 1_794_402_962)
        c.anchor(468_136_792, 1_794_402_979)
        assertEquals(1_794_402_962_000, c.estimateMs(468_136_711))
        // 468136718 — между опорами: 962 + 7/81·17 ≈ 963,5 → в событии того слота было 964
        val mid = c.estimateMs(468_136_718)!!
        assertTrue(mid in 1_794_402_963_000..1_794_402_964_000, "получили $mid")
        // За краем — по наклону опор (короткий отрезок — 400 мс на слот).
        assertEquals(1_794_402_979_000 + 4_000, c.estimateMs(468_136_802))
    }

    @Test
    fun `наклон по длинному отрезку опор`() {
        val c = SlotClock()
        c.anchor(1_000, 10_000)
        c.anchor(1_300, 10_123) // 410 мс на слот
        assertEquals(410.0, c.msPerSlot(), 0.001)
        // 10 слотов × 410 мс = 4,1 с; время блока — целые секунды, оценка тоже.
        assertEquals(10_127_000, c.estimateMs(1_310))
    }

    // ─── Учёт кредитов ───────────────────────────────────────────────────────

    @Test
    fun `кредиты — 2 за 100 000 байт, плюс соединения и HTTP`() {
        var now = 1_000L * DAY_MS
        val u = HeliusUsage { now }
        repeat(10) { u.onMessage(50_000) } // 500 000 байт = 10 кредитов
        u.onConnection()
        u.onHttpCall()
        u.onHttpCall()
        assertEquals(13.0, u.creditsToday(), 1e-9)
        now += DAY_MS // новые сутки UTC — счёт сначала
        assertEquals(0.0, u.creditsToday(), 1e-9)
        assertEquals(13.0, u.snapshot().creditsTotal, 1e-9)
    }

    @Test
    fun `скорость в окне и прогноз на сутки`() {
        val w = RateWindow()
        w.start(0)
        for (s in 0 until 120) w.add(1_000, s * 1_000L) // 1 КБ/с две минуты
        assertEquals(1_000.0, w.rate(120_000), 20.0)
        assertEquals(1_728.0, w.creditsPerDay(120_000), 40.0) // 86,4 МБ/сутки
    }

    // ─── Бюджет ──────────────────────────────────────────────────────────────

    private fun cost(pool: String, perDay: Double, measuredMs: Long = 120_000L, bytes: Long = 0L) =
        CreditBudget.PoolCost(pool, perDay, measuredMs, bytes)

    @Test
    fun `дорогой пул снимается после замера, дешёвые остаются`() {
        val d = CreditBudget.decide(
            listOf(cost("hold", 53_000.0), cost("payday", 1_255.0), cost("jeff", 627.0)),
            dailyLimit = 25_000, usedToday = 0.0, msLeftToday = DAY_MS / 2,
        )
        assertEquals(mapOf("hold" to CreditBudget.Reason.EXPENSIVE), d.drop)
        assertTrue(d.admit > 0)
    }

    @Test
    fun `взрыв трафика на замере — снимается, не дожидаясь минуты`() {
        // mihao: ~180 КБ/с — за 5 с уже 900 КБ = 18 кредитов при доле пула 5 000/сутки (3,5 за минуту).
        val d = CreditBudget.decide(
            listOf(cost("mihao", 0.0, measuredMs = 5_000L, bytes = 900_000L)),
            dailyLimit = 25_000, usedToday = 0.0, msLeftToday = DAY_MS,
        )
        assertEquals(CreditBudget.Reason.EXPENSIVE, d.drop["mihao"])
    }

    @Test
    fun `сумма не влезает — снимаются самые дорогие, лимит суток — снимается всё`() {
        val many = (1..10).map { cost("p$it", 2_500.0 + it * 100) } // каждый < 20% лимита, вместе ~30 тыс.
        val d = CreditBudget.decide(many, dailyLimit = 25_000, usedToday = 0.0, msLeftToday = DAY_MS)
        assertTrue(d.drop.values.all { it == CreditBudget.Reason.OVER_BUDGET })
        assertTrue("p10" in d.drop && "p1" !in d.drop)
        val kept = many.filter { it.pool !in d.drop }.sumOf { it.creditsPerDay }
        assertTrue(kept <= 25_000 * CreditBudget.TOTAL_SHARE)
        // Сняты p8–p10 (20 300 из 22 500): в остаток влезают два новых пула с неизвестной ценой.
        assertEquals(setOf("p8", "p9", "p10"), d.drop.keys)
        assertEquals(2, d.admit)

        val stop = CreditBudget.decide(many, dailyLimit = 25_000, usedToday = 25_001.0, msLeftToday = DAY_MS / 3)
        assertTrue(stop.stopAll)
        assertEquals(many.size, stop.drop.size)
    }

    @Test
    fun `конец суток — остаток лимита тратится смелее`() {
        val pools = listOf(cost("a", 4_000.0), cost("b", 4_000.0))
        // Потрачено почти всё, а до конца суток час: 8 000 × 1/24 ≈ 333 кредита — влезает в остаток 500.
        val late = CreditBudget.decide(pools, dailyLimit = 25_000, usedToday = 24_500.0, msLeftToday = DAY_MS / 24)
        assertTrue(late.drop.isEmpty())
        // То же потрачено, но до конца суток полдня — не влезает.
        val early = CreditBudget.decide(pools, dailyLimit = 25_000, usedToday = 24_500.0, msLeftToday = DAY_MS / 2)
        assertTrue(early.drop.isNotEmpty())
        assertTrue(early.drop.values.all { it == CreditBudget.Reason.OVER_BUDGET })
    }

    // ─── Сверка ──────────────────────────────────────────────────────────────

    @Test
    fun `сверка считает совпадения, пропуски и лишние бары`() {
        fun c(t: Long, close: Double, vol: Double) = Candle(t, close, close, close, close, vol)
        val chain = listOf(
            ChainBar(c(0, 1.0, 10.0), BarSource.CHAIN, 3, openReliable = false),
            ChainBar(c(300_000, 1.005, 10.0), BarSource.CHAIN, 2, openReliable = true),
            ChainBar(c(600_000, 2.0, 5.0), BarSource.CHAIN, 1, openReliable = true), // у GT нет
            ChainBar(c(900_000, 1.0, 1.0), BarSource.PARTIAL, 1, openReliable = true), // неполный — не сверяем
        )
        val gecko = listOf(
            c(0, 1.0, 10.0),
            c(300_000, 1.0, 11.0),
            c(900_000, 1.0, 1.0),
            c(1_200_000, 1.0, 1.0), // подписка жива, а сделок у нас нет — пропуск
        )
        val r = CandleReconciler.compare("p", "P", chain, gecko, covered = { it >= 0 })
        assertEquals(2, r.matched)
        assertEquals(1, r.chainOnly)
        assertEquals(2, r.geckoOnly) // 900 000 (у нас неполный) и 1 200 000
        assertEquals(2, r.close.n)
        assertEquals(2, r.close.within1)
        assertEquals(1, r.close.within01)
        assertEquals(1, r.open.n) // open первого бара ненадёжен
        assertEquals(20.0 / 21.0, r.volumeRatio, 1e-9)
        val t = CandleReconciler.total(listOf(r))
        assertEquals(2, t.matched)
        assertEquals(1, t.volumeWithin1)
    }
}
