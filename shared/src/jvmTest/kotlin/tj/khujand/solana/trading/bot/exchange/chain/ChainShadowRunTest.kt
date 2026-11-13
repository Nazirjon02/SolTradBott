package tj.khujand.solana.trading.bot.exchange.chain

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi

/**
 * Живой прогон сверки (инструмент, а не обычный тест): несколько часов строит свечи из блокчейна по списку
 * наблюдения работающего бота и в конце сверяет 5m-бары с GeckoTerminal. Без переменной CHAIN_SHADOW_MINUTES
 * ничего не делает.
 *
 * ```
 * CHAIN_SHADOW_MINUTES=240 CHAIN_OUT=/путь/к/папке ./gradlew :shared:jvmTest --tests "*ChainShadowRunTest*" --rerun
 * ```
 * `--rerun` обязателен: переменные окружения не входят во входы задачи, и без него Gradle достанет прошлый
 * (пустой) результат из кеша сборки, ничего не запустив.
 * Переменные: CHAIN_RPC_URL — узел (по умолчанию публичный mainnet; адрес Helius с ключом в отчёт не
 * пишется), CHAIN_DB — база бота (читается копия файла: живую базу бот пишет без WAL, лишняя блокировка ему
 * ни к чему), CHAIN_DAILY_LIMIT — лимит кредитов в сутки (как в приложении, по умолчанию 25 000).
 *
 * GeckoTerminal спрашивается только в конце, по запросу на пул с паузой 2 мин и без ключа: тот же IP может
 * быть у работающего бота, а его лимит — около 5 запросов в минуту.
 */
class ChainShadowRunTest {

