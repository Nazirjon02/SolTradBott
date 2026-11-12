package tj.khujand.solana.trading.bot

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.core.strategy.MIN_CONFIDENCE
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.rangeAllowsEntry
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.domain.backtest.BacktestCoin
import tj.khujand.solana.trading.bot.domain.backtest.BacktestDataLoader
import tj.khujand.solana.trading.bot.domain.backtest.BacktestSettings
import tj.khujand.solana.trading.bot.domain.backtest.BacktestStats
import tj.khujand.solana.trading.bot.domain.backtest.Backtester
import tj.khujand.solana.trading.bot.domain.backtest.CandleSlices
import tj.khujand.solana.trading.bot.domain.backtest.CoinSeries
import tj.khujand.solana.trading.bot.domain.backtest.backtestHigherTf
import tj.khujand.solana.trading.bot.domain.backtest.backtestStrategy
import tj.khujand.solana.trading.bot.domain.backtest.timeframeMs
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.CandleStore
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.util.formatLocalTime

/**
 * Аудит сетапов — ответ на «почему бот не находит входы» на настоящих данных. Берёт из базы бота список
 * наблюдения сканера, стратегии с их настройками и уже торговавшиеся монеты (читается копия файла, сразу
 * удаляется), грузит свечи за несколько дней и проигрывает последние часы бар за баром: на каком условии каждая
 * стратегия останавливается, сколько сетапов было бы, если бы бот разбирал КАЖДУЮ монету списка на КАЖДОМ закрытом
 * баре, и сколько из этого он видит на самом деле (сканер отдаёт стратегии только лучших по баллу).
 *
 * Свечи идут с ключом CoinGecko (как бэктест): бесплатный лимит GeckoTerminal общий с работающим ботом — аудит
 * без ключа отнял бы у бота свечи, поэтому без ключа не запускается.
 *
 * Запуск (в отдельной рабочей копии, не в папке, из которой запущен бот):
 * `SETUP_AUDIT_OUT=<папка> ./gradlew :shared:jvmTest --tests "*SetupAuditRunTest*" --rerun`
 * Необязательно: SETUP_AUDIT_DB (база бота), SETUP_AUDIT_HOURS (окно проигрывания, 24), SETUP_AUDIT_DAYS (сколько
 * дней истории грузить, 3), SETUP_AUDIT_MAX_COINS. Без SETUP_AUDIT_OUT тест ничего не делает.
 */
class SetupAuditRunTest {

    private class Loaded(
        val configs: List<StrategyConfig>,
        val pool: List<TokenCandidate>,
        val traded: Set<String>,
        val key: String?,
        val usageLine: String,
    )

    /** Одна оценка стратегии на одном закрытом баре одной монеты. */
    private class Row(
        val symbol: String,
        val mint: String,
        val barCloseMs: Long,
        val passedFilters: Boolean,
        val passed: Int,
        val total: Int,
        val missing: String?,
        val signal: Boolean,
        val accepted: Boolean,
        val rejectedBy: String?,
        val reason: String?,
    )

    /** Итог по стратегии для сводной таблицы. */
    private class Summary(
        val name: String,
        val timeframe: String,
        val eligible: Int,
        val top: Int,
        val topTraded: Int,
        val allEpisodes: Int,
        val allCoins: Int,
        val seenEpisodes: Int,
        val fixedEpisodes: Int,
        val backtest: BacktestStats,
    )

