package tj.khujand.solana.trading.bot.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.exchange.dex.CandleFeedStatus
import tj.khujand.solana.trading.bot.shouldResumeOnLaunch

/** `/health`, список ошибок сессии и автозапуск: то, по чему видно, что бот не торгует, хотя должен. */
class HealthTest {

    private val now = 100 * 60_000L
    private fun minAgo(m: Int) = now - m * 60_000L

    @Test
    fun stoppedButLeftRunningIsDegraded() {
        val r = healthReport(BotStatus.STOPPED, BotStatus.RUNNING.name, "DEMO", lastOkAt = null, engineStartedAt = 0L, nowMs = now)
        assertEquals("degraded", r.status)
        assertTrue(r.problems.single().contains("RUNNING"))
    }

    @Test
    fun deliberatelyStoppedBotIsHealthy() {
        val r = healthReport(BotStatus.STOPPED, BotStatus.STOPPED.name, "DEMO", lastOkAt = minAgo(60), engineStartedAt = minAgo(90), nowMs = now)
        assertEquals("ok", r.status)
        assertEquals(3600L, r.lastOkAgeSeconds)
    }

    @Test
    fun runningWithFreshAnswersIsHealthy() {
        val r = healthReport(BotStatus.RUNNING, BotStatus.RUNNING.name, "REAL", lastOkAt = minAgo(1), engineStartedAt = minAgo(30), nowMs = now)
        assertEquals("ok", r.status)
        assertEquals("REAL", r.mode)
    }

    @Test
    fun runningWithoutAnswersForLongIsDegraded() {
        val r = healthReport(BotStatus.RUNNING, BotStatus.RUNNING.name, "DEMO", lastOkAt = minAgo(15), engineStartedAt = minAgo(30), nowMs = now)
        assertEquals("degraded", r.status)
        assertTrue(r.problems.single().startsWith("нет успешных ответов DEX-API 15 мин"), r.problems.single())
    }

    @Test
    fun runningThatNeverGotAnAnswerIsDegraded() {
        val r = healthReport(BotStatus.RUNNING, BotStatus.RUNNING.name, "DEMO", lastOkAt = null, engineStartedAt = minAgo(12), nowMs = now)
        assertTrue(r.problems.single().contains("ещё ни разу"), r.problems.single())
    }

    @Test
    fun freshRestartIsNotStaleBecauseOfOldAnswer() {
        // Последний ответ — до остановки, движок запущен минуту назад: ещё рано звать на помощь.
        val r = healthReport(BotStatus.RUNNING, BotStatus.RUNNING.name, "DEMO", lastOkAt = minAgo(40), engineStartedAt = minAgo(1), nowMs = now)
        assertEquals("ok", r.status)
    }

    @Test
    fun dataProblemsDegradeOnlyRunningBot() {
        val problem = listOf("ключ CoinGecko отклонён")
        val running = healthReport(BotStatus.RUNNING, BotStatus.RUNNING.name, "DEMO", minAgo(1), minAgo(30), now, dataProblems = problem)
        assertEquals("degraded", running.status)
        assertEquals(problem, running.problems)
        val stopped = healthReport(BotStatus.STOPPED, BotStatus.STOPPED.name, "DEMO", minAgo(1), minAgo(30), now, dataProblems = problem)
        assertEquals("ok", stopped.status)
    }

    @Test
    fun candleFeedProblemsNameRejectedKeyAndStarvation() {
        assertTrue(candleFeedProblems(CandleFeedStatus(lastOkAtMs = minAgo(1)), now).isEmpty())
        assertTrue(candleFeedProblems(CandleFeedStatus(keyRejected = true), now).single().contains("отклонён"))

        // Единичный отказ по лимиту — не проблема: после него запросы ждут паузу и идут дальше.
        val oneRefusal = CandleFeedStatus(rateLimitHits = 1, lastRateLimitAtMs = minAgo(2), lastOkAtMs = minAgo(1))
        assertTrue(candleFeedProblems(oneRefusal, now).isEmpty())

        // Свечи приходили, но уже 15 минут источник только отказывает.
        val starved = CandleFeedStatus(rateLimitHits = 9, lastRateLimitAtMs = minAgo(1), lastOkAtMs = minAgo(15))
        assertTrue(candleFeedProblems(starved, now).single().startsWith("свечи не приходят 15 мин"))
    }

    @Test
    fun errorsListKeepsTextsSeparatelyFromFeed() {
        val log = ActivityLog(capacity = 2, errorCapacity = 3)
        log.error("e1")
        log.requestFailed("сбой запроса")
        log.requestFailed() // без текста — только счётчик
        log.info("i1")
        log.info("i2") // лента (2 события) вытеснила обе ошибки
        assertEquals(listOf("e1", "сбой запроса"), log.errors.value.map { it.message })
        assertEquals(2, log.stats.value.errorCount)
        assertTrue(log.events.value.none { it.level == ActivityLevel.ERROR })

        repeat(3) { log.error("x$it") }
        assertEquals(listOf("x0", "x1", "x2"), log.errors.value.map { it.message })
        log.clear()
        assertTrue(log.errors.value.isEmpty())
    }

    @Test
    fun resumeOnLaunchOnlyWhenEnabledAndLeftTrading() {
        assertTrue(shouldResumeOnLaunch(true, BotStatus.STOPPED, BotStatus.RUNNING.name))
        assertTrue(shouldResumeOnLaunch(true, BotStatus.STOPPED, BotStatus.PAUSED.name))
        assertEquals(false, shouldResumeOnLaunch(false, BotStatus.STOPPED, BotStatus.RUNNING.name))
        assertEquals(false, shouldResumeOnLaunch(true, BotStatus.STOPPED, BotStatus.STOPPED.name))
        assertEquals(false, shouldResumeOnLaunch(true, BotStatus.STOPPED, null))
        assertEquals(false, shouldResumeOnLaunch(true, BotStatus.RUNNING, BotStatus.RUNNING.name))
    }
}
