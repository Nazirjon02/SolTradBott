package tj.khujand.solana.trading.bot

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import kotlin.time.Instant
import tj.khujand.solana.trading.bot.core.engine.ActivityLog
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.exchange.chain.ChainCandleService
import tj.khujand.solana.trading.bot.exchange.chain.ChainCandleStatus
import tj.khujand.solana.trading.bot.exchange.chain.WatchedPool
import tj.khujand.solana.trading.bot.exchange.chain.appendChainFile
import tj.khujand.solana.trading.bot.exchange.chain.chainReportDir
import tj.khujand.solana.trading.bot.exchange.dex.DexClient
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.TokenCache

/** Пауза между своими запросами прототипа к GeckoTerminal: очередь без ключа общая со стратегиями. */
private const val CHAIN_GECKO_SPACING_MS = 90_000L

/** Дольше очереди не ждём: если стратегии ждут своей очереди, их слот прототип не займёт. */
private const val CHAIN_GECKO_MAX_WAIT_MS = 3_000L

/**
 * Прототип свечей из блокчейна для рантайма приложения ([ChainCandleService], теневой режим).
 *
 * Список наблюдения — из кеша сканера, цена SOL — из [DexClient] (Jupiter, кеш 30 с), история и сверка — у
 * GeckoTerminal в очереди торговли без ключа, не дольше [CHAIN_GECKO_MAX_WAIT_MS] ожидания и не чаще раза в
 * [CHAIN_GECKO_SPACING_MS]. Отчёт сверки — `chain/reconcile.md` рядом с базой (на десктопе).
 */
internal fun createChainCandles(
    settingsStore: SettingsStore,
    tokenCache: TokenCache,
    client: DexClient,
    activityLog: ActivityLog,
): ChainCandleService {
    val report = chainReportDir()?.let { "$it/reconcile.md" }
    return ChainCandleService(
        rpcUrl = settingsStore::getRpcUrl,
        watchlist = {
            tokenCache.all().map { WatchedPool(it.pairAddress, it.mint, it.symbol, it.dexId, it.volumeH1Usd) }
        },
        solPriceUsd = client::getSolPriceUsd,
        geckoCandles = { pool, tf, agg, limit ->
            GeckoTerminalApi.getCandles(pool, tf, agg, limit, maxWaitMs = CHAIN_GECKO_MAX_WAIT_MS)
        },
        dailyCreditLimit = settingsStore::getChainCreditLimit,
        geckoSpacingMs = { CHAIN_GECKO_SPACING_MS },
        reportSink = report?.let { path -> { text -> appendChainFile(path, text + "\n") } },
        onEvent = activityLog::info,
    )
}

/**
 * «Настройки» → «Свечи из блокчейна»: выключатель (применяется сразу), состояние и расход кредитов, лимит
 * в сутки. Без рантайма (превью, тесты отрисовки) раздел не показывается.
 */
@Composable
fun ChainCandlesSection() {
    val runtime = DrxRuntimeHolder.get() ?: return
    val service = runtime.chainCandles
    val store = runtime.settingsStore
    val st by service.status.collectAsState()
    var enabled by remember { mutableStateOf(store.getChainCandlesEnabled()) }
    var limit by remember { mutableStateOf(store.getChainCreditLimit().toString()) }
    ChainCandlesSectionContent(
        status = st,
        enabled = enabled,
        limit = limit,
        onToggle = { v ->
            enabled = v
            store.setChainCandlesEnabled(v)
            if (v) service.start() else service.stop()
        },
        onLimit = { v ->
            limit = v
            v.toIntOrNull()?.takeIf { it > 0 }?.let(store::setChainCreditLimit)
        },
    )
}

/**
 * Содержимое раздела — от состояния, без рантайма (рисуется и в тестах). Карточка в «Подключениях» после
 * Telegram; в отличие от остальных полей экрана выключатель и лимит применяются сразу, без «Сохранить».
 */
