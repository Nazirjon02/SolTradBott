package tj.khujand.solana.trading.bot.core.engine

import kotlinx.serialization.Serializable
import tj.khujand.solana.trading.bot.data.BotStatus

/**
 * Столько работающий движок может обходиться без единого успешного ответа DEX-API. Цикл стратегии
 * спрашивает сканер раз в ~30 с, монитор сделок — цены раз в 15 с: десять минут тишины — это уже
 * не задержка, а поломка (сеть, лимит, упавший API).
 */
const val API_STALE_MS = 10 * 60_000L

/**
 * Ответ `/health` для внешнего мониторинга (UptimeRobot, docker healthcheck): [status] «ok» или «degraded»,
 * при «degraded» сервер отвечает HTTP 503, а в [problems] — что именно не так.
 * @param lastOkAgeSeconds сколько секунд назад был последний успешный ответ DEX-API; null — ещё не было.
 */
@Serializable
data class HealthReport(
    val status: String,
    val bot: String,
    val mode: String,
    val lastOkAgeSeconds: Long?,
    val problems: List<String>,
)

/**
 * Состояние бота для мониторинга. Чистая функция — время и состояния передаются параметрами.
 *
 * Свежесть ответов API спрашивается ТОЛЬКО с работающего движка: у остановленного бота запросов нет, и
 * намеренная остановка иначе вечно краснела бы — мониторинг привыкает к красному ровно к тому моменту,
 * когда оно означает настоящую поломку.
 *
 * @param desiredState в каком состоянии бота оставили (имя [BotStatus], см. SettingsStore.getDesiredState).
 * @param engineStartedAt когда движок последний раз запущен; 0 — не запускался.
 */
fun healthReport(
    status: BotStatus,
    desiredState: String?,
    mode: String,
    lastOkAt: Long?,
    engineStartedAt: Long,
    nowMs: Long,
): HealthReport {
    val problems = buildList {
        if (status == BotStatus.STOPPED &&
            (desiredState == BotStatus.RUNNING.name || desiredState == BotStatus.PAUSED.name)
        ) {
            add("движок остановлен, хотя его оставили в состоянии $desiredState")
        }
        if (status == BotStatus.RUNNING) {
            // Отсчёт — от последнего ответа или от запуска движка, что позже: ответ, полученный до
            // остановки, не делает свежезапущенный движок «зависшим».
            val since = maxOf(lastOkAt ?: 0L, engineStartedAt)
            if (since > 0 && nowMs - since > API_STALE_MS) {
                val minutes = (nowMs - since) / 60_000
                add(
                    if (lastOkAt == null || lastOkAt < engineStartedAt) "движок работает $minutes мин, но DEX-API ещё ни разу не ответил"
                    else "нет успешных ответов DEX-API $minutes мин"
                )
            }
        }
    }
    return HealthReport(
        status = if (problems.isEmpty()) "ok" else "degraded",
        bot = status.name,
        mode = mode,
        lastOkAgeSeconds = lastOkAt?.let { (nowMs - it) / 1000 },
        problems = problems,
    )
}