    @Test
    fun audit() = runBlocking {
        val outDir = System.getenv("SETUP_AUDIT_OUT")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val out = File(outDir).apply { mkdirs() }
        val dbFile = File(System.getenv("SETUP_AUDIT_DB") ?: "${System.getProperty("user.home")}/.soltradbot/soltradbot.db")
        val hours = System.getenv("SETUP_AUDIT_HOURS")?.toIntOrNull() ?: 24
        val days = System.getenv("SETUP_AUDIT_DAYS")?.toIntOrNull() ?: 3
        val maxCoins = System.getenv("SETUP_AUDIT_MAX_COINS")?.toIntOrNull() ?: Int.MAX_VALUE

        val loaded = readBotDb(dbFile)
        val key = loaded.key
            ?: error("В базе бота нет ключа CoinGecko: аудит без ключа отнял бы бесплатный лимит свечей у работающего бота")
        GeckoTerminalApi.setApiKey(key)

        val now = System.currentTimeMillis()
        val active = loaded.configs.filter { it.isActive }
        val coins = loaded.pool.sortedByDescending { it.score }.take(maxCoins)

        // ── Свечи. 5m — по каждой монете (из них собираются 15m); часовые — отдельным запросом, если нужны старшему
        // ТФ; ТФ мельче 5m (стратегия на 1m) — только по монетам, которые проходят её фильтры сейчас.
        val loader = BacktestDataLoader(minRequestIntervalMs = 1_200L)
        var requests = 0
        val five = LinkedHashMap<String, List<Candle>>()
        coins.forEach { c ->
            five[c.mint] = loader.loadSeries(c.toCoin(), "5m", null, days).candles
            requests += loader.requestsPerCoin("5m", null, days)
        }
        val direct = HashMap<Pair<String, String>, List<Candle>>()
        suspend fun directSeries(c: TokenCandidate, tf: String, tfDays: Int): List<Candle> = direct.getOrPut(c.mint to tf) {
            requests += loader.requestsPerCoin(tf, null, tfDays)
            loader.loadSeries(c.toCoin(), tf, null, tfDays).candles
        }
        suspend fun candlesFor(c: TokenCandidate, tf: String): List<Candle> {
            val ms = timeframeMs(tf)
            return when {
                ms == FIVE_MIN_MS -> five[c.mint].orEmpty()
                ms < FIVE_MIN_MS -> directSeries(c, tf, minOf(days, FINE_TF_DAYS))
                ms < HOUR_MS && ms % FIVE_MIN_MS == 0L -> CandleStore.aggregate(five[c.mint].orEmpty(), ms, now)
                ms == HOUR_MS -> directSeries(c, "1h", HOURLY_DAYS)
                else -> CandleStore.aggregate(directSeries(c, "1h", HOURLY_DAYS), ms, now)
            }
        }
        val withCandles = five.count { it.value.size > 20 }

        val report = StringBuilder()
        val signalsCsv = StringBuilder("strategy,symbol,mint,bar_close_local,accepted,rejected_by,reason\n")
        val summaries = ArrayList<Summary>()
        // Сколько запросов свечей в минуту нужно боту: по каждому пулу — раз в бар самого мелкого ТФ, на котором
        // его смотрит хоть одна стратегия (старшие ТФ собираются из младшего без запроса).
        val finestTf = HashMap<String, Long>()

        for (cfg in active) {
            val tfMs = timeframeMs(cfg.timeframe)
            val eligible = coins.filter(cfg.scanFilters()::passes).sortedByDescending { it.score }
            val limit = cfg.scanFilters().maxCandidates
            // Как сейчас: десять лучших по баллу, уже торговавшиеся из них бот пропускает.
            val top = eligible.take(limit)
            val seenNow = top.filter { it.mint !in loaded.traded }.map { it.mint }.toSet()
            // Как после исправления: десять лучших среди тех, кем ещё можно торговать.
            val seenFixed = eligible.filter { it.mint !in loaded.traded }.take(limit).map { it.mint }.toSet()
            top.forEach { c -> finestTf.merge(c.pairAddress, tfMs) { a, b -> minOf(a, b) } }

            val rows = ArrayList<Row>()
            val series = ArrayList<CoinSeries>()
            // ТФ мельче 5m грузим только по монетам под фильтрами стратегии — остальные ей всё равно не достанутся.
            val audited = if (tfMs < FIVE_MIN_MS) eligible else coins
            for (c in audited) {
                val entry = candlesFor(c, cfg.timeframe)
                if (entry.size <= WARMUP_BARS) continue
                val higherTf = cfg.backtestHigherTf()
                val higher = higherTf?.let { candlesFor(c, it) } ?: emptyList()
                val coin = c.toCoin()
                series += CoinSeries(coin, entry, higher)
                rows += replay(cfg, coin, entry, higher, tfMs, higherTf?.let { timeframeMs(it) } ?: 0L, now - hours * HOUR_MS)
            }
            val backtest = Backtester.run(cfg, series, BacktestSettings(costPerSidePercent = 1.0, oneTradePerCoin = true)).stats
            summaries += section(report, signalsCsv, cfg, rows, eligible.size, coins.size, top.size, top.size - seenNow.size,
                seenNow, seenFixed, loaded.traded, backtest, days)
        }

        val demandPerMin = finestTf.values.sumOf { 60_000.0 / it }
        val head = StringBuilder()
        head.appendLine("# Аудит сетапов — ${formatLocalTime(now)}")
        head.appendLine()
        head.appendLine("Монет в списке наблюдения: **${coins.size}**, со свечами: **$withCandles**, уже торговались (в текущем режиме) " +
            "и исключены навсегда: **${coins.count { it.mint in loaded.traded }}**. Окно: последние **$hours ч**. ${loaded.usageLine} " +
            "Запросов с ключом на аудит: ~$requests.")
        head.appendLine()
        head.appendLine("**Свечи:** стратегиям сейчас нужно около **${fmt1(demandPerMin)} запроса в минуту** (${finestTf.size} пулов, каждый — " +
            "раз в бар своего самого мелкого ТФ), бесплатный GeckoTerminal даёт **4 в минуту**." +
            (if (demandPerMin > KEYLESS_PER_MIN) " Спрос выше лимита — часть монет разбирается по устаревшим свечам." else ""))
        head.appendLine()
        head.appendLine("Колонки: «все» — если бы бот разбирал каждую монету списка на каждом закрытом баре; «сейчас» — лучшие по " +
            "баллу сканера за вычетом уже торговавшихся; «после правки» — лучшие по баллу среди тех, кем ещё можно торговать. " +
            "Входы — эпизоды (бары с сигналом подряд считаются одним входом) за окно, прошедшие общие фильтры.")
        head.appendLine()
        head.appendLine("| Стратегия | ТФ | Под фильтры | Разбирает | Из них уже торговались | Входов: все | на монетах | сейчас | после правки | Бэктест $days дн. |")
        head.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        summaries.forEach { s ->
            head.appendLine("| ${s.name.md()} | ${s.timeframe} | ${s.eligible} | ${s.top} | ${s.topTraded} | ${s.allEpisodes} | ${s.allCoins} | " +
                "${s.seenEpisodes} | ${s.fixedEpisodes} | ${s.backtest.trades} сд., ${fmt2(s.backtest.expectancyR)}R |")
        }
        head.appendLine()
        File(out, "report.md").writeText(head.toString() + report)
        File(out, "signals.csv").writeText(signalsCsv.toString())
    }

