package tj.khujand.solana.trading.bot.exchange.dex

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Очередь запросов к API с лимитом «N в минуту»: запросы уходят не чаще одного в [intervalMs], по очереди
 * (кто раньше попросил — тот раньше и отправит), а после отказа сервера по лимиту все ждут [pauseUntil].
 *
 * Ровный шаг, а не «пачка и пауза»: сервер считает запросы в скользящем окне, и пачка на стыке двух окон
 * превысила бы лимит. Часы и ожидание передаются параметрами — тесты идут без реального времени.
 *
 * @param intervalMs шаг между запросами; читается на каждом запросе (меняется, когда появляется ключ API).
 */
class RequestLimiter(
    private val intervalMs: () -> Long,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var nextSlotAt = 0L
    private var pausedUntil = 0L

    /**
     * Дожидается своей очереди. false — ждать пришлось бы дольше [maxWaitMs]: запрос не отправляем,
     * вызывающий обходится тем, что у него уже есть.
     */
    suspend fun acquire(maxWaitMs: Long): Boolean {
        val deadline = nowMs() + maxWaitMs.coerceAtLeast(0)
        while (true) {
            val wait = lock.withLock {
                val now = nowMs()
                val slot = maxOf(now, nextSlotAt, pausedUntil)
                if (slot > deadline) return false
                nextSlotAt = slot + intervalMs().coerceAtLeast(0)
                slot - now
            }
            if (wait > 0) sleep(wait)
            // Пока ждали, сервер мог отказать соседнему запросу — тогда встаём в очередь заново, после паузы.
            if (nowMs() >= lock.withLock { pausedUntil }) return true
        }
    }

    /** Сервер ответил «слишком много запросов»: до [untilMs] никто ничего не отправляет. */
    suspend fun pauseUntil(untilMs: Long) = lock.withLock {
        if (untilMs > pausedUntil) pausedUntil = untilMs
    }
}