@Composable
internal fun ChainCandlesSectionContent(
    status: ChainCandleStatus,
    enabled: Boolean,
    limit: String,
    onToggle: (Boolean) -> Unit,
    onLimit: (String) -> Unit,
) {
    DrxCard {
        CardTitle(
            "Свечи из блокчейна",
            icon = Icons.Rounded.CandlestickChart,
            subtitle = "теневой режим — стратегии их не получают",
            trailing = { Pill("ПРОТОТИП", Amber) },
        )
        Spacer(Modifier.height(10.dp))
        HintText(
            "Свечи 1m/5m строятся из сделок в блокчейне (PumpSwap, Raydium) и раз в час сверяются с " +
                "GeckoTerminal. Торговля от них не меняется."
        )
        Spacer(Modifier.height(6.dp))
        SettingSwitch(
            "Строить свечи из блокчейна",
            enabled,
            description = "Включается и выключается сразу, без «Сохранить»",
            onChange = onToggle,
        )
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            val (text, tone) = chainStatusText(status)
            Banner(text, tone)
        }
        Spacer(Modifier.height(12.dp))
        NumberField(
            "Лимит кредитов Helius в сутки",
            limit,
            onChange = onLimit,
            allowDecimal = false,
            hint = "Сохраняется сразу; дорогие пулы отключаются, чтобы сумма уложилась в лимит",
            error = if (limit.toIntOrNull()?.takeIf { it > 0 } == null) "Целое число больше нуля" else null,
        )
        Spacer(Modifier.height(4.dp))
        HintText(
            "Узел — «Solana RPC URL» в карточке «Кошелёк Solana»: https://mainnet.helius-rpc.com/?api-key=… " +
                "(новый адрес подхватывается при следующем включении). Бесплатный Helius — 1 млн кредитов в месяц, " +
                "WebSocket — 2 кредита за 0,1 МБ. На популярные пулы MEV-боты шлют сотни транзакций в секунду, и " +
                "подписка на такой пул стоит десятки тысяч кредитов в сутки — прототип замеряет цену каждого пула " +
                "и дорогие отключает сам."
        )
    }
}

/** Строки состояния и тон плашки. */
private fun chainStatusText(st: ChainCandleStatus): Pair<String, Tone> {
    if (!st.running) return "Не запущено${st.error?.let { ": $it" } ?: ""}" to Tone.BAD
    val lines = ArrayList<String>()
    lines += when {
        !st.connected -> "Подключение к ${st.host}…"
        st.helius -> "Подключено к ${st.host}"
        st.host.contains("publicnode", ignoreCase = true) -> "Подключено к ${st.host} — PublicNode снимает большинство " +
            "подписок (замер 2026-11-12: 45 из 54), свечи будут по малой части пулов; для свечей нужен Helius"
        else -> "Подключено к ${st.host} — это не Helius: кредиты посчитаны так, как их считал бы Helius"
    }
    lines += "Пулов: подписано ${st.poolsLive} из ${st.poolsWatched} в наблюдении"
    if (st.skipped.isNotEmpty()) {
        lines += "Без подписки: " + st.skipped.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key.text} — ${it.value}" }
    }
    lines += "Сделок сегодня: ${st.tradesToday}"
    val perDay = st.usage.creditsPerDayNow.roundToLong()
    lines += "Кредиты: сегодня ${st.usage.creditsToday.roundToLong()} из ${st.dailyLimit}; сейчас ~$perDay в сутки (~${perDay * 30} в месяц)"
    if (st.pausedUntilMs > 0) lines += "Лимит на сутки исчерпан — подписки сняты до ${Instant.fromEpochMilliseconds(st.pausedUntilMs)}"
    if (st.reconnects > 0) lines += "Переподключений: ${st.reconnects}"
    if (st.subscriptionDrops > 0) {
        lines += "Узел сам снял подписок: ${st.subscriptionDrops} — эти пулы переподписываются, их бары за это время берутся у GeckoTerminal"
    }
    st.lastReconcile?.let { lines += it.replaceFirstChar { c -> c.uppercase() } }
    st.error?.let { lines += "Ошибка: $it" }
    val tone = when {
        st.pausedUntilMs > 0 || st.error != null -> Tone.WARN
        st.connected -> Tone.OK
        else -> Tone.WARN
    }
    return lines.joinToString("\n") to tone
}