    /** Оценки стратегии [cfg] по монете на каждом закрытом баре, закрывшемся после [fromMs]. */
    private suspend fun replay(
        cfg: StrategyConfig,
        coin: BacktestCoin,
        candles: List<Candle>,
        higherTf: List<Candle>,
        tfMs: Long,
        htfMs: Long,
        fromMs: Long,
    ): List<Row> {
        val window = cfg.darsCandleLimit.coerceAtLeast(50)
        val slices = CandleSlices(DexClient.parseTimeframe(cfg.timeframe))
        val strategy = backtestStrategy(cfg, slices)
        val rows = ArrayList<Row>()
        var htfClosed = 0
        for (i in WARMUP_BARS until candles.size) {
            val barCloseMs = candles[i].openTimeMs + tfMs
            while (htfClosed < higherTf.size && higherTf[htfClosed].openTimeMs + htfMs <= barCloseMs) htfClosed++
            if (barCloseMs < fromMs) continue
            slices.entry = candles.subList(maxOf(0, i - window + 1), i + 1)
            slices.higher = higherTf.subList(maxOf(0, htfClosed - window), htfClosed)
            val candidate = Backtester.candidateAt(coin, candles, i, tfMs, barCloseMs)
            if (!Backtester.passesScanFilters(cfg, candidate)) {
                rows += Row(coin.symbol, coin.mint, barCloseMs, false, 0, 0, null, false, false, null, null)
                continue
            }
            val e = strategy.evaluate(candidate, slices.entry, emptyList())
            val s = e.signal
            val confidenceOk = s == null || strategy.selfGated || s.confidence >= MIN_CONFIDENCE
            val rangeOk = s == null || cfg.rangeAllowsEntry(slices.entry, s.entryPrice)
            val rejectedBy = when {
                s == null -> null
                !rangeOk -> "range-фильтр"
                !confidenceOk -> "уверенность ${(s.confidence * 100).toInt()}% < ${(MIN_CONFIDENCE * 100).toInt()}%"
                else -> null
            }
            rows += Row(
                coin.symbol, coin.mint, barCloseMs, true, e.passed, e.total, e.missing, s != null,
                s != null && rejectedBy == null, rejectedBy, s?.reason,
            )
        }
        return rows
    }

    /** Бары с сигналом подряд по одной монете — один эпизод: бот вошёл бы на первом. */
    private fun episodes(list: List<Row>, tfMs: Long): Int = list.groupBy { it.mint }.values.sumOf { perCoin ->
        var n = 0
        var prev: Long? = null
        perCoin.sortedBy { it.barCloseMs }.forEach { r ->
            if (prev == null || r.barCloseMs - prev!! > tfMs) n++
            prev = r.barCloseMs
        }
        n
    }

