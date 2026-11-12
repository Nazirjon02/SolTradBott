package tj.khujand.solana.trading.bot.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.exchange.dex.CandleFeedStatus
import tj.khujand.solana.trading.bot.util.formatLocalTime

/** Об отказах по лимиту напоминаем не чаще: при исчерпанном лимите они идут один за другим. */
private const val RATE_LIMIT_NOTE_INTERVAL_MS = 10 * 60_000L

/**
 * Что сказать в ленте активности о состоянии источника свечей.
 *
 * Раньше отказ GeckoTerminal по лимиту выглядел как «у монеты мало свечей»: стратегии молча не находили
 * сетапов, а причина нигде не показывалась. Здесь она превращается в сообщения: как идут свечи — с ключом или без
 * (один раз), отклонённый ключ, потраченный дневной лимит ключа, кончившаяся месячная квота, отказ по лимиту (не
 * чаще раза в [RATE_LIMIT_NOTE_INTERVAL_MS]).
 */
class CandleFeedNotes(private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private var seenHits = 0
    private var limitNotedAt = 0L
    private var keylessNoted = false
    private var keyNoted = false
    private var rejectionNoted = false
    private var exhaustionNotedUntil = 0L
    private var dailySpentNotedFor = 0L

    /** Сообщения для ленты по очередному состоянию источника; обычно пусто. */
    fun onStatus(s: CandleFeedStatus): List<Pair<ActivityLevel, String>> = buildList {
        if (s.keyRejected && !rejectionNoted) {
            add(
                ActivityLevel.ERROR to "⚠️ Свечи: ключ CoinGecko отклонён сервером — бэктест и «Анализ» идут без ключа. " +
                    "Проверьте ключ в настройках."
            )
        }
        rejectionNoted = s.keyRejected

        if (s.keyExhaustedUntilMs > 0L && s.keyExhaustedUntilMs != exhaustionNotedUntil) {
            add(
                ActivityLevel.WARN to "⚠️ Свечи: ключ CoinGecko упирается в лимит — похоже, кончилась месячная квота " +
                    "(у бесплатного ключа 10 000 запросов в месяц). Бэктест и «Анализ» идут без ключа — медленнее; " +
                    "ключ попробую снова ${formatLocalTime(s.keyExhaustedUntilMs)}. Торговля и так идёт без ключа."
            )
        }
        exhaustionNotedUntil = s.keyExhaustedUntilMs

        if (s.usingKey && !keyNoted) {
            add(
                ActivityLevel.INFO to "ℹ️ Свечи: торговля идёт без ключа — бесплатно, около 4 запросов в минуту. Ключ " +
                    "CoinGecko тратят только бэктест, «Анализ» и график, с дневным лимитом — чтобы квоты хватило до конца месяца."
            )
        }
        keyNoted = s.usingKey

        // «Без ключа» — только когда ключа нет вовсе: про отклонённый и отложенный ключ сказано выше.
        val noKey = !s.usingKey && !s.keyRejected && s.keyExhaustedUntilMs == 0L
        if (noKey && !keylessNoted) {
            add(
                ActivityLevel.INFO to "ℹ️ Свечи: без ключа CoinGecko GeckoTerminal даёт около 4 запросов в минуту — монеты " +
                    "разбираются по очереди, на ТФ 1m с отставанием. Бесплатный ключ ускорит бэктест и «Анализ» — " +
                    "«Настройки» → «Подключения»."
            )
        }
        keylessNoted = noKey

        val dailySpent = s.usingKey && s.keyDailyAllowance > 0 && s.keyUsedToday >= s.keyDailyAllowance
        if (dailySpent && s.keyNextDayMs != dailySpentNotedFor) {
            dailySpentNotedFor = s.keyNextDayMs
            add(
                ActivityLevel.INFO to "ℹ️ Ключ CoinGecko: дневной лимит (${s.keyDailyAllowance} запросов) потрачен — до " +
                    "${formatLocalTime(s.keyNextDayMs)} бэктест и «Анализ» идут без ключа, медленнее."
            )
        }

        val now = nowMs()
        if (s.rateLimitHits > seenHits && now - limitNotedAt >= RATE_LIMIT_NOTE_INTERVAL_MS) {
            limitNotedAt = now
            add(
                ActivityLevel.WARN to "⚠️ Свечи: источник ответил «слишком много запросов» — запросы на паузе, разбор " +
                    "монет с задержкой. Торговля идёт без ключа, поэтому если задержка мешает — уменьшите число " +
                    "стратегий или кандидатов."
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
