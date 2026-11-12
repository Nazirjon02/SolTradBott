package tj.khujand.solana.trading.bot.exchange.chain

/**
 * Лимит кредитов Helius в сутки по умолчанию: бесплатный тариф — 1 млн в месяц (~33 тыс. в сутки);
 * ~8 тыс. в сутки остаются на остальные вызовы RPC того же ключа (баланс, отправка транзакций).
 */
const val DEFAULT_CHAIN_CREDIT_LIMIT = 25_000

/** Сутки в мс. */
internal const val DAY_MS = 86_400_000L

/** Байт за кредит: WebSocket Helius — 2 кредита за 0,1 МБ (0,1 МБ = 100 000 байт — с запасом против 104 858). */
internal const val BYTES_PER_CREDIT = 50_000.0

/** Начало суток UTC, в которые попадает [ms]. */
internal fun utcDayStart(ms: Long): Long = ms - ms.mod(DAY_MS)

/**
 * Скорость (байт/с) в скользящем окне из минутных корзин: столько сейчас стоит подписка.
 * Окно короче [buckets] минут, пока замер моложе.
 */
class RateWindow(private val buckets: Int = 10, private val bucketMs: Long = 60_000L) {
    private val bytes = LongArray(buckets)
    private val stamps = LongArray(buckets) { Long.MIN_VALUE }
    var startedAtMs: Long = -1L
        private set
    var totalBytes: Long = 0L
        private set

    fun add(n: Int, nowMs: Long) {
        if (startedAtMs < 0) startedAtMs = nowMs
        val slot = nowMs / bucketMs
        val i = slot.mod(buckets.toLong()).toInt()
        if (stamps[i] != slot) {
            stamps[i] = slot
            bytes[i] = 0
        }
        bytes[i] += n.toLong()
        totalBytes += n
    }

    /** Начать замер с [nowMs], даже если байт ещё не было (тихий пул — тоже замер). */
    fun start(nowMs: Long) {
        if (startedAtMs < 0) startedAtMs = nowMs
    }

    /** Сколько длится замер, мс. */
    fun ageMs(nowMs: Long): Long = if (startedAtMs < 0) 0L else nowMs - startedAtMs

    /** Байт/с в окне. */
    fun rate(nowMs: Long): Double {
        val age = ageMs(nowMs)
        if (age <= 0) return 0.0
        val slot = nowMs / bucketMs
        var sum = 0L
        for (k in 0 until buckets) {
            val s = slot - k
            val i = s.mod(buckets.toLong()).toInt()
            if (stamps[i] == s) sum += bytes[i]
        }
        val windowMs = minOf(age, buckets * bucketMs).coerceAtLeast(1_000L)
        return sum * 1000.0 / windowMs
    }

    /** Прогноз кредитов в сутки при нынешней скорости. */
    fun creditsPerDay(nowMs: Long): Double = rate(nowMs) * 86_400.0 / BYTES_PER_CREDIT
}

/** Снимок расхода для экрана и отчёта. */
data class UsageSnapshot(
    val creditsToday: Double = 0.0,
    val bytesToday: Long = 0L,
    val messagesToday: Long = 0L,
    val creditsTotal: Double = 0.0,
    val bytesTotal: Long = 0L,
    val httpCallsTotal: Long = 0L,
    val connectionsTotal: Long = 0L,
    val runningMs: Long = 0L,
    /** Кредиты в сутки по скорости последних 10 минут (прогноз, если всё останется как есть). */
    val creditsPerDayNow: Double = 0.0,
    /** Кредиты в сутки в среднем с начала работы. */
    val creditsPerDayAvg: Double = 0.0,
)

/**
 * Расход Helius в кредитах (бесплатный тариф, по helius.dev на 2026-11-11): WebSocket — 2 кредита за 0,1 МБ
 * присланных данных, открытие соединения — 1 кредит, обычный вызов HTTP RPC — 1 кредит.
 *
 * Считаются байты полезной нагрузки сообщений (JSON, как его отдаёт узел, без сжатия) — так считает и счёт
 * Helius, если сжатие не согласовано; с публичным узлом Solana цифры показывают, сколько это стоило бы на Helius.
 */
class HeliusUsage(private val nowMs: () -> Long) {
    private val startedAt = nowMs()
    private var dayStart = utcDayStart(startedAt)
    private var bytesToday = 0L
    private var messagesToday = 0L
    private var extraCreditsToday = 0L
    private var bytesTotal = 0L
    private var httpCalls = 0L
    private var connections = 0L
    private val recent = RateWindow()

    private fun rollDay(now: Long) {
        val d = utcDayStart(now)
        if (d != dayStart) {
            dayStart = d
            bytesToday = 0
            messagesToday = 0
            extraCreditsToday = 0
        }
    }

    fun onMessage(bytes: Int) {
        val now = nowMs()
        rollDay(now)
        bytesToday += bytes
        messagesToday++
        bytesTotal += bytes
        recent.add(bytes, now)
    }

    fun onConnection() {
        rollDay(nowMs())
        connections++
        extraCreditsToday++
    }

    fun onHttpCall() {
        rollDay(nowMs())
        httpCalls++
        extraCreditsToday++
    }

