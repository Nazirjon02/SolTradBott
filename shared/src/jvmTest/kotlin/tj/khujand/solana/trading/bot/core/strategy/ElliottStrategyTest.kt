package tj.khujand.solana.trading.bot.core.strategy

import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Волны Эллиотта (порт ElliottStrategyTest из MRX): каждый тест ломает РОВНО одно правило или
 * условие и проверяет его КОД.
 *
 * Свечи строятся по ломаной: каждый бар проходит 0.5 цены с фитилями по 0.1, поэтому
 * ATR ≈ 0.7, порог волны (2.5 ATR) ≈ 1.75, порог младшей степени (0.382) ≈ 0.67.
 *
 * Эталон «вход в 3-ю» (лонг):
 * ```
 *   110 → 100   спуск к началу волны 1
 *   100 → 110   волна 1, объём 200
 *   110 → 104   волна 2: откат 60 %, объём 100 (тише первой)
 *   104 → 106.5 старт волны 3: разворот подтверждён, вход
 *   стоп за началом волны 1 (правило 1), цель 104 + 1.618 × 10
 * ```
 */
class ElliottStrategyTest {

    private data class Leg(val to: Double, val bars: Int, val volume: Double = 100.0)

    private val tfMs = 15 * 60_000L

    private fun path(start: Double, legs: List<Leg>, mirror: Double? = null): List<Candle> {
        var stamp = 1_700_000_000_000L
        var price = start
        val out = mutableListOf<Candle>()
        for (leg in legs) {
            val step = (leg.to - price) / leg.bars
            repeat(leg.bars) {
                val open = price
                val close = price + step
                stamp += tfMs
                out += Candle(stamp, open, maxOf(open, close) + 0.1, minOf(open, close) - 0.1, close, leg.volume)
                price = close
            }
        }
        // Зеркало: тот же счёт вниз.
        if (mirror != null) return out.map {
            Candle(it.openTimeMs, mirror - it.open, mirror - it.low, mirror - it.high, mirror - it.close, it.volume)
        }
        return out
    }

    private fun wave3(
        wave1Volume: Double = 200.0,
        wave2Volume: Double = 100.0,
        wave2To: Double = 104.0,
        wave3Bars: Int = 5,
        wave1: List<Leg> = listOf(Leg(110.0, 20, wave1Volume)),
        wave2: List<Leg>? = null,
    ): List<Candle> {
        val w2 = wave2 ?: listOf(Leg(wave2To, ((110.0 - wave2To) / 0.5).toInt(), wave2Volume))
        val w3Start = w2.last().to
        return path(110.0, listOf(Leg(100.0, 20)) + wave1 + w2 + Leg(w3Start + 0.5 * wave3Bars, wave3Bars))
    }

    /**
     * Эталоны проверяют вход на конце волны 2 (TURN): так в каждом из них участвует ровно одна
     * точка входа. Вход на пробое (дефолт стратегии) проверяется отдельными тестами.
     */
    private val base = StrategyConfig(id = "test", type = StrategyType.ELLIOTT.name, timeframe = "15m", ewWave3Entry = "TURN")

    private fun evaluate(candles: List<Candle>, cfg: StrategyConfig = base): ElliottEvaluation =
        ElliottStrategy(cfg).evaluate(candles, candles.last().close, ElliottHigherDegree.off())

    private fun ElliottEvaluation.failedText() = failed.map { "${it.check}: ${it.detail}" }.toString()

    private fun assertFailedExactly(e: ElliottEvaluation, check: ElliottCheck) {
        assertNull(e.blocker, "разбор не должен упираться в блокер: ${e.blocker}")
        assertEquals(listOf(check), e.failed.map { it.check }, "должно отвалиться только $check: ${e.failedText()}")
        assertNull(e.entry)
    }

    private fun candidate(price: Double) = TokenCandidate(
        mint = "MINT", symbol = "EW", name = "Elliott", pairAddress = "POOL", dexId = "raydium",
        priceUsd = price, liquidityUsd = 50_000.0, marketCap = 500_000.0, volumeH1Usd = 10_000.0,
        buysH1 = 10, sellsH1 = 5, priceChangeM5 = 0.0, priceChangeH1 = 0.0,
        tokenAgeMinutes = 6_000L, score = 0.0, scannedAt = 0L,
    )

    // ─── Вход в волну 3 ─────────────────────────────────────────────────────