    /** Раздел отчёта по одной стратегии. */
    private fun section(
        report: StringBuilder,
        signalsCsv: StringBuilder,
        cfg: StrategyConfig,
        rows: List<Row>,
        eligibleNow: Int,
        poolSize: Int,
        top: Int,
        topTraded: Int,
        seenNow: Set<String>,
        seenFixed: Set<String>,
        traded: Set<String>,
        backtest: BacktestStats,
        days: Int,
    ): Summary {
        val tfMs = timeframeMs(cfg.timeframe)
        val evaluated = rows.filter { it.passedFilters }
        val filtered = rows.size - evaluated.size
        val total = evaluated.maxOfOrNull { it.total } ?: 0
        val setups = evaluated.filter { it.signal }
        val accepted = setups.filter { it.accepted }
        val tradable = accepted.filter { it.mint !in traded }
        val allEpisodes = episodes(accepted, tfMs)
        val seenEpisodes = episodes(accepted.filter { it.mint in seenNow }, tfMs)
        val fixedEpisodes = episodes(accepted.filter { it.mint in seenFixed }, tfMs)

        report.appendLine("## ${cfg.name} · ${cfg.timeframe}")
        report.appendLine()
        report.appendLine("- Фильтры сканера сейчас проходят **$eligibleNow из $poolSize** монет; бот берёт из них **$top** лучших по баллу, " +
            "из которых **$topTraded уже торговались** и пропускаются.")
        report.appendLine("- Оценок на закрытых барах: ${evaluated.size} (ещё $filtered баров отсеяны фильтрами ликвидности/капитализации/возраста в прошлом).")
        report.appendLine("- Сетапов (стратегия сказала «вход»): ${episodes(setups, tfMs)} эпизодов по ${setups.map { it.mint }.toSet().size} монетам; " +
            "после общих фильтров (уверенность, range): **$allEpisodes по ${accepted.map { it.mint }.toSet().size} монетам**, " +
            "из них по ещё не торговавшимся — ${episodes(tradable, tfMs)}.")
        report.appendLine("- Бот сейчас увидел бы из них **$seenEpisodes**; с десяткой из неторговавшихся — **$fixedEpisodes**.")
        report.appendLine("- Бэктест на тех же свечах ($days дн., одна сделка на монету, издержки 1% за сторону): сделок ${backtest.trades}, " +
            "в плюс ${backtest.winRate.toInt()}%, ожидание ${fmt2(backtest.expectancyR)}R, сумма ${fmt2(backtest.totalR)}R.")
        report.appendLine()

        if (total > 0) {
            report.appendLine("Где останавливается разбор (шагов всего $total):")
            report.appendLine()
            report.appendLine("| Пройдено шагов | Оценок | Доля |")
            report.appendLine("|---|---|---|")
            evaluated.groupingBy { it.passed }.eachCount().toSortedMap().forEach { (stage, n) ->
                val label = if (stage >= total) "$stage — сетап" else "$stage"
                report.appendLine("| $label | $n | ${pct(n, evaluated.size)} |")
            }
            report.appendLine()
        }

        val reasons = evaluated.filter { !it.signal && it.missing != null }
            .groupBy { normalize(it.missing!!) }
            .map { (k, v) -> Triple(k, v.size, v.first().missing!!) }
            .sortedByDescending { it.second }
        if (reasons.isNotEmpty()) {
            report.appendLine("Чего не хватает чаще всего:")
            report.appendLine()
            report.appendLine("| Причина (числа заменены на N) | Оценок | Доля | Пример |")
            report.appendLine("|---|---|---|---|")
            reasons.take(10).forEach { (k, n, example) ->
                report.appendLine("| ${k.md()} | $n | ${pct(n, evaluated.size)} | ${example.take(110).md()} |")
            }
            report.appendLine()
        }

        val rejected = setups.filter { !it.accepted }.groupingBy { it.rejectedBy?.let(::normalize) ?: "?" }.eachCount()
        if (rejected.isNotEmpty()) {
            report.appendLine("Сетап сложился, но вход сняли общие фильтры: " +
                rejected.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} — ${it.value}" } + ".")
            report.appendLine()
        }

        if (setups.isNotEmpty()) {
            report.appendLine("Сетапы (первые бары эпизодов, до 20):")
            report.appendLine()
            var prevKey: Pair<String, Long>? = null
            var shown = 0
            for (r in setups.sortedWith(compareBy({ it.mint }, { it.barCloseMs }))) {
                val continues = prevKey != null && prevKey!!.first == r.mint && r.barCloseMs - prevKey!!.second <= tfMs
                prevKey = r.mint to r.barCloseMs
                if (continues || shown >= 20) continue
                shown++
                val seen = when {
                    r.mint in traded -> "уже торговалась"
                    r.mint in seenNow -> "бот видит"
                    r.mint in seenFixed -> "увидит после правки"
                    else -> "вне десятки"
                }
                report.appendLine("- ${formatLocalTime(r.barCloseMs)} · ${r.symbol} · $seen · " +
                    (if (r.accepted) "вход" else "снят: ${r.rejectedBy}") + " · ${r.reason?.take(130)?.md()}")
            }
            report.appendLine()
        }
        setups.forEach { r ->
            signalsCsv.appendLine(listOf(cfg.name, r.symbol, r.mint, formatLocalTime(r.barCloseMs), r.accepted, r.rejectedBy ?: "",
                r.reason.orEmpty()).joinToString(",") { "\"${it.toString().replace("\"", "'")}\"" })
        }

        return Summary(cfg.name, cfg.timeframe, eligibleNow, top, topTraded, allEpisodes, accepted.map { it.mint }.toSet().size,
            seenEpisodes, fixedEpisodes, backtest)
    }

    private fun TokenCandidate.toCoin() = BacktestCoin(
        pool = pairAddress, mint = mint, symbol = symbol, name = name, dexId = dexId,
        createdAtMs = if (tokenAgeMinutes > 0) scannedAt - tokenAgeMinutes * 60_000L else 0L,
        priceNow = priceUsd, marketCapNow = marketCap, liquidityNow = liquidityUsd,
    )

    /**
     * Стратегии, список наблюдения, торговавшиеся монеты и ключ свечей из копии базы бота. Копия — во временном
     * файле и удаляется сразу после чтения: в базе лежат секреты (токен Telegram, ключи), а живую базу бот пишет
     * без WAL.
     */
    private fun readBotDb(db: File): Loaded {
        require(db.exists()) { "Нет базы бота: $db" }
        val copy = Files.createTempFile("setup-audit-", ".db")
        try {
            Files.copy(db.toPath(), copy, StandardCopyOption.REPLACE_EXISTING)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.toAbsolutePath().toString().replace('\\', '/')}")
            try {
                val database = DrxDatabase(driver)
                val settings = SettingsStore(database)
                val usage = settings.getCoinGeckoUsage()
                val usageLine = "Ключ CoinGecko по счёту бота: сегодня ${usage?.usedToday ?: 0}, за месяц ${usage?.usedMonth ?: 0} " +
                    "из ${settings.getCoinGeckoQuota()}."
                val demoFlag = if (settings.getDemoMode() != false) 1L else 0L
                return Loaded(
                    configs = StrategyStore(database).loadAll(),
                    pool = TokenCache(database).all(),
                    traded = database.tradeQueries.tradedMints(demoFlag).executeAsList().toSet(),
                    key = settings.getCoinGeckoKey(),
                    usageLine = usageLine,
                )
            } finally {
                driver.close()
            }
        } finally {
            Files.deleteIfExists(copy)
        }
    }

    private fun normalize(reason: String): String =
        reason.replace(Regex("[−-]?\\d+(?:[.,]\\d+)?(?:[eE][−-]?\\d+)?"), "N").replace(Regex("\\s+"), " ").trim().take(120)

    private fun String.md(): String = replace("|", "/").replace("\n", " ")

    private fun pct(n: Int, of: Int): String = if (of == 0) "—" else "${(n * 1000L / of) / 10.0}%"

    private fun fmt1(v: Double): String = "%.1f".format(java.util.Locale.ROOT, v)

    private fun fmt2(v: Double): String = (if (v >= 0) "+" else "−") + "%.2f".format(java.util.Locale.ROOT, kotlin.math.abs(v))

    private companion object {
        const val WARMUP_BARS = 10
        const val FIVE_MIN_MS = 5 * 60_000L
        const val HOUR_MS = 3_600_000L

        /** Часовых свечей для старшего ТФ — за столько дней (один запрос на монету). */
        const val HOURLY_DAYS = 20

        /** Свечей мельче 5m — не больше чем за столько дней: на 1m это уже три запроса на монету. */
        const val FINE_TF_DAYS = 2

        /** Бесплатный GeckoTerminal: запрос раз в 15 с. */
        const val KEYLESS_PER_MIN = 4.0
    }
}
