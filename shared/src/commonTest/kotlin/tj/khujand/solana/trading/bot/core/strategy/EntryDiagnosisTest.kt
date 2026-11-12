package tj.khujand.solana.trading.bot.core.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.util.plural

/** «Почему нет сделок»: причины проверяются по порядку — от «бот стоит» до «сетапы отклонили». */
class EntryDiagnosisTest {

    private val now = 50_000_000L
    private val active = setOf("s1", "s2")

    private fun snapshot(
        cycles: Int = 10,
        coins: Int = 12,
        alreadyTraded: Int = 0,
        noCandles: Int = 0,
        setups: Int = 0,
        losses: Map<EntryLoss, Int> = emptyMap(),
        opened: Int = 0,
        signals: Int = 0,
        emptyScans: Int = 0,
        strategies: Map<String, StrategyCycle> = active.associateWith { StrategyCycle(now - 10_000, null, "") },
    ) = FunnelSnapshot(
        windowMinutes = 60, cycles = cycles, coins = coins, alreadyTraded = alreadyTraded, noCandles = noCandles,
        setups = setups, losses = losses, rejectedCoins = losses.values.sum(), opened = opened, signals = signals,
        emptyScans = emptyScans, lastCycleAt = now - 10_000, strategies = strategies,
    )

    private fun diagnose(
        funnel: FunnelSnapshot = snapshot(),
        status: BotStatus = BotStatus.RUNNING,
        activeIds: Set<String> = active,
        signalOnly: Boolean = false,
        nearest: WatchItem? = null,
        candlesLimited: Boolean = false,
    ) = diagnoseEntries(status, activeIds, funnel, signalOnly, nearest, candlesLimited, now)

    @Test
    fun stoppedBotComesFirst() {
        val d = diagnose(status = BotStatus.STOPPED, funnel = snapshot(opened = 3))
        assertEquals(DiagnosisTone.INFO, d.tone)
        assertTrue(d.headline.startsWith("Бот остановлен"))
    }

    @Test
    fun noActiveStrategies() {
        val d = diagnose(activeIds = emptySet())
        assertEquals(DiagnosisTone.WARN, d.tone)
        assertTrue("Нет включённых стратегий" in d.headline)
    }

    @Test
    fun pausedBot() {
        assertTrue(diagnose(status = BotStatus.PAUSED).headline.startsWith("Бот на паузе"))
    }

    @Test
    fun firstCycleNotYetDone() {
        assertTrue("первый цикл" in diagnose(funnel = snapshot(cycles = 0)).headline)
    }

    @Test
    fun openedTradesAreGoodNews() {
        val d = diagnose(funnel = snapshot(setups = 3, opened = 2))
        assertEquals(DiagnosisTone.OK, d.tone)
        assertEquals("За 60 мин открыты 2 сделки", d.headline)
    }

    @Test
    fun allStrategiesBlockedByPortfolio() {
        val blocked = mapOf(
            "s1" to StrategyCycle(now - 5_000, CycleBlock.PORTFOLIO, "3 позиции из 3"),
            "s2" to StrategyCycle(now - 8_000, CycleBlock.COOLDOWN, "ещё 90 с"),
        )
        val d = diagnose(funnel = snapshot(strategies = blocked))
        assertEquals(DiagnosisTone.WARN, d.tone)
        assertEquals("Входы закрыты лимитами портфеля: 3 позиции из 3", d.headline, "портфель важнее кулдауна")
    }

    @Test
    fun hardStopIsTheWorst() {
        val blocked = mapOf(
            "s1" to StrategyCycle(now - 5_000, CycleBlock.HARD_STOP, "убыток за день 10%"),
            "s2" to StrategyCycle(now - 5_000, CycleBlock.PORTFOLIO, "3 позиции из 3"),
        )
        val d = diagnose(funnel = snapshot(strategies = blocked))
        assertEquals(DiagnosisTone.BAD, d.tone)
        assertTrue("убыток за день 10%" in d.headline)
    }

    @Test
    fun oneWorkingStrategyMeansNotBlocked() {
        val mixed = mapOf(
            "s1" to StrategyCycle(now - 5_000, CycleBlock.COOLDOWN, "ещё 90 с"),
            "s2" to StrategyCycle(now - 5_000, null, ""),
        )
        val d = diagnose(funnel = snapshot(strategies = mixed))
        assertTrue(d.headline.startsWith("Сетапов нет"), d.headline)
    }

    @Test
    fun staleBlockIsIgnored() {
        // Кулдаун был 10 минут назад — сейчас он ничего не объясняет.
        val stale = active.associateWith { StrategyCycle(now - 10 * 60_000L, CycleBlock.COOLDOWN, "ещё 90 с") }
        assertTrue(diagnose(funnel = snapshot(strategies = stale)).headline.startsWith("Сетапов нет"))
    }