    @Test
    fun `эталон — лонг в волну 3 со стопом за началом волны 1`() {
        val e = evaluate(wave3())
        val entry = assertNotNull(e.entry, "эталон должен дать вход: ${e.failedText()}")
        assertEquals(ElliottSetup.WAVE3, entry.setup)
        // Стоп — за началом волны 1 (99.9) с буфером 0.2 ATR.
        assertTrue(entry.stop < 99.9 && entry.stop > 99.5, "стоп ${entry.stop}")
        // Цель — конец волны 2 (103.9) + 1.618 × волна 1 (10.2).
        assertEquals(103.9 + 1.618 * 10.2, entry.target, 1e-6)
        assertTrue(entry.confidence >= MIN_CONFIDENCE, "уверенность ниже порога движка")
        assertTrue(e.checks.first { it.check == ElliottCheck.COUNT }.detail.contains("вход в волну 3"))
    }

    @Test
    fun `analyze отдаёт сигнал с уровнями разбора и ценой кандидата`() = runBlocking<Unit> {
        val candles = wave3()
        val price = candles.last().close
        val entry = assertNotNull(evaluate(candles).entry)
        val signal = assertNotNull(ElliottStrategy(base).analyze(candidate(price), candles, emptyList()))
        assertEquals("MINT", signal.mint)
        assertEquals("POOL", signal.pairAddress)
        assertEquals(price, signal.entryPrice)
        assertEquals(entry.stop, signal.stopLoss)
        assertEquals(entry.target, signal.takeProfit)
        assertEquals(entry.confidence, signal.confidence)
    }

    @Test
    fun `волна 2 ушла за начало волны 1 — RULE_W2`() {
        assertFailedExactly(evaluate(wave3(wave2To = 99.0)), ElliottCheck.RULE_W2)
    }

    @Test
    fun `объём второй волны выше первой и нужны все ориентиры — GUIDELINES`() {
        assertFailedExactly(evaluate(wave3(wave2Volume = 300.0), base.copy(ewMinGuidelines = 3)), ElliottCheck.GUIDELINES)
    }

    @Test
    fun `ориентир не правило — один промах при пороге 2 вход не снимает`() {
        assertNotNull(evaluate(wave3(wave2Volume = 300.0)).entry)
    }

    @Test
    fun `разворот подтверждён давно — FRESH`() {
        assertFailedExactly(evaluate(wave3(wave3Bars = 7)), ElliottCheck.FRESH)
    }

    @Test
    fun `R_R хуже порога — REWARD_RISK`() {
        assertFailedExactly(evaluate(wave3(), base.copy(ewMinRewardRisk = 3.0)), ElliottCheck.REWARD_RISK)
    }

    @Test
    fun `стоп по правилу дальше Stop Loss — STOP_DISTANCE`() {
        // Вход 106.5, стоп ≈ 99.76 — это −6.3 %, а потолок 5 %.
        assertFailedExactly(evaluate(wave3(), base.copy(stopLossPercent = 5.0)), ElliottCheck.STOP_DISTANCE)
    }

    @Test
    fun `зеркальный счёт — шорт, на споте не торгуется — SIDE_ALLOWED`() {
        val candles = path(
            110.0,
            listOf(Leg(100.0, 20), Leg(110.0, 20, 200.0), Leg(104.0, 12), Leg(106.5, 5)),
            mirror = 300.0
        )
        val e = evaluate(candles)
        assertFailedExactly(e, ElliottCheck.SIDE_ALLOWED)
        assertTrue(e.checks.first { it.check == ElliottCheck.COUNT }.detail.contains("шорт"))
    }

    @Test
    fun `строго по книге прямая волна не пятёрка — STRUCTURE`() {
        assertFailedExactly(evaluate(wave3(), base.copy(ewStructure = "STRICT")), ElliottCheck.STRUCTURE)
    }

    @Test
    fun `тройка на месте волны 1 отвергается и в мягком режиме — STRUCTURE`() {
        // 100 → 105, откат на 1 (больше младшей степени, меньше старшей), → 110: три ноги.
        val three = listOf(Leg(105.0, 10, 200.0), Leg(104.0, 2, 200.0), Leg(110.0, 12, 200.0))
        assertFailedExactly(evaluate(wave3(wave1 = three)), ElliottCheck.STRUCTURE)
    }