    fun creditsToday(): Double {
        rollDay(nowMs())
        return bytesToday / BYTES_PER_CREDIT + extraCreditsToday
    }

    fun snapshot(): UsageSnapshot {
        val now = nowMs()
        rollDay(now)
        recent.start(startedAt)
        val running = now - startedAt
        val total = bytesTotal / BYTES_PER_CREDIT + httpCalls + connections
        return UsageSnapshot(
            creditsToday = bytesToday / BYTES_PER_CREDIT + extraCreditsToday,
            bytesToday = bytesToday,
            messagesToday = messagesToday,
            creditsTotal = total,
            bytesTotal = bytesTotal,
            httpCallsTotal = httpCalls,
            connectionsTotal = connections,
            runningMs = running,
            creditsPerDayNow = recent.creditsPerDay(now),
            creditsPerDayAvg = if (running >= 60_000L) total * DAY_MS / running else 0.0,
        )
    }

    /** Сколько мс осталось до конца суток UTC. */
    fun msLeftToday(): Long = dayStart + DAY_MS - nowMs()

    /** Начало следующих суток UTC. */
    fun nextDayStart(): Long = utcDayStart(nowMs()) + DAY_MS
}

/**
 * Бюджет кредитов: какие подписки снять, чтобы бесплатного тарифа хватило на месяц.
 *
 * Стоимость подписки на пул почти не зависит от числа его сделок: на популярный пул ссылаются сотни
 * транзакций MEV-ботов в секунду (замер 2026-11-11: пул HOLD — 1960 уведомлений в минуту, из них 15 сделок,
 * ~53 тыс. кредитов в сутки), а `logsSubscribe` присылает каждую. Поэтому цена каждой подписки
 * измеряется, и дорогие пулы снимаются. Чистая функция — без сети.
 */
object CreditBudget {
    /** Доля суточного лимита, которую может занять один пул. */
    const val POOL_SHARE = 0.2

    /** Доля суточного лимита для всех подписок вместе (запас — HTTP-вызовы и рост шума). */
    const val TOTAL_SHARE = 0.9

    /** Через сколько замера цена пула считается известной. */
    const val PROBE_MS = 60_000L

    /** Прогноз для пула, который ещё замеряется: средний пул списка наблюдения. */
    const val UNKNOWN_POOL_CREDITS = 1_000.0

    /** Цена подписки по замеру. */
    data class PoolCost(val pool: String, val creditsPerDay: Double, val measuredMs: Long, val bytes: Long)

    /** Решение: [drop] — пул → причина; [admit] — сколько новых пулов можно подписать сейчас. */
    data class Decision(val drop: Map<String, Reason>, val admit: Int, val stopAll: Boolean)

    enum class Reason { EXPENSIVE, OVER_BUDGET, DAY_LIMIT }

    fun decide(costs: List<PoolCost>, dailyLimit: Int, usedToday: Double, msLeftToday: Long): Decision {
        val limit = dailyLimit.toDouble().coerceAtLeast(1.0)
        if (usedToday >= limit) {
            return Decision(costs.associate { it.pool to Reason.DAY_LIMIT }, admit = 0, stopAll = true)
        }
        val perPool = limit * POOL_SHARE
        val drop = LinkedHashMap<String, Reason>()
        for (c in costs) {
            val known = c.measuredMs >= PROBE_MS
            // Ещё не замерен, но уже съел втрое больше своей доли за время замера — ждать минуту незачем.
            val burst = !known && c.bytes / BYTES_PER_CREDIT > 3 * perPool * PROBE_MS / DAY_MS
            if ((known && c.creditsPerDay > perPool) || burst) drop[c.pool] = Reason.EXPENSIVE
        }
        val kept = costs.filter { it.pool !in drop }
        // Скорость молодой подписки по короткому окну скачет (одна крупная транзакция — и прогноз в разы
        // выше): до конца замера пул считается средним, а по-настоящему дорогой ловит правило «взрыва» выше.
        fun estimate(c: PoolCost) = if (c.measuredMs >= PROBE_MS) c.creditsPerDay else UNKNOWN_POOL_CREDITS
        var total = kept.sumOf { estimate(it) }
        // На остаток суток: потраченное + скорость × оставшееся время не должно превысить лимит.
        val remainingDay = msLeftToday.coerceAtLeast(0L).toDouble() / DAY_MS
        for (c in kept.sortedByDescending { estimate(it) }) {
            val fitsSteady = total <= limit * TOTAL_SHARE
            val fitsToday = usedToday + total * remainingDay <= limit
            if (fitsSteady && fitsToday) break
            drop[c.pool] = Reason.OVER_BUDGET
            total -= estimate(c)
        }
        val room = limit * TOTAL_SHARE - total
        val admit = if (room <= 0) 0 else (room / UNKNOWN_POOL_CREDITS).toInt().coerceAtMost(MAX_ADMIT_PER_ROUND)
        return Decision(drop, admit, stopAll = false)
    }

    /** Сколько новых пулов подписывать за один круг: цена каждого ещё не известна. */
    const val MAX_ADMIT_PER_ROUND = 5
}