    @Test
    fun shadowRun() = runBlocking {
        val minutes = System.getenv("CHAIN_SHADOW_MINUTES")?.toLongOrNull()
        if (minutes == null) {
            println("ChainShadowRunTest: CHAIN_SHADOW_MINUTES не задан — прогон пропущен")
            return@runBlocking
        }
        val out = File(System.getenv("CHAIN_OUT") ?: "build/chain-shadow").apply { mkdirs() }
        val db = File(System.getenv("CHAIN_DB") ?: "${System.getProperty("user.home")}/.soltradbot/soltradbot.db")
        // Узел: явно заданный, иначе «Solana RPC URL» из настроек бота (там пользователь хранит ключ Helius).
        val rpc = System.getenv("CHAIN_RPC_URL")?.takeIf { it.isNotBlank() }
            ?: runCatching { readSetting(db, "solana.rpc_url") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "https://api.mainnet-beta.solana.com"
        File(out, "events.log").appendText(
            "${System.currentTimeMillis()} узел: ${ChainCandleService.hostOf(rpc)} (helius=${ChainCandleService.isHelius(rpc)})\n"
        )
        val limit = System.getenv("CHAIN_DAILY_LIMIT")?.toIntOrNull() ?: 25_000
        val dex = DexClient()
        val trades = File(out, "trades.csv").apply {
            if (!exists()) writeText("pool,signature,slot,time_ms,exact,buy,token,quote,quote_ccy,quote_usd\n")
        }
        val statusLog = File(out, "status.log")
        val events = File(out, "events.log")
        val gtDir = File(out, "gecko").apply { mkdirs() }
        var lastWatchlist: List<WatchedPool> = emptyList()

        val service = ChainCandleService(
            rpcUrl = { rpc },
            watchlist = {
                runCatching { readWatchlist(db) }.onSuccess { lastWatchlist = it }.getOrDefault(lastWatchlist)
            },
            solPriceUsd = { dex.getSolPriceUsd() },
            geckoCandles = { pool, tf, agg, lim ->
                GeckoTerminalApi.getCandles(pool, tf, agg, lim, maxWaitMs = 180_000L).also { list ->
                    if (list.isNotEmpty()) File(gtDir, "$pool-$agg.csv").writeText(candlesCsv(list))
                }
            },
            dailyCreditLimit = { limit },
            geckoSpacingMs = { System.getenv("CHAIN_GT_SPACING_MS")?.toLongOrNull() ?: 120_000L },
            geckoHistory = false,
            keepLeftMs = 48 * 60 * 60_000L, // выбывшие пулы — до конца прогона
            reconcileEveryMs = Long.MAX_VALUE,
            reportSink = { File(out, "report.md").appendText(it + "\n") },
            tradeSink = { t, q ->
                trades.appendText(
                    "${t.pool},${t.signature},${t.slot},${t.timeMs},${t.timeExact},${t.isBuy}," +
                        "${t.tokenAmount},${t.quoteAmount},${t.quote},$q\n"
                )
            },
            onEvent = { events.appendText("${System.currentTimeMillis()} $it\n") },
            onDiagnostic = { events.appendText("${System.currentTimeMillis()} [diag] $it\n") },
            pingIntervalMs = System.getenv("CHAIN_PING_MS")?.toLongOrNull() ?: ChainCandleService.PING_INTERVAL_MS,
        )
        service.start()
        // Состояние — раз в минуту и во время итоговой сверки: подписки живут и тратят кредиты до самого конца,
        // а по строкам с отметкой времени расход сверяется с панелью Helius на любой момент прогона.
        val logger = launch {
            while (isActive) {
                delay(60_000L)
                // Сбой записи строки состояния не должен оборвать суточный прогон.
                runCatching {
                    logStatus(service, statusLog)
                    writePoolStats(service, File(out, "pools.csv"))
                }
            }
        }
        // Конец — по настенным часам: монотонные могут стоять, пока компьютер спит.
        val end = System.currentTimeMillis() + minutes * 60_000L
        while (System.currentTimeMillis() < end) delay(minOf(60_000L, end - System.currentTimeMillis()).coerceAtLeast(1L))
        // Итог: бары из цепочки и покрытие подпиской — в файлы (без покрытия пропуски потом только оценка),
        // затем сверка (GeckoTerminal — по запросу на пул).
        for ((pool, symbol) in service.poolSymbols()) {
            val spans = service.coverage(pool).joinToString(";") {
                "${it.first}-${if (it.last == Long.MAX_VALUE) "open" else it.last.toString()}"
            }
            File(out, "chain-$pool-5.csv").writeText(
                "# $symbol\n# coverage_ms: $spans\nopen_ms,open,high,low,close,volume,source,trades,open_reliable\n" +
                    service.bars(pool, 5).joinToString("") { b ->
                        val c = b.candle
                        "${c.openTimeMs},${c.open},${c.high},${c.low},${c.close},${c.volume},${b.source},${b.trades},${b.openReliable}\n"
                    }
            )
        }
        writePoolStats(service, File(out, "pools.csv"))
        // Выбывшие из списка пулы сверены при выходе; сверять их заново незачем — за сутки их бары уходят из окна
        // ответа GeckoTerminal. Заново — только пулы, которые ещё в списке, и те, чья сверка не удалась.
        val stillWatched = lastWatchlist.map { it.address }.toSet()
        val reconciled = service.reconcileResults().map { it.pool }.toSet()
        service.reconcileNow(only = service.poolSymbols().keys.filter { it in stillWatched || it !in reconciled }.toSet())
        writePoolStats(service, File(out, "pools.csv"))
        logger.cancel()
        logStatus(service, statusLog)
        service.stop()
        dex.close()
    }

    private fun logStatus(service: ChainCandleService, file: File) {
        val s = service.status.value
        val u = s.usage
        file.appendText(
            "${System.currentTimeMillis()} connected=${s.connected} live=${s.poolsLive}/${s.poolsWatched} " +
                "skipped=${s.skipped} trades=${s.tradesTotal} reconnects=${s.reconnects} drops=${s.subscriptionDrops} " +
                "MB=${u.bytesTotal / 1_000_000.0} bytes=${u.bytesTotal} http=${u.httpCallsTotal} conns=${u.connectionsTotal} " +
                "credits=${u.creditsTotal.roundToLong()} today=${u.creditsToday.roundToLong()} " +
                "perDayNow=${u.creditsPerDayNow.roundToLong()} perDayAvg=${u.creditsPerDayAvg.roundToLong()} " +
                "paused=${s.pausedUntilMs} error=${s.error}\n"
        )
    }

    private suspend fun writePoolStats(service: ChainCandleService, file: File) {
        file.writeText(
            "pool,symbol,dex,subscribed,skip,bytes,measured_ms,credits_day_now,credits_day_avg,trades,noise,failed,truncated\n" +
                service.poolStats().sortedByDescending { it.bytes }.joinToString("") {
                    "${it.pool},${it.symbol.replace(',', ' ')},${it.dex},${it.subscribed},${it.skip ?: ""},${it.bytes}," +
                        "${it.measuredMs},${it.creditsPerDayNow.roundToLong()},${it.creditsPerDayAvg.roundToLong()}," +
                        "${it.trades},${it.noise},${it.failedTx},${it.truncated}\n"
                }
        )
    }

    private fun candlesCsv(list: List<Candle>): String =
        "open_ms,open,high,low,close,volume\n" +
            list.joinToString("") { "${it.openTimeMs},${it.open},${it.high},${it.low},${it.close},${it.volume}\n" }

    /**
     * Запрос к копии базы бота: читать живую незачем — бот пишет её без WAL, лишняя блокировка ему ни к чему.
     * Копия — во временном файле и удаляется сразу после чтения: в базе лежат секреты (seed-фраза, токены).
     */
    private fun <T> withDbCopy(db: File, block: (java.sql.Connection) -> T): T {
        val copy = Files.createTempFile("chain-shadow-", ".db")
        try {
            Files.copy(db.toPath(), copy, StandardCopyOption.REPLACE_EXISTING)
            return DriverManager.getConnection("jdbc:sqlite:${copy.toAbsolutePath().toString().replace('\\', '/')}").use(block)
        } finally {
            Files.deleteIfExists(copy)
        }
    }

    private fun readSetting(db: File, key: String): String? = withDbCopy(db) { c ->
        c.prepareStatement("SELECT val_text FROM settings WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** Список наблюдения из копии базы бота (рваная копия → исключение → прежний список). */
    private fun readWatchlist(db: File): List<WatchedPool> = withDbCopy(db) { c ->
        c.createStatement().use { st ->
            val rs = st.executeQuery("SELECT pair_address, mint, symbol, dex_id, volume_h1_usd FROM token_cache")
            val list = ArrayList<WatchedPool>()
            while (rs.next()) {
                list += WatchedPool(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5))
            }
            list
        }
    }
}