    @Test
    fun `пятёрка и зигзаг проходят строгий режим`() {
        val five = listOf(
            Leg(103.0, 6, 200.0), Leg(102.0, 2, 200.0), Leg(107.0, 10, 200.0),
            Leg(106.0, 2, 200.0), Leg(110.0, 8, 200.0)
        )
        val zigzag = listOf(Leg(106.0, 8), Leg(107.0, 2), Leg(104.0, 6))
        val e = evaluate(wave3(wave1 = five, wave2 = zigzag), base.copy(ewStructure = "STRICT"))
        val structure = e.checks.first { it.check == ElliottCheck.STRUCTURE }
        assertTrue(structure.passed, structure.detail)
        assertTrue(structure.detail.contains("импульс (5)"), structure.detail)
        assertTrue(structure.detail.contains("зигзаг (3)"), structure.detail)
        assertNotNull(e.entry, e.failedText())
    }

    @Test
    fun `вход на пробое конца волны 1 — стоп за концом волны 2`() {
        // 2-я откатила 50 % (до 105), 3-я на 11-м баре закрылась за концом 1-й (110.1).
        val candles = wave3(wave2To = 105.0, wave3Bars = 11)
        // На повороте сделка уже протухла: конец 2-й подтвердился 7 баров назад.
        val late = evaluate(candles)
        assertNull(late.entry)
        assertTrue(ElliottCheck.FRESH in late.failed.map { it.check }, late.failedText())

        val entry = assertNotNull(evaluate(candles, base.copy(ewWave3Entry = "BREAK")).entry)
        assertTrue(entry.stop < 104.9 && entry.stop > 104.5, "стоп ${entry.stop}")

        // Пробой — дефолт стратегии: конфиг без явного режима ведёт себя так же.
        val bare = StrategyConfig(id = "t", type = StrategyType.ELLIOTT.name, timeframe = "15m")
        assertEquals(entry, evaluate(candles, bare).entry)
    }

    @Test
    fun `пробоя конца волны 1 ещё нет — FRESH`() {
        assertFailedExactly(evaluate(wave3(), base.copy(ewWave3Entry = "BREAK")), ElliottCheck.FRESH)
    }

    // ─── Вход в волну 5 ─────────────────────────────────────────────────────

    private fun wave5(wave4To: Double = 127.0, wave3Volume: Double = 300.0) = path(
        110.0,
        listOf(
            Leg(100.0, 20),
            Leg(120.0, 40, 200.0),            // 1
            Leg(108.0, 24, 100.0),            // 2: откат 60 %
            Leg(145.0, 74, wave3Volume),      // 3: растянутая
            Leg(wave4To, ((145.0 - wave4To) / 0.5).toInt(), 100.0), // 4
            Leg(wave4To + 2.5, 5)             // старт 5-й
        )
    )

    private val wave5Only = base.copy(ewTradeWave3 = false)

    @Test
    fun `эталон — лонг в волну 5 со стопом за концом волны 1`() {
        val e = evaluate(wave5(), wave5Only)
        val entry = assertNotNull(e.entry, e.failedText())
        assertEquals(ElliottSetup.WAVE5, entry.setup)
        assertTrue(entry.stop < 120.1 && entry.stop > 119.7, "стоп ${entry.stop}")
        // Цель — равенство с волной 1 от конца волны 4.
        assertEquals(126.9 + (120.1 - 99.9), entry.target, 1e-6)
    }

    @Test
    fun `волна 4 зашла на территорию волны 1 — RULE_W4_OVERLAP`() {
        // Глубже конца 1-й (120): правило 3 нарушено. Ориентиры не держим, R:R — со стопом за 4-й.
        val e = evaluate(wave5(wave4To = 118.0), wave5Only.copy(ewMinGuidelines = 0, ewStopAnchor = "PIVOT"))
        assertFailedExactly(e, ElliottCheck.RULE_W4_OVERLAP)
    }

    // ─── Отличия DRX: формирующийся бар и старшая степень ───────────────────

    @Test
    fun `формирующийся бар не участвует в разметке`() = runBlocking<Unit> {
        // Разворот подтверждён 2 бара назад — в окне 3. Ещё один закрытый бар его бы вывел из окна.
        val closed = wave3(wave3Bars = 6)
        val last = closed.last()
        val withForming = closed + Candle(last.openTimeMs + tfMs, last.close, last.close + 0.4, last.close - 0.1, last.close + 0.3, 50.0)
        val price = last.close

        val inside = ElliottStrategy(base, nowMs = { withForming.last().openTimeMs + 60_000L })
        assertNotNull(inside.analyze(candidate(price), withForming, emptyList()), "бар ещё идёт — его отбрасываем")

        val afterClose = ElliottStrategy(base, nowMs = { withForming.last().openTimeMs + tfMs })
        val e = afterClose.evaluateFull(candidate(price), withForming, emptyList())
        assertFailedExactly(e, ElliottCheck.FRESH)
    }

