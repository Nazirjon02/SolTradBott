package tj.khujand.solana.trading.bot.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.exchange.dex.CandleFeedStatus

/** Об отказах по лимиту напоминаем не чаще: при исчерпанном лимите они идут один за другим. */
private const val RATE_LIMIT_NOTE_INTERVAL_MS = 10 * 60_000L

/**
 * Что сказать в ленте активности о состоянии источника свечей.
 *
 * Раньше отказ GeckoTerminal по лимиту выглядел как «у монеты мало свечей»: стратегии молча не находили
 * сетапов, а причина нигде не показывалась. Здесь она превращается в сообщения: работа без ключа (один раз),
 * отклонённый ключ, отказ по лимиту (не чаще раза в [RATE_LIMIT_NOTE_INTERVAL_MS]).
 */
class CandleFeedNotes(private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private var seenHits = 0
    private var limitNotedAt = 0L
    private var keylessNoted = false
    private var rejectionNoted = false

    /** Сообщения для ленты по очередному состоянию источника; обычно пусто. */
    fun onStatus(s: CandleFeedStatus): List<Pair<ActivityLevel, String>> = buildList {
        if (s.keyRejected && !rejectionNoted) {
            add(
                ActivityLevel.ERROR to "⚠️ Свечи: ключ CoinGecko отклонён сервером — работаю без ключа (около 4 запросов " +
                    "в минуту). Проверьте ключ в настройках."
            )
        }
        rejectionNoted = s.keyRejected

        if (!s.usingKey && !s.keyRejected && !keylessNoted) {
            add(
                ActivityLevel.INFO to "ℹ️ Свечи: без ключа CoinGecko GeckoTerminal даёт около 4 запросов в минуту — монеты " +
                    "разбираются по очереди, на ТФ 1m с отставанием. Бесплатный ключ — «Настройки» → «Данные рынка»."
            )
        }
        keylessNoted = !s.usingKey

        val now = nowMs()
        if (s.rateLimitHits > seenHits && now - limitNotedAt >= RATE_LIMIT_NOTE_INTERVAL_MS) {
            limitNotedAt = now
            add(
                ActivityLevel.WARN to "⚠️ Свечи: источник ответил «слишком много запросов» — запросы на паузе, разбор " +
                    "монет с задержкой. " +
                    if (s.usingKey) "Уменьшите число активных стратегий или кандидатов."
                    else "Бесплатный ключ CoinGecko («Настройки» → «Данные рынка») поднимает лимит."
            )
        }
        seenHits = s.rateLimitHits
    }
}

/** Следит за источником свечей и пишет в ленту то, что о нём стоит знать (см. [CandleFeedNotes]). */
fun CoroutineScope.watchCandleFeed(status: StateFlow<CandleFeedStatus>, activityLog: ActivityLog): Job = launch {
    val notes = CandleFeedNotes()
    status.collect { s -> notes.onStatus(s).forEach { (level, message) -> activityLog.add(level, message) } }
}