    @Test
    fun signalOnlyMode() {
        val d = diagnose(signalOnly = true, funnel = snapshot(setups = 2, signals = 2))
        assertTrue("только сигнал" in d.headline)
        assertTrue(d.hints.first().contains("2"))
    }

    @Test
    fun nothingPassesStrategyFilters() {
        val d = diagnose(funnel = snapshot(coins = 0, emptyScans = 20))
        assertEquals(DiagnosisTone.WARN, d.tone)
        assertTrue("фильтры" in d.headline)
    }

    @Test
    fun everythingAlreadyTraded() {
        val d = diagnose(funnel = snapshot(coins = 5, alreadyTraded = 5))
        assertTrue(d.headline.startsWith("Все монеты из списка уже торговались"))
    }

    @Test
    fun missingCandlesPointToTheRateLimit() {
        val d = diagnose(funnel = snapshot(coins = 10, noCandles = 6), candlesLimited = true)
        assertEquals(DiagnosisTone.WARN, d.tone)
        assertEquals("Не хватает свечей: 6 из 10 монет без данных", d.headline)
        assertTrue("слишком много запросов" in d.hints.single())
    }

    @Test
    fun noSetupsShowsTheNearestCoin() {
        val near = WatchItem("m", "BONKX", "p", "s1", "Пробой + откат", "5m", 1.0, 1.02, 5, 6, "триггер не закрылся", WatchStatus.NEAR, now)
        val d = diagnose(nearest = near)
        assertEquals("Сетапов нет: разобрано 12 монет — условия стратегий не сложились", d.headline)
        assertEquals("Ближе всех: BONKX (Пробой + откат) — 5 из 6, не хватает: триггер не закрылся", d.hints.single())
    }

    @Test
    fun rejectedSetupsNameTheMainReason() {
        val d = diagnose(funnel = snapshot(setups = 4, losses = mapOf(EntryLoss.COIN_CHECK to 3, EntryLoss.PRICE_RAN to 1)))
        assertEquals(DiagnosisTone.WARN, d.tone)
        assertEquals("Сетапов: 4, сделок нет — чаще всего мешает: проверка монеты", d.headline)
        assertTrue(d.hints[0].startsWith("Проверка монеты отклоняет вход"))
        assertEquals("Ещё: цена убежала от сигнала — 1", d.hints[1])
    }

    @Test
    fun russianPlurals() {
        assertEquals("монета", plural(1, "монета", "монеты", "монет"))
        assertEquals("монеты", plural(3, "монета", "монеты", "монет"))
        assertEquals("монет", plural(11, "монета", "монеты", "монет"))
        assertEquals("монета", plural(21, "монета", "монеты", "монет"))
        assertEquals("монет", plural(112, "монета", "монеты", "монет"))
    }

    @Test
    fun candleShortageIsTheFirstHintWhileRunning() {
        val demand = CandleDemand(pools = 20, requestsPerMinute = 7.2, poolsByTimeframeMs = mapOf(60_000L to 4, 5 * 60_000L to 16))
        val near = WatchItem("m", "BONKX", "p", "s1", "Пробой + откат", "5m", 1.0, 1.02, 5, 6, "триггер не закрылся", WatchStatus.NEAR, now)
        val d = diagnoseEntries(BotStatus.RUNNING, active, snapshot().copy(candleDemand = demand), false, near, false, now, candleBudgetPerMinute = 4.0)
        assertTrue(d.headline.startsWith("Сетапов нет"), "главная причина прежняя: ${d.headline}")
        assertEquals(
            "Свечей не хватает: стратегиям нужно около 7,2 запроса в минуту (20 монет), источник даёт 4 — часть монет " +
                "разбирается по свечам с опозданием. Больше всего просит ТФ 1m (4 монеты): перенесите эту стратегию на ТФ " +
                "крупнее или выключите её.",
            d.hints.first(),
        )
        assertTrue(d.hints[1].startsWith("Ближе всех: BONKX"))
    }

    @Test
    fun noShortageHintWhenSourceKeepsUpOrBotStands() {
        val enough = CandleDemand(pools = 10, requestsPerMinute = 2.0, poolsByTimeframeMs = mapOf(5 * 60_000L to 10))
        assertTrue(candleShortageHint(enough, 4.0) == null)
        val tooMuch = CandleDemand(pools = 30, requestsPerMinute = 6.0, poolsByTimeframeMs = mapOf(5 * 60_000L to 30))
        assertEquals(
            "Свечей не хватает: стратегиям нужно около 6 запросов в минуту (30 монет), источник даёт 4 — часть монет " +
                "разбирается по свечам с опозданием. Сократите число стратегий или монет либо возьмите ТФ крупнее.",
            candleShortageHint(tooMuch, 4.0),
        )
        // Бот стоит — свечи никто не просит, подсказка не нужна.
        val stopped = diagnoseEntries(BotStatus.STOPPED, active, snapshot().copy(candleDemand = tooMuch), false, null, false, now, 4.0)
        assertTrue(stopped.hints.none { it.startsWith("Свечей не хватает") })
    }
}