    /** Старший ТФ: [legs] от 100 — направление последней ноги и есть текущая волна старшей степени. */
    private fun htf(vararg legs: Leg) = path(100.0, legs.toList())

    private val withHtf = base.copy(ewRequireHtf = true)

    @Test
    fun `старшая степень вверх — вход есть`() = runBlocking<Unit> {
        val candles = wave3()
        val up = htf(Leg(150.0, 60), Leg(140.0, 10), Leg(180.0, 60))
        val s = ElliottStrategy(withHtf, htfSource = { _, _, _ -> up })
        assertNotNull(s.analyze(candidate(candles.last().close), candles, emptyList()))
    }

    @Test
    fun `старшая степень вниз — HIGHER_DEGREE`() = runBlocking<Unit> {
        val candles = wave3()
        val down = htf(Leg(150.0, 60), Leg(160.0, 10), Leg(120.0, 60))
        val s = ElliottStrategy(withHtf, htfSource = { _, _, _ -> down })
        assertFailedExactly(s.evaluateFull(candidate(candles.last().close), candles, emptyList()), ElliottCheck.HIGHER_DEGREE)
    }

    @Test
    fun `свечи старшего ТФ не получены — входа нет`() = runBlocking<Unit> {
        val candles = wave3()
        val s = ElliottStrategy(withHtf, htfSource = { _, _, _ -> emptyList() })
        assertFailedExactly(s.evaluateFull(candidate(candles.last().close), candles, emptyList()), ElliottCheck.HIGHER_DEGREE)
    }

    @Test
    fun `на молодой монете старшей степени ещё нет — не применяется`() = runBlocking<Unit> {
        val candles = wave3()
        val s = ElliottStrategy(withHtf, htfSource = { _, _, _ -> htf(Leg(90.0, 20)) })
        assertNotNull(s.analyze(candidate(candles.last().close), candles, emptyList()))
    }

    @Test
    fun `старший ТФ запрашивается только для готового сетапа`() = runBlocking<Unit> {
        var calls = 0
        val s = ElliottStrategy(withHtf, htfSource = { _, _, _ -> calls++; emptyList() })
        val broken = wave3(wave2To = 99.0)
        assertNull(s.analyze(candidate(broken.last().close), broken, emptyList()))
        assertEquals(0, calls, "правило нарушено — лимит GeckoTerminal не тратим")
    }

    @Test
    fun `мало свечей — блокер, а не проверки`() {
        val e = evaluate(wave3().take(30))
        assertTrue(e.blocker?.contains("мало свечей") == true, e.blocker.toString())
        assertTrue(e.checks.isEmpty())
    }

    // ─── Разметка ───────────────────────────────────────────────────────────

    private fun points(vararg prices: Double): List<ElliottPoint> =
        prices.mapIndexed { i, p -> ElliottPoint(i, p, isHigh = i % 2 == 1) }

    @Test
    fun `пятёрка с перекрытием 4 и 1 — диагональ, без него — импульс`() {
        assertEquals(ElliottShape.IMPULSE, classifyMotive(points(100.0, 105.0, 102.0, 112.0, 108.0, 115.0)))
        assertEquals(ElliottShape.DIAGONAL, classifyMotive(points(100.0, 105.0, 102.0, 109.0, 104.0, 111.0)))
        // Волна 3 самая короткая — правило нарушено.
        assertEquals(ElliottShape.IRREGULAR, classifyMotive(points(100.0, 106.0, 103.0, 107.0, 105.0, 113.0)))
        assertEquals(ElliottShape.THREE, classifyMotive(points(100.0, 105.0, 103.0, 110.0)))
    }

    @Test
    fun `тройка — зигзаг или плоская по глубине волны B`() {
        // Коррекция вниз: начало — вершина.
        fun down(vararg prices: Double) = prices.mapIndexed { i, p -> ElliottPoint(i, p, isHigh = i % 2 == 0) }
        assertEquals(ElliottShape.ZIGZAG, classifyCorrective(down(110.0, 104.0, 107.0, 102.0)))
        assertEquals(ElliottShape.FLAT, classifyCorrective(down(110.0, 104.0, 109.8, 103.0)))
        assertEquals(ElliottShape.FIVE_AGAINST, classifyCorrective(down(110.0, 104.0, 106.0, 100.0, 102.0, 98.0)))
    }
}
