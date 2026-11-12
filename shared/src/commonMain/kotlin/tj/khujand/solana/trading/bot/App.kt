package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.engine.ActivityEvent
import tj.khujand.solana.trading.bot.core.engine.ActivityStats
import tj.khujand.solana.trading.bot.core.engine.fmtNum
import tj.khujand.solana.trading.bot.core.engine.runStartedAt
import tj.khujand.solana.trading.bot.core.engine.runSummaries
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyManager
import tj.khujand.solana.trading.bot.domain.dars.CoinAnalysis
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalysis
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.core.strategy.WatchItem
import tj.khujand.solana.trading.bot.core.strategy.WatchStatus
import tj.khujand.solana.trading.bot.core.strategy.diagnoseEntries
import tj.khujand.solana.trading.bot.data.db.Trade
import tj.khujand.solana.trading.bot.domain.backtest.BacktestReport
import tj.khujand.solana.trading.bot.domain.backtest.BacktestRequest
import tj.khujand.solana.trading.bot.domain.backtest.BacktestService
import tj.khujand.solana.trading.bot.domain.backtest.BacktestSettings
import tj.khujand.solana.trading.bot.domain.backtest.BacktestState
import tj.khujand.solana.trading.bot.domain.backtest.BacktestStats
import tj.khujand.solana.trading.bot.domain.backtest.backtestHigherTf
import tj.khujand.solana.trading.bot.exchange.dex.DEFAULT_KEY_MONTHLY_QUOTA
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.exchange.dex.GtPoolList
import tj.khujand.solana.trading.bot.data.AccountBalance
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.OpenPosition
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

// ─── Оболочка приложения ──────────────────────────────────────────────────────

@Composable
fun App(runtime: DrxRuntime? = null) {
    DrxTheme { DrxApp(runtime) }
}

/**
 * Оболочка: вкладки, экраны поверх них (форма стратегии, бэктест, график) и общее состояние. На узком экране вкладки
 * внизу, на широком окне (десктоп, планшет) — боковая панель, а главная и «Сделки» раскладываются в две колонки.
 * [forceWide] — для рендер-тестов.
 */
@Composable
fun DrxApp(runtime: DrxRuntime? = null, forceWide: Boolean? = null) {
    BoxWithConstraints(Modifier.fillMaxSize().background(BgDark)) {
        DrxAppContent(runtime, wide = forceWide ?: (maxWidth >= WideLayoutMinWidth))
    }
}

@Composable
private fun DrxAppContent(runtime: DrxRuntime?, wide: Boolean) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    val strategies = remember { mutableStateListOf<StrategyConfig>() }
    var editingStrategy by remember { mutableStateOf<StrategyConfig?>(null) }
    var showForm by remember { mutableStateOf(false) }
    // Открытый бэктест (поверх вкладок); из него можно уйти в форму стратегии и вернуться.
    var backtestFor by remember { mutableStateOf<StrategyConfig?>(null) }
    // Открытый график бота: монета с доски наблюдения или открытая позиция. На широком окне — рядом с главной.
    var chartTarget by remember { mutableStateOf<ChartTarget?>(null) }
    // Ручная покупка из «Анализа» — диалог поверх экрана.
    var manualBuy by remember { mutableStateOf<CoinAnalysis?>(null) }

    val db = runtime?.db
    val settingsStore = runtime?.settingsStore
    val engine = runtime?.engine

    // Настройки: сохранённые и черновик правок. Черновик живёт здесь, а не в экране настроек, — переход на другую
    // вкладку не теряет набранное, а на вкладке «Настройки» горит точка, пока правки не сохранены.
    var settings by remember { mutableStateOf(loadSettings(settingsStore)) }
    var settingsDraft by remember { mutableStateOf(settings) }
    var settingsFormVersion by remember { mutableStateOf(0) }

    // Режим DEMO/REAL — применяется сразу (исполнитель читает флаг из БД на каждой сделке).
    var demoMode by remember { mutableStateOf(settingsStore?.getDemoMode() ?: true) }
    // SHADOW (DEMO по котировкам Jupiter) — тоже сразу.
    var shadowMode by remember { mutableStateOf(settingsStore?.getShadowQuotes() ?: false) }
    // Возобновление торговли при запуске приложения — читается при следующем запуске.
    var resumeOnLaunch by remember { mutableStateOf(settingsStore?.getResumeOnLaunch() ?: false) }

    // Режим «только сигнал» — общий StateFlow с Telegram.
    val fallbackSignalOnly = remember { MutableStateFlow(false) }
    val signalOnly by (runtime?.strategyManager?.signalOnly ?: fallbackSignalOnly).collectAsState()

    // Telegram-контроллером (long-polling команд) владеет DrxRuntime — он живёт вместе с движком и переживает уход
    // Activity. При смене токена/чата UI лишь дёргает runtime.syncTelegramController() в обработчике сохранения.

    val fallbackStatus = remember { MutableStateFlow(BotStatus.STOPPED) }
    val botStatus by (engine?.status ?: fallbackStatus).collectAsState()

    val activityFallback = remember { MutableStateFlow<List<ActivityEvent>>(emptyList()) }
    val activity by (runtime?.activityLog?.events ?: activityFallback).collectAsState()
    val statsFallback = remember { MutableStateFlow(ActivityStats()) }
    val activityStats by (runtime?.activityLog?.stats ?: statsFallback).collectAsState()
    val errorsFallback = remember { MutableStateFlow<List<ActivityEvent>>(emptyList()) }
    val sessionErrors by (runtime?.activityLog?.errors ?: errorsFallback).collectAsState()

    // Доска наблюдения — строки приходят из скан-цикла стратегий.
    val watchFallback = remember { MutableStateFlow<List<WatchItem>>(emptyList()) }
    val watchItems by (runtime?.watchBoard?.items ?: watchFallback).collectAsState()

    // Воронка входа: перечитываем после каждого отчёта стратегий и раз в 15 с — окно в час сдвигается и без них.
    val funnel = runtime?.entryFunnel
    val funnelFallback = remember { MutableStateFlow(0L) }
    val funnelVersion by (funnel?.version ?: funnelFallback).collectAsState()
    var clockTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            clockTick++
        }
    }
    val funnelSnapshot = remember(funnelVersion, clockTick) { funnel?.snapshot() }
    val feed by GeckoTerminalApi.status.collectAsState()

    var balance by remember { mutableStateOf<AccountBalance?>(null) }
    LaunchedEffect(engine, demoMode) {
        val e = engine ?: return@LaunchedEffect
        while (true) {
            balance = runCatching { e.getBalance() }.getOrNull()
            delay(30_000)
        }
    }

    // «Живые» открытые позиции с текущим PnL — для главной и ручного закрытия.
    val openPositions = remember { mutableStateListOf<OpenPosition>() }
    suspend fun refreshPositions() {
        val e = engine ?: return
        runCatching { e.getPositions() }.getOrNull()?.let {
            openPositions.clear(); openPositions.addAll(it)
        }
    }
    LaunchedEffect(engine, demoMode) {
        if (engine == null) return@LaunchedEffect
        while (true) {
            refreshPositions()
            delay(15_000)
        }
    }

    fun reload() {
        val store = runtime?.strategyStore ?: return
        strategies.clear()
        strategies.addAll(store.loadAll())
    }
    LaunchedEffect(runtime) { reload() }

    // Режим, защиту и автоматику могли переключить из Telegram (/mode, /settings) — при смене вкладки перечитываем
    // их из БД, иначе экран показывал бы старое, а «Сохранить» вернуло бы старые значения поверх новых.
    LaunchedEffect(screen) {
        val s = settingsStore ?: return@LaunchedEffect
        demoMode = s.getDemoMode() ?: true
        shadowMode = s.getShadowQuotes()
        if (screen == Screen.SETTINGS) {
            val fresh = s.getProtection()
            if (fresh != settings.protection) {
                // Защиту в черновике не правили — свежие значения идут и в него; правили — набранное остаётся.
                if (settingsDraft.protection == settings.protection) {
                    settingsDraft = settingsDraft.copy(protection = fresh)
                    settingsFormVersion++
                }
                settings = settings.copy(protection = fresh)
            }
            resumeOnLaunch = s.getResumeOnLaunch()
        }
    }

    // Закрытые сделки: SQLDelight сообщает о каждой записи в таблицу сделок — главная и «Сделки» обновляются, как
    // только монитор закрыл сделку, а не при смене вкладки.
    var closedTrades by remember { mutableStateOf(loadClosedTrades(db)) }
    LaunchedEffect(db) {
        val d = db ?: return@LaunchedEffect
        d.tradeQueries.getAll().asFlow().mapToList(Dispatchers.Default).collect { rows -> closedTrades = rows.toHistoryItems() }
    }
    // Сделка закрылась — позиция ушла: список позиций обновляем сразу, не дожидаясь 15-секундного цикла.
    LaunchedEffect(closedTrades.size) { refreshPositions() }

    // «Новый старт»: итоги главной и «Аналитики» — с начала забега текущего режима (0 — вся история).
    var runVersion by remember { mutableStateOf(0) }
    val runStart = remember(screen, demoMode, runVersion, closedTrades.size) { db?.runStartedAt(demoMode) ?: 0L }
    val runs = remember(screen, demoMode, runVersion, closedTrades.size) {
        db?.let { runCatching { runSummaries(it, demoMode) }.getOrNull() } ?: emptyList()
    }
    val runTrades = if (runStart > 0) closedTrades.filter { it.isDemo == demoMode && it.closedAt >= runStart } else closedTrades
    val mode = accountMode(demoMode, shadowMode)

    fun select(target: Screen) {
        screen = target
        // Переход на вкладку закрывает экраны поверх неё — иначе вкладка «не переключалась бы».
        chartTarget = null
        backtestFor = null
    }

    val onStatusChange: (BotStatus) -> Unit = { target ->
        scope.launch {
            when (target) {
                BotStatus.RUNNING -> {
                    if (isAndroid()) createServiceController().startMonitoring()
                    engine?.start()
                }
                BotStatus.PAUSED -> engine?.pause()
                BotStatus.STOPPED -> {
                    engine?.stop()
                    if (isAndroid()) createServiceController().stopMonitoring()
                }
            }
        }
    }

    val body: @Composable () -> Unit = {
        val chart = chartTarget
        val bt = backtestFor
        when {
            showForm -> StrategyFormScreen(
                initial = editingStrategy,
                onSave = { cfg ->
                    runtime?.strategyStore?.save(cfg)
                    // Работающий сканер держит старый конфиг — перезапускаем его с новым.
                    scope.launch { engine?.reloadStrategy(cfg.id) }
                    reload()
                    showForm = false
                    editingStrategy = null
                    // Правили параметры из бэктеста — возвращаемся к нему с новым конфигом.
                    backtestFor = backtestFor?.let { b -> strategies.firstOrNull { it.id == b.id } }
                },
                onCancel = { showForm = false; editingStrategy = null }
            )
            // На широком окне график сделки открывается рядом с главной, а не вместо неё.
            chart != null && !(wide && screen == Screen.DASHBOARD) -> BotChartScreen(runtime, chart) { chartTarget = null }
            bt != null -> BacktestScreen(
                config = bt,
                service = runtime?.backtest,
                onEditStrategy = { editingStrategy = backtestFor; showForm = true },
                onClose = { backtestFor = null }
            )
            else -> when (screen) {
                Screen.DASHBOARD -> {
                    val now = Clock.System.now().toEpochMilliseconds()
                    DashboardScreen(
                        botStatus = botStatus,
                        strategies = strategies,
                        balance = balance,
                        connected = runtime != null,
                        activity = activity,
                        stats = activityStats,
                        errors = sessionErrors,
                        trades = runTrades,
                        openPositions = openPositions,
                        onClosePosition = { tradeId ->
                            scope.launch {
                                engine?.closePosition(tradeId)
                                refreshPositions() // сразу обновляем список после закрытия
                            }
                        },
                        watch = watchItems,
                        onWatchClick = { w -> chartTarget = ChartTarget(w.mint, w.symbol, w.pairAddress, strategyId = w.strategyId) },
                        onPositionChart = { p ->
                            // Пул и стратегию берём из строки сделки: в карточке позиции их нет.
                            db?.tradeQueries?.getById(p.tradeId)?.executeAsOneOrNull()?.let { t ->
                                chartTarget = ChartTarget(t.mint, t.symbol, t.pair_address, t.strategy_id, t.id)
                            }
                        },
                        diagnosis = funnelSnapshot?.let { snap ->
                            diagnoseEntries(
                                status = botStatus,
                                activeStrategyIds = strategies.filter { it.isActive }.map { it.id }.toSet(),
                                funnel = snap,
                                signalOnly = signalOnly,
                                nearest = watchItems.firstOrNull { it.status != WatchStatus.NONE },
                                candlesLimited = feed.lastRateLimitAtMs > 0L && now - feed.lastRateLimitAtMs < 10 * 60_000L,
                                nowMs = now,
                            )
                        },
                        funnel = funnelSnapshot,
                        signalOnly = signalOnly,
                        lastCycleAgo = funnelSnapshot?.lastCycleAt?.let { agoLabel(it, now) },
                        modeLabel = mode.label,
                        wide = wide,
                        sidePane = if (wide && chart != null) {
                            { BotChartScreen(runtime, chart) { chartTarget = null } }
                        } else null,
                        onOpenTrades = { select(Screen.TRADES) },
                        onOpenStrategies = { select(Screen.STRATEGIES) },
                        onStatusChange = onStatusChange,
                    )
                }
                Screen.ANALYSIS -> AnalysisScreen(
                    candidates = runtime?.tokenCache?.all() ?: emptyList(),
                    analyzer = runtime?.marketAnalyzer,
                    darsConfig = strategies.activeDarsConfig(),
                    settingsStore = settingsStore,
                    onBuy = if (runtime != null) { a -> manualBuy = a } else null,
                )
                Screen.TRADES -> TradesScreen(closedTrades, runs, runStart, demoMode, wide)
                Screen.STRATEGIES -> StrategiesScreen(
                    strategies = strategies,
                    onCreate = { showForm = true; editingStrategy = null },
                    onEdit = { cfg -> editingStrategy = cfg; showForm = true },
                    onDelete = { cfg ->
                        runtime?.strategyStore?.delete(cfg.id)
                        // Удалённая стратегия не должна продолжать сканировать и входить.
                        scope.launch { engine?.reloadStrategy(cfg.id) }
                        reload()
                    },
                    onToggle = { cfg ->
                        runtime?.strategyStore?.setActive(cfg.id, !cfg.isActive)
                        scope.launch { engine?.applyStrategyActive(cfg.id, !cfg.isActive) }
                        reload()
                    },
                    onBacktest = { cfg -> backtestFor = cfg },
                    wide = wide,
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    draft = settingsDraft,
                    formVersion = settingsFormVersion,
                    onDraftChange = { settingsDraft = it },
                    onDiscard = {
                        settingsDraft = settings
                        settingsFormVersion++
                    },
                    connected = runtime != null,
                    demoMode = demoMode,
                    shadowMode = shadowMode,
                    demoBalance = balance?.demoUsd?.takeIf { it > 0.0 } ?: 10_000.0,
                    signalOnly = signalOnly,
                    resumeOnLaunch = resumeOnLaunch,
                    onResumeOnLaunchChange = { v ->
                        resumeOnLaunch = v
                        settingsStore?.setResumeOnLaunch(v)
                    },
                    onDemoModeChange = { v ->
                        // REAL требует seed-фразы — экран не даст выбрать REAL без неё, а исполнитель и так не откроет сделку.
                        demoMode = v
                        settingsStore?.setDemoMode(v)
                    },
                    onShadowModeChange = { v ->
                        shadowMode = v
                        settingsStore?.setShadowQuotes(v)
                    },
                    onSignalOnlyChange = { v ->
                        val sm = runtime?.strategyManager
                        if (sm != null) sm.setSignalOnly(v) else fallbackSignalOnly.value = v
                    },
                    onResetDemo = { runtime?.executor?.resetDemoBalance() },
                    onResetBot = { runtime?.resetBotData(); runVersion++ },
                    onNewRun = { newBalance ->
                        val message = runtime?.startNewRun(newBalance) ?: "Рантайм не подключён"
                        runVersion++
                        message
                    },
                    onCorrectKeyUsage = { GeckoTerminalApi.correctKeyMonthUsage(it) },
                    wide = wide,
                    onSave = { newSettings ->
                        settingsStore?.apply {
                            setRpcUrl(newSettings.rpcUrl.trim())
                            if (newSettings.walletSeed.isNotBlank()) setWalletSeed(newSettings.walletSeed.trim())
                            setTelegramToken(newSettings.telegramToken.trim())
                            setTelegramChatId(newSettings.telegramChatId.trim().toLongOrNull() ?: 0L)
                            // Защиту читают на каждой проверке — действует со следующего входа/выхода.
                            setProtection(newSettings.protection)
                            setCoinGeckoKey(newSettings.coinGeckoKey.trim())
                            setCoinGeckoQuota(newSettings.coinGeckoQuota)
                        }
                        // Ключ свечей применяется сразу: следующий запрос уйдёт уже с ним.
                        GeckoTerminalApi.setApiKey(newSettings.coinGeckoKey)
                        // Новый токен/чат применяем к живому notifier движка сразу.
                        runtime?.notifier?.updateCredentials(
                            newSettings.telegramToken.trim(),
                            newSettings.telegramChatId.trim().toLongOrNull() ?: 0L
                        )
                        // И перезапускаем long-polling контроллера под новый токен/чат.
                        runtime?.syncTelegramController()
                        // Пустая seed-фраза при сохранении не стирает сохранённую — в черновике остаётся она же.
                        val stored = if (newSettings.walletSeed.isBlank()) newSettings.copy(walletSeed = settings.walletSeed) else newSettings
                        settings = stored
                        settingsDraft = stored
                    },
                )
            }
        }
    }

    val badges = if (settingsDraft != settings) setOf(Screen.SETTINGS) else emptySet()
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            if (!showForm) {
                val (statusColor, statusLabel) = when (botStatus) {
                    BotStatus.RUNNING -> Green to "работает"
                    BotStatus.PAUSED -> Amber to "пауза"
                    BotStatus.STOPPED -> Red to "стоп"
                }
                DrxNavRail(screen, statusColor, statusLabel, badges) { select(it) }
            }
            // Нижней панели в широком режиме нет — отступ под системную навигацию (планшет) держит сам контент.
            Box(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) { body() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { body() }
            if (!showForm) DrxBottomBar(screen, badges) { select(it) }
        }
    }

    manualBuy?.let { coin ->
        val rt = runtime ?: return@let
        ManualBuyDialog(
            coin = coin,
            modeLabel = rt.executor.modeLabel(),
            isReal = !rt.executor.isDemo(),
            // По умолчанию — 5% баланса, как у стратегий по умолчанию.
            defaultSizeUsd = (balance?.totalUsd ?: 0.0).let { b -> if (b > 0) kotlin.math.round(b * 0.05) else 50.0 },
            onBuy = { size, sl, tp ->
                when (val r = rt.manualBuy(coin.mint, size, sl, tp)) {
                    is StrategyManager.ManualResult.Opened ->
                        "✅ Куплено ${r.symbol} на $${fmtNum(r.sizeUsd)} по ${MarketAnalysis.fmtPrice(r.entryPrice)} — позиция на главной"
                    is StrategyManager.ManualResult.Rejected -> "⛔ ${r.reason}"
                }
            },
            onDismiss = {
                manualBuy = null
                scope.launch { refreshPositions() }
            },
        )
    }
}

/** «12 с назад», «3 мин назад» — когда стратегии последний раз проходили цикл. */
private fun agoLabel(ms: Long, nowMs: Long): String {
    val s = ((nowMs - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s с назад"
        s < 3600 -> "${s / 60} мин назад"
        else -> "${s / 3600} ч назад"
    }
}

data class DrxSettings(
    val rpcUrl: String = DEFAULT_RPC_URL,
    val walletSeed: String = "",
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val protection: ProtectionSettings = ProtectionSettings(),
    /** Demo-ключ CoinGecko для свечей; пусто — без ключа (GeckoTerminal пускает считаные запросы в минуту). */
    val coinGeckoKey: String = "",
    /** Месячная квота ключа CoinGecko: из неё считается дневной лимит (см. [GeckoTerminalApi]). */
    val coinGeckoQuota: Int = DEFAULT_KEY_MONTHLY_QUOTA,
)

/** Публичный RPC Solana — когда свой узел не задан. */
internal const val DEFAULT_RPC_URL = "https://api.mainnet-beta.solana.com"

/** Настройки из БД (кошелёк, Telegram, RPC, защита, ключ свечей). Chat ID 0 — «не задан». */
internal fun loadSettings(store: SettingsStore?): DrxSettings = store?.let { s ->
    DrxSettings(
        rpcUrl = s.getRpcUrl() ?: DEFAULT_RPC_URL,
        walletSeed = s.getWalletSeed() ?: "",
        telegramToken = s.getTelegramToken() ?: "",
        telegramChatId = s.getTelegramChatId()?.takeIf { it != 0L }?.toString() ?: "",
        protection = s.getProtection(),
        coinGeckoKey = s.getCoinGeckoKey() ?: "",
        coinGeckoQuota = s.getCoinGeckoQuota(),
    )
} ?: DrxSettings()

// ─── Закрытые сделки и общие форматы ──────────────────────────────────────────

data class TradeHistoryItem(
    val symbol: String,
    val strategy: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnl: Double,
    val pnlPercent: Double,
    val sizeUsd: Double,
    val isDemo: Boolean,
    val duration: String,
    val isWin: Boolean,
    val openReason: String,
    val closeReason: String,
    val closedAt: Long = 0L,
    val fee: Double = 0.0,
    val mint: String = "",
    val openedAt: Long = 0L
)

/** Строки таблицы сделок → закрытые сделки для истории (новые сверху — как отдаёт запрос). */
internal fun List<Trade>.toHistoryItems(): List<TradeHistoryItem> = filter { it.status == "CLOSED" }.map { t ->
    TradeHistoryItem(
        symbol = t.symbol,
        strategy = t.strategy_name,
        entryPrice = t.entry_price,
        exitPrice = t.exit_price ?: t.entry_price,
        pnl = t.pnl ?: 0.0,
        pnlPercent = t.pnl_percent ?: 0.0,
        sizeUsd = t.size_usd,
        isDemo = t.is_demo == 1L,
        duration = formatTradeDuration((t.closed_at ?: t.opened_at) - t.opened_at),
        isWin = (t.pnl ?: 0.0) > 0,
        openReason = t.open_reason,
        closeReason = t.close_reason ?: "—",
        closedAt = t.closed_at ?: t.opened_at,
        fee = t.fee ?: 0.0,
        mint = t.mint,
        openedAt = t.opened_at
    )
}

/** Закрытые сделки из БД (новые сверху) → элементы истории. */
private fun loadClosedTrades(db: DrxDatabase?): List<TradeHistoryItem> {
    if (db == null) return emptyList()
    return runCatching { db.tradeQueries.getAll().executeAsList().toHistoryItems() }.getOrDefault(emptyList())
}

private fun formatTradeDuration(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h} ч ${m} мин" else "${m} мин"
}

/** Кривая доходности: кумулятивный PnL по закрытым сделкам в хронологическом порядке. */
internal fun equityCurve(trades: List<TradeHistoryItem>): List<Double> {
    var acc = 0.0
    return trades.sortedBy { it.closedAt }.map { acc += it.pnl; acc }
}

internal fun startOfTodayMillis(): Long {
    val tz = TimeZone.currentSystemDefault()
    return Clock.System.now().toLocalDateTime(tz).date.atStartOfDayIn(tz).toEpochMilliseconds()
}

internal fun dateLabel(ms: Long): String {
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.day.toString().padStart(2, '0')}.${(dt.month.ordinal + 1).toString().padStart(2, '0')}"
}

internal fun formatClock(millis: Long): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${p(dt.hour)}:${p(dt.minute)}:${p(dt.second)}"
}

/** «Ab3dEf…7xYz» — короткий вид адреса контракта. */
internal fun shortMint(mint: String): String =
    if (mint.length <= 14) mint else "${mint.take(6)}…${mint.takeLast(6)}"

/** Цены мемкоинов крошечные — до 8 значащих знаков после запятой, без хвостовых нулей. */
internal fun fmtPrice(v: Double): String {
    if (v == 0.0) return "0"
    return formatNumberRu(v, if (v >= 1) 4 else 8).trimEnd('0').trimEnd(',')
}

internal fun String.toTypeLabel(): String =
    StrategyType.entries.firstOrNull { it.name == this }?.displayName ?: this

// ─── Backtest screen ──────────────────────────────────────────────────────────

private fun GtPoolList.label(): String = when (this) {
    GtPoolList.TRENDING -> "Трендовые"
    GtPoolList.NEW -> "Новые"
    GtPoolList.TOP -> "Топ по объёму"
}

private fun fmtR(v: Double): String = "${if (v >= 0) "+" else "−"}${"%.2f".format(kotlin.math.abs(v))}R"

/**
 * Бэктест стратегии по истории GeckoTerminal: те же вход и выходы, что у бота (см. [Backtester]).
 * Загруженные свечи держит [BacktestService] — после правки параметров в форме прогон повторяется
 * на тех же данных мгновенно.
 */
@Composable
fun BacktestScreen(
    config: StrategyConfig,
    service: BacktestService?,
    onEditStrategy: () -> Unit,
    onClose: () -> Unit,
) {
    val initial = service?.lastRequest ?: BacktestRequest()
    var source by remember { mutableStateOf(initial.source) }
    var coins by remember { mutableStateOf(initial.coins.toFloat()) }
    var days by remember { mutableStateOf(initial.days) }
    var cost by remember { mutableStateOf(initial.settings.costPerSidePercent.toFloat()) }
    var oneTrade by remember { mutableStateOf(initial.settings.oneTradePerCoin) }
    var reversalExit by remember { mutableStateOf(initial.settings.reversalExit) }
    val request = BacktestRequest(source, coins.toInt(), days, BacktestSettings(cost.toDouble(), oneTrade, reversalExit))

    val fallback = remember { MutableStateFlow<BacktestState>(BacktestState.Idle) }
    val state by (service?.state ?: fallback).collectAsState()
    val busy = state is BacktestState.Loading || state is BacktestState.Running
    val cached = service?.hasData(config, request) == true

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🧪 Бэктест", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Surface(
                color = SurfaceCard, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable { onClose() }
            ) {
                Text("✕ Закрыть", color = TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(config.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                "${config.type.toTypeLabel()} · ТФ ${config.timeframe}" +
                    (config.backtestHigherTf()?.let { " · тренд $it" } ?: ""),
                fontSize = 12.sp, color = TextSecondary
            )

            FormSection("📥  Данные") {
                Text("Монеты", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GtPoolList.entries.forEach { s -> ChoiceChip(s.label(), source == s) { source = s } }
                }
                FormSlider("Сколько монет", coins, 5f, 50f, "${coins.toInt()}") { coins = it }
                Text("Период истории", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 7, 14, 30).forEach { d -> ChoiceChip("$d д", days == d) { days = d } }
                }
                Spacer(Modifier.height(8.dp))
                val hint = if (cached) {
                    "✓ История уже загружена — прогон мгновенный."
                } else {
                    val req = service?.estimatedRequests(config, request) ?: 0
                    // С ключом CoinGecko запрос идёт раз в ~2.5 с (пауза загрузчика), без ключа — раз в 15 с (очередь API).
                    val withKey = GeckoTerminalApi.status.value.usingKey
                    val minutes = (req * (if (withKey) 2.5 else 15.0) / 60).toInt() + 1
                    "≈ $req запросов к GeckoTerminal — загрузка ~$minutes мин" +
                        (if (withKey) ". " else " (без ключа CoinGecko — 4 запроса в минуту; с ключом из «Настроек» быстрее). ") +
                        "Потом историю можно прогонять сколько угодно раз."
                }
                Text(hint, fontSize = 11.sp, color = if (cached) Green else TextSecondary, lineHeight = 16.sp)
                if (source != GtPoolList.NEW) {
                    Spacer(Modifier.height(6.dp))
                    Surface(color = Amber.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "⚠️ Трендовые и топовые монеты — это выжившие: результат будет лучше, чем у бота " +
                                "в реальности. «Новые» честнее (среди них есть умершие), но у них меньше истории.",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
            }

            FormSection("⚙️  Исполнение") {
                FormSlider("Издержки на вход и каждый выход", cost, 0f, 5f,
                    "${"%.1f".format(cost)}% за сторону") { cost = it }
                FormToggle("Одна сделка на монету (как в боте)", oneTrade) { oneTrade = it }
                FormToggle("Выход по развороту (как тумблер в защите)", reversalExit) { reversalExit = it }
            }

            when (val s = state) {
                is BacktestState.Loading -> BacktestProgress("📥 ${s.message}", s.done, s.total)
                is BacktestState.Running -> BacktestProgress("⚙️ Прогон стратегии", s.done, s.total)
                is BacktestState.Failed -> Surface(color = Red.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp)) {
                    Text("❌ ${s.message}", fontSize = 13.sp, color = Red, modifier = Modifier.padding(12.dp))
                }
                is BacktestState.Done -> if (s.strategyId == config.id) BacktestResults(s.report)
                BacktestState.Idle -> Unit
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (busy) {
                Button(
                    onClick = { service?.cancel() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) { Text("⏹  Остановить", fontWeight = FontWeight.Bold, color = Color.White) }
            } else {
                Button(
                    onClick = { service?.start(config, request) },
                    enabled = service != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text(if (cached) "🔁  Прогнать снова" else "▶  Загрузить и прогнать",
                        fontWeight = FontWeight.Bold, color = Color.Black)
                }
                Button(
                    onClick = onEditStrategy,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 16.dp, horizontal = 14.dp)
                ) { Text("✏️ Параметры", color = TextPrimary) }
            }
        }
    }
}

@Composable
private fun BacktestProgress(message: String, done: Int, total: Int) {
    Surface(color = SurfaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(message, fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = Green,
                trackColor = BorderColor,
            )
            Spacer(Modifier.height(6.dp))
            Text("$done / $total", fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun BacktestResults(report: BacktestReport) {
    val s = report.stats
    Text(
        "${report.coinsWithData}/${report.coins} монет с историей · ${report.bars} свечей · ${s.trades} сделок",
        fontSize = 12.sp, color = TextSecondary
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Сделок", "${s.trades}", "${s.wins} в плюс · ${s.losses} в минус", TextPrimary, Modifier.weight(1f))
        StatCard("Winrate", "${s.winRate.toInt()}%", "доля прибыльных",
            if (s.trades == 0) TextSecondary else if (s.winRate >= 50) Green else Amber, Modifier.weight(1f))
        StatCard("Ожидание", fmtR(s.expectancyR), "на сделку",
            if (s.expectancyR > 0) Green else Red, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Profit factor",
            if (s.profitFactor.isInfinite()) "∞" else "%.2f".format(s.profitFactor), "прибыль / убыток",
            if (s.profitFactor >= 1) Green else Red, Modifier.weight(1f))
        StatCard("Сумма", fmtR(s.totalR), "всех сделок", if (s.totalR >= 0) Green else Red, Modifier.weight(1f))
        StatCard("Счёт", "${if (s.equityPct >= 0) "+" else ""}${"%.1f".format(s.equityPct)}%",
            "при риске ${BacktestStats.RISK_PER_TRADE_PCT.toInt()}% на сделку",
            if (s.equityPct >= 0) Green else Red, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Ср. прибыль", fmtR(s.avgWinR), "на выигрыш", Green, Modifier.weight(1f))
        StatCard("Ср. убыток", fmtR(s.avgLossR), "на проигрыш", Red, Modifier.weight(1f))
        StatCard("Просадка", "−${"%.1f".format(s.maxDrawdownPct)}%", "макс. по счёту",
            if (s.maxDrawdownPct > 20) Red else Amber, Modifier.weight(1f))
    }

    val verdict = when {
        s.trades == 0 -> "Сигналов не было. Ослабьте фильтры или пороги, возьмите больше монет или период."
        s.trades < 30 -> "Сделок меньше 30 — статистика ненадёжна. Увеличьте число монет или период."
        s.expectancyR > 0 -> "Ожидание положительное: в среднем ${fmtR(s.expectancyR)} на сделку. " +
            "Проверьте на «Новых» монетах и другом периоде, чтобы не подогнать параметры под одну выборку."
        else -> "Ожидание отрицательное — с этими параметрами стратегия в среднем теряет."
    }
    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
        Text(verdict, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.padding(10.dp), lineHeight = 17.sp)
    }

    if (s.exitReasons.isNotEmpty()) {
        FormSection("🚪  Причины выхода") {
            s.exitReasons.forEach { (reason, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(reason, fontSize = 13.sp, color = TextPrimary)
                    Text("$n", fontSize = 13.sp, color = TextSecondary)
                }
            }
        }
    }

    if (report.trades.isNotEmpty()) {
        val shown = report.trades.sortedByDescending { it.exitTimeMs }.take(50)
        FormSection("📜  Сделки (последние ${shown.size})") {
            shown.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.symbol} · ${dateLabel(t.entryTimeMs)} ${formatClock(t.entryTimeMs).dropLast(3)}",
                            fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.exitReason, fontSize = 11.sp, color = TextSecondary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtR(t.rMultiple), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (t.rMultiple > 0) Green else Red)
                        Text("${if (t.returnPct >= 0) "+" else ""}${"%.1f".format(t.returnPct)}%",
                            fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }
    }

    FormSection("ℹ️  Как считается") {
        Text(
            "Стратегия видит только закрытые свечи; вход — по открытию следующей свечи (если цена не ушла " +
                "больше чем на 3%). Выходы — те же правила, что у бота: стоп, TP, лестница в R, трейлинги, " +
                "тайм-стоп. Внутри свечи цена идёт open→low→high→close (бычья) или open→high→low→close " +
                "(медвежья); стоп и тейк исполняются по своему уровню, при гэпе — по открытию. " +
                "Капитализация в прошлом оценена пропорционально цене, ликвидность — как √цены. " +
                "Ликвидность-стоп, лимит позиций и кулдаун не моделируются. Позиция, открытая на конец " +
                "истории, закрывается по последней цене («Конец данных»).",
            fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
        )
    }
}

// ─── Strategy form ────────────────────────────────────────────────────────────

@Composable
fun StrategyFormScreen(
    initial: StrategyConfig?,
    onSave: (StrategyConfig) -> Unit,
    onCancel: () -> Unit
) {
    val isEdit = initial != null
    val c = initial

    // ── Основное ──
    var name by remember { mutableStateOf(c?.name ?: "") }
    var type by remember { mutableStateOf(c?.type ?: StrategyType.DARS.name) }
    var timeframe by remember { mutableStateOf(c?.timeframe ?: "1m") }

    // ── Размер / исполнение ──
    var positionSize by remember { mutableStateOf(c?.positionSize?.toFloat() ?: 5f) }
    var maxPositions by remember { mutableStateOf((c?.maxPositions ?: 1).toString()) }
    var slippage by remember { mutableStateOf(c?.slippagePercent?.toFloat() ?: 1.5f) }

    // ── SL ──
    var stopLoss by remember { mutableStateOf(c?.stopLossPercent?.toFloat() ?: 15f) }
    var trailingEnabled by remember { mutableStateOf(c?.trailingStopEnabled ?: false) }
    var trailingPercent by remember { mutableStateOf(c?.trailingStopPercent?.toFloat() ?: 10f) }
    var trailingActivation by remember { mutableStateOf(c?.trailingActivationPercent?.toFloat() ?: 15f) }
    var breakEvenEnabled by remember { mutableStateOf(c?.breakEvenEnabled ?: false) }
    var breakEvenTrigger by remember { mutableStateOf(c?.breakEvenTriggerPercent?.toFloat() ?: 10f) }
    var breakEvenOffset by remember { mutableStateOf(c?.breakEvenOffsetPercent?.toFloat() ?: 1f) }

    // ── TP ──
    var takeProfit by remember { mutableStateOf(c?.takeProfitPercent?.toFloat() ?: 30f) }
    var partialTpEnabled by remember { mutableStateOf(c?.partialTpEnabled ?: false) }
    var tp1Percent by remember { mutableStateOf(c?.tp1Percent?.toFloat() ?: 15f) }
    var tp1Close by remember { mutableStateOf(c?.tp1ClosePercent?.toFloat() ?: 50f) }
    var tp2Percent by remember { mutableStateOf(c?.tp2Percent?.toFloat() ?: 25f) }
    var tp2Close by remember { mutableStateOf(c?.tp2ClosePercent?.toFloat() ?: 30f) }

    // ── DEX-выходы ──
    var timeStop by remember { mutableStateOf((c?.timeStopMinutes ?: 0).toString()) }
    var liqExit by remember { mutableStateOf(c?.liquidityExitDropPercent?.toFloat() ?: 50f) }

    // ── Выходы в R (общие для всех типов) ──
    var rLadder by remember { mutableStateOf(c?.rLadderEnabled ?: false) }
    var tp1R by remember { mutableStateOf(c?.tp1R?.toFloat() ?: 1.5f) }
    var tp2R by remember { mutableStateOf(c?.tp2R?.toFloat() ?: 3f) }
    var beAfterTp1 by remember { mutableStateOf(c?.breakEvenAfterTp1 ?: false) }
    var runner by remember { mutableStateOf(c?.runnerEnabled ?: false) }
    var atrTrail by remember { mutableStateOf(c?.atrTrailEnabled ?: false) }
    var atrTrailMult by remember { mutableStateOf(c?.atrTrailMult?.toFloat() ?: 3f) }
    var atrTrailActivation by remember { mutableStateOf(c?.atrTrailActivationR?.toFloat() ?: 1f) }

    // ── Выходы «по иксам» (общие для всех типов) ──
    var tieredTrail by remember { mutableStateOf(c?.tieredTrailEnabled ?: false) }
    var trailTier1 by remember { mutableStateOf(c?.trailTier1Pct?.toFloat() ?: 40f) }
    var trailTier2 by remember { mutableStateOf(c?.trailTier2Pct?.toFloat() ?: 30f) }
    var trailTier3 by remember { mutableStateOf(c?.trailTier3Pct?.toFloat() ?: 20f) }
    var trailTier4 by remember { mutableStateOf(c?.trailTier4Pct?.toFloat() ?: 15f) }
    var staleExit by remember { mutableStateOf(c?.staleExitEnabled ?: false) }
    var staleExitMinutes by remember { mutableStateOf((c?.staleExitMinutes ?: 1440).toString()) }

    // ── Размер от риска ──
    var riskSizing by remember { mutableStateOf(c?.riskSizingEnabled ?: false) }
    var riskPerTrade by remember { mutableStateOf(c?.riskPerTradePercent?.toFloat() ?: 1f) }
    var maxPoolShare by remember { mutableStateOf(c?.maxPoolSharePercent?.toFloat() ?: 1f) }

    // ── Пробой + откат ──
    var bpHigherTf by remember { mutableStateOf(c?.bpHigherTf ?: "15m") }
    var bpHtfTrend by remember { mutableStateOf(c?.bpRequireHtfTrend ?: true) }
    var bpLookback by remember { mutableStateOf((c?.bpBreakoutLookback ?: 20).toString()) }
    var bpBreakoutVol by remember { mutableStateOf(c?.bpBreakoutVolumeMult?.toFloat() ?: 2f) }
    var bpMaxBars by remember { mutableStateOf((c?.bpMaxBarsSinceBreakout ?: 20).toString()) }
    var bpPullbackVol by remember { mutableStateOf(c?.bpPullbackVolumeRatio?.toFloat() ?: 0.7f) }
    var bpTriggerVol by remember { mutableStateOf(c?.bpTriggerVolumeMult?.toFloat() ?: 1.2f) }

    // ── Взведённый вход («Пробой + откат», «Мем-плейбук») ──
    var armedEntry by remember { mutableStateOf(c?.armedEntryEnabled ?: false) }
    var armedExpiry by remember { mutableStateOf((c?.armedExpiryBars ?: 3).toString()) }

    // ── Мем-плейбук ──
    var mpLookback by remember { mutableStateOf((c?.mpLookbackBars ?: 72).toString()) }
    var mpMinImpulse by remember { mutableStateOf(c?.mpMinImpulsePct?.toFloat() ?: 50f) }
    var mpFibMin by remember { mutableStateOf(c?.mpFibMin?.toFloat() ?: 0.382f) }
    var mpFibMax by remember { mutableStateOf(c?.mpFibMax?.toFloat() ?: 0.786f) }
    var mpPullbackVol by remember { mutableStateOf(c?.mpPullbackVolumeRatio?.toFloat() ?: 0.7f) }
    var mpTriggerVol by remember { mutableStateOf(c?.mpTriggerVolumeMult?.toFloat() ?: 1.2f) }
    var mpLiqToMc by remember { mutableStateOf(c?.mpMinLiqToMcPct?.toFloat() ?: 3f) }
    var mpAuthorities by remember { mutableStateOf(c?.mpRequireRevokedAuthorities ?: true) }
    var mpCopycats by remember { mutableStateOf(c?.mpRejectCopycats ?: true) }

    // ── Волны Эллиотта ──
    var ewHigherTf by remember { mutableStateOf(c?.ewHigherTf ?: "1h") }
    var ewHtf by remember { mutableStateOf(c?.ewRequireHtf ?: false) }
    var ewSwingAtr by remember { mutableStateOf(c?.ewSwingAtr?.toFloat() ?: 2.5f) }
    var ewSubDegree by remember { mutableStateOf(c?.ewSubDegree?.toFloat() ?: 0.382f) }
    var ewStructure by remember { mutableStateOf(c?.ewStructure ?: "SOFT") }
    var ewWave3 by remember { mutableStateOf(c?.ewTradeWave3 ?: true) }
    var ewWave5 by remember { mutableStateOf(c?.ewTradeWave5 ?: true) }
    var ewCorrection by remember { mutableStateOf(c?.ewTradeCorrection ?: false) }
    var ewW2Min by remember { mutableStateOf(c?.ewW2RetraceMin?.toFloat() ?: 0.382f) }
    var ewW2Max by remember { mutableStateOf(c?.ewW2RetraceMax?.toFloat() ?: 0.786f) }
    var ewW4Min by remember { mutableStateOf(c?.ewW4RetraceMin?.toFloat() ?: 0.236f) }
    var ewW4Max by remember { mutableStateOf(c?.ewW4RetraceMax?.toFloat() ?: 0.5f) }
    var ewMinGuidelines by remember { mutableStateOf(c?.ewMinGuidelines ?: 2) }
    var ewWave3Entry by remember { mutableStateOf(c?.ewWave3Entry ?: "BREAK") }
    var ewWindow by remember { mutableStateOf((c?.ewEntryWindowBars ?: 3).toString()) }
    var ewStopAnchor by remember { mutableStateOf(c?.ewStopAnchor ?: "RULE") }
    var ewStopBuffer by remember { mutableStateOf(c?.ewStopBufferAtr?.toFloat() ?: 0.2f) }
    var ewW3Target by remember { mutableStateOf(c?.ewW3Target?.toFloat() ?: 1.618f) }
    var ewW5Target by remember { mutableStateOf(c?.ewW5Target?.toFloat() ?: 1f) }
    var ewMinRr by remember { mutableStateOf(c?.ewMinRewardRisk?.toFloat() ?: 1.5f) }

    // ── Пробой на объёме ──
    var vbLookback by remember { mutableStateOf((c?.vbLookbackBars ?: 60).toString()) }
    var vbMinImpulse by remember { mutableStateOf(c?.vbMinImpulsePct?.toFloat() ?: 20f) }
    var vbMinPullback by remember { mutableStateOf(c?.vbMinPullbackPct?.toFloat() ?: 5f) }
    var vbMaxRetrace by remember { mutableStateOf(c?.vbMaxPullbackRetrace?.toFloat() ?: 0.786f) }
    var vbNearHigh by remember { mutableStateOf(c?.vbNearHighPct?.toFloat() ?: 8f) }
    var vbVolume by remember { mutableStateOf(c?.vbVolumeMult?.toFloat() ?: 2.5f) }
    var vbMaxExtension by remember { mutableStateOf(c?.vbMaxExtensionPct?.toFloat() ?: 15f) }

    // ── Риск ──
    var maxDailyLossEnabled by remember { mutableStateOf(c?.maxDailyLossEnabled ?: true) }
    var maxDailyLoss by remember { mutableStateOf(c?.maxDailyLoss?.toFloat() ?: 5f) }
    var maxDrawdownEnabled by remember { mutableStateOf(c?.maxDrawdownEnabled ?: true) }
    var maxDrawdown by remember { mutableStateOf(c?.maxDrawdown?.toFloat() ?: 15f) }
    var cooldown by remember { mutableStateOf((c?.cooldownSeconds ?: 300).toString()) }

    // ── Фильтры токенов ──
    var minLiq by remember { mutableStateOf((c?.minLiquidityUsd ?: 10_000.0).toLong().toString()) }
    var minMc by remember { mutableStateOf((c?.minMarketCap ?: 50_000.0).toLong().toString()) }
    var maxMc by remember { mutableStateOf((c?.maxMarketCap ?: 10_000_000.0).toLong().toString()) }
    var minAge by remember { mutableStateOf((c?.minTokenAgeMinutes ?: 30L).toString()) }
    var maxAge by remember { mutableStateOf((c?.maxTokenAgeMinutes ?: 43_200L).toString()) }

    // Фильтр по диапазону (общий для всех типов)
    var rangeFilter by remember { mutableStateOf(c?.rangeFilterEnabled ?: false) }
    var rangeMaxEntry by remember { mutableStateOf(c?.rangeMaxEntryPct?.toFloat() ?: 0.8f) }
    var rangeLookback by remember { mutableStateOf((c?.rangeLookbackBars ?: 100).toString()) }

    // Вход по баллу (тип SCORE)
    var scoreThreshold by remember { mutableStateOf((c?.scoreThreshold ?: 50).toFloat()) }
    var scanFiltersEnabled by remember { mutableStateOf(c?.scanFiltersEnabled ?: true) }

    // ── Dars ──
    var darsHigherTf by remember { mutableStateOf(c?.darsHigherTf ?: "15m") }
    var darsSwing by remember { mutableStateOf(c?.darsSwingPivotPct?.toFloat() ?: 1f) }
    var darsHtfTrend by remember { mutableStateOf(c?.darsRequireHtfTrend ?: true) }
    var darsDominance by remember { mutableStateOf(c?.darsDominanceRatio?.toFloat() ?: 1.3f) }
    var darsMinCorr by remember { mutableStateOf(c?.darsMinCorrectionLenPct?.toFloat() ?: 30f) }
    var darsRejectRes by remember { mutableStateOf(c?.darsRejectAtResistance ?: true) }
    var darsResProx by remember { mutableStateOf(c?.darsResistanceProximityPct?.toFloat() ?: 1f) }
    var darsImpCorr by remember { mutableStateOf(c?.darsUseImpulseCorrection ?: true) }
    var darsTrendLevels by remember { mutableStateOf(c?.darsUseTrendLevels ?: true) }
    var darsFalseBreakout by remember { mutableStateOf(c?.darsUseFalseBreakout ?: true) }
    var darsTriangle by remember { mutableStateOf(c?.darsUseTriangle ?: true) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // Заголовок
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isEdit) "Редактировать" else "Новая стратегия",
                fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary
            )
            Surface(
                color = SurfaceCard, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable { onCancel() }
            ) {
                Text("✕ Отмена", color = TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Основное
            FormSection("📝  Основное") {
                FormTextField("Название", name) { name = it }
                Spacer(Modifier.height(4.dp))
                Text("Тип стратегии", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StrategyType.entries.forEach { t ->
                        ChoiceChip(t.displayName.substringBefore(" /").substringBefore(" Trend"), type == t.name) {
                            type = t.name
                            // Новая «Пробой + откат» сразу получает рекомендуемые выходы и ТФ
                            // (STRATEGY_RESEARCH.md §3); при редактировании настройки не трогаем.
                            if (!isEdit && t == StrategyType.BREAKOUT_PULLBACK) {
                                timeframe = "5m"
                                rLadder = true
                                beAfterTp1 = true
                                runner = true
                                atrTrail = true
                                riskSizing = true
                                tp1Close = 40f
                                tp2Close = 30f
                            }
                            // Новый «Мем-плейбук» — выходы из обзора исследований (STRATEGY_RESEARCH.md §8):
                            // половина на 2x, четверть на 3x, остаток под трейлингом «по иксам».
                            if (!isEdit && t == StrategyType.MEME_PLAYBOOK) {
                                timeframe = "5m"
                                stopLoss = 25f
                                takeProfit = 200f
                                partialTpEnabled = true
                                tp1Percent = 100f
                                tp1Close = 50f
                                tp2Percent = 200f
                                tp2Close = 25f
                                beAfterTp1 = true
                                breakEvenOffset = 2f
                                runner = true
                                tieredTrail = true
                                staleExit = true
                                riskSizing = true
                                riskPerTrade = 2f
                                liqExit = 20f
                                maxDrawdown = 50f
                                minAge = "60"
                            }
                            // Новые «Волны Эллиотта» — как в сиде (elliottDefaults): 15m, стоп и цель
                            // от разметки (Stop Loss — потолок), размер от риска.
                            if (!isEdit && t == StrategyType.ELLIOTT) {
                                timeframe = "15m"
                                positionSize = 10f
                                slippage = 2f
                                stopLoss = 25f
                                riskSizing = true
                                riskPerTrade = 1f
                                maxDrawdown = 50f
                                minAge = "720"
                            }
                            // Новый «Пробой на объёме» — как в сиде (volumeBreakoutDefaults): 1m, молодые
                            // монеты, 30% на +2R → безубыток, 30% на +4R, остаток под трейлингом «по иксам».
                            if (!isEdit && t == StrategyType.VOLUME_BREAKOUT) {
                                timeframe = "1m"
                                positionSize = 10f
                                slippage = 2f
                                stopLoss = 15f
                                takeProfit = 300f
                                timeStop = "60"
                                liqExit = 30f
                                rLadder = true
                                tp1R = 2f
                                tp2R = 4f
                                tp1Close = 30f
                                tp2Close = 30f
                                beAfterTp1 = true
                                breakEvenOffset = 2f
                                runner = true
                                tieredTrail = true
                                staleExit = true
                                staleExitMinutes = "120"
                                riskSizing = true
                                riskPerTrade = 1f
                                maxDrawdown = 40f
                                minLiq = "15000"
                                minAge = "20"
                                maxAge = "720"
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Рабочий таймфрейм", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1m", "5m", "15m", "1h").forEach { tf ->
                        ChoiceChip(tf, timeframe == tf) { timeframe = tf }
                    }
                }
            }

            // 2. Размер позиции
            FormSection("📦  Размер позиции") {
                FormSlider(if (riskSizing) "Макс. размер входа" else "Размер входа", positionSize, 1f, 25f,
                    "${"%.1f".format(positionSize)}% от баланса") { positionSize = it }
                FormToggle("Размер от риска", riskSizing) { riskSizing = it }
                if (riskSizing) {
                    FormSlider("Риск на сделку", riskPerTrade, 0.25f, 5f,
                        "${"%.2f".format(riskPerTrade)}% баланса") { riskPerTrade = it }
                    FormSlider("Не больше доли пула", maxPoolShare, 0.25f, 5f,
                        "${"%.2f".format(maxPoolShare)}% ликвидности") { maxPoolShare = it }
                    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Размер = баланс × риск% / расстояние до стопа: при срабатывании стопа теряется " +
                                "одинаковый % баланса, какой бы ни была ширина стопа. Потолок — «Макс. размер входа» " +
                                "и доля ликвидности пула (в тонком пуле крупный вход съедает прибыль на слиппедже).",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
                FormTextField("Макс. одновременных позиций", maxPositions, isNumber = true) { maxPositions = it }
                FormSlider("Slippage", slippage, 0.5f, 5f,
                    "${"%.1f".format(slippage)}%") { slippage = it }
            }

            // 3. Stop Loss
            FormSection("🛡  Stop Loss") {
                FormSlider("Stop Loss", stopLoss, 3f, 50f,
                    "-${"%.0f".format(stopLoss)}%") { stopLoss = it }
                FormToggle("Trailing Stop", trailingEnabled) { trailingEnabled = it }
                if (trailingEnabled) {
                    FormSlider("Активация после прибыли", trailingActivation, 5f, 50f,
                        "+${"%.0f".format(trailingActivation)}%") { trailingActivation = it }
                    FormSlider("Отступ от пика", trailingPercent, 3f, 30f,
                        "${"%.0f".format(trailingPercent)}%") { trailingPercent = it }
                }
                FormToggle("Break-even (SL в безубыток)", breakEvenEnabled) { breakEvenEnabled = it }
                if (breakEvenEnabled) {
                    FormSlider("Триггер (прибыль)", breakEvenTrigger, 3f, 30f,
                        "+${"%.0f".format(breakEvenTrigger)}%") { breakEvenTrigger = it }
                    FormSlider("Отступ от входа", breakEvenOffset, 0f, 5f,
                        "+${"%.1f".format(breakEvenOffset)}%") { breakEvenOffset = it }
                }
            }

            // 4. Take Profit
            FormSection("🎯  Take Profit") {
                FormSlider("Take Profit", takeProfit, 5f, 200f,
                    "+${"%.0f".format(takeProfit)}%") { takeProfit = it }
                FormToggle("Частичная фиксация (TP1/TP2)", partialTpEnabled) { partialTpEnabled = it }
                if (partialTpEnabled) {
                    FormSlider("TP1 — прибыль", tp1Percent, 5f, 200f,
                        "+${"%.0f".format(tp1Percent)}%") { tp1Percent = it }
                    FormSlider("TP1 — закрыть", tp1Close, 10f, 90f,
                        "${"%.0f".format(tp1Close)}% позиции") { tp1Close = it }
                    FormSlider("TP2 — прибыль", tp2Percent, 10f, 500f,
                        "+${"%.0f".format(tp2Percent)}%") { tp2Percent = it }
                    FormSlider("TP2 — закрыть", tp2Close, 10f, 90f,
                        "${"%.0f".format(tp2Close)}% позиции") { tp2Close = it }
                }
            }

            // 4b. Выходы в R (общие для всех типов)
            FormSection("📐  Выходы в R") {
                FormToggle("Лестница TP в R (вместо %)", rLadder) { rLadder = it }
                if (rLadder) {
                    FormSlider("TP1 — цель", tp1R, 0.5f, 5f,
                        "+${"%.1f".format(tp1R)}R") { tp1R = it }
                    FormSlider("TP1 — закрыть", tp1Close, 10f, 90f,
                        "${"%.0f".format(tp1Close)}% позиции") { tp1Close = it }
                    FormSlider("TP2 — цель", tp2R, 1f, 10f,
                        "+${"%.1f".format(tp2R)}R") { tp2R = it }
                    FormSlider("TP2 — закрыть", tp2Close, 10f, 90f,
                        "${"%.0f".format(tp2Close)}% позиции") { tp2Close = it }
                }
                FormToggle("Безубыток после TP1", beAfterTp1) { beAfterTp1 = it }
                FormToggle("Раннер: Take Profit не закрывает позицию", runner) { runner = it }
                FormToggle("ATR-трейлинг (Chandelier)", atrTrail) { atrTrail = it }
                if (atrTrail) {
                    FormSlider("Отступ от пика", atrTrailMult, 1f, 6f,
                        "${"%.1f".format(atrTrailMult)} × ATR") { atrTrailMult = it }
                    FormSlider("Активация после прибыли", atrTrailActivation, 0f, 5f,
                        "+${"%.1f".format(atrTrailActivation)}R") { atrTrailActivation = it }
                }
                Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "R — риск сделки: расстояние от входа до начального стопа. Лестница фиксирует доли " +
                            "на +TP1R и +TP2R (заменяет TP1/TP2 в %); после TP1 тайм-стоп позицию не закрывает. " +
                            "Раннер оставляет остаток работать, пока его не закроет стоп или трейлинг — " +
                            "включайте вместе с трейлингом. ATR-трейлинг держит стоп на N × ATR от пика " +
                            "(ATR — средний размах свечи на входе): шире на волатильных монетах, уже на спокойных.",
                        fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                    )
                }
            }

            // 4c. Выходы «по иксам» (общие для всех типов)
            FormSection("🪜  Выходы по иксам") {
                FormToggle("Ступенчатый трейлинг (уже с ростом иксов)", tieredTrail) { tieredTrail = it }
                if (tieredTrail) {
                    FormSlider("До 2x — отступ от пика", trailTier1, 5f, 60f,
                        "${"%.0f".format(trailTier1)}%") { trailTier1 = it }
                    FormSlider("2x–5x — отступ от пика", trailTier2, 5f, 60f,
                        "${"%.0f".format(trailTier2)}%") { trailTier2 = it }
                    FormSlider("5x–10x — отступ от пика", trailTier3, 5f, 60f,
                        "${"%.0f".format(trailTier3)}%") { trailTier3 = it }
                    FormSlider("От 10x — отступ от пика", trailTier4, 5f, 60f,
                        "${"%.0f".format(trailTier4)}%") { trailTier4 = it }
                }
                FormToggle("Выход, если нет нового максимума", staleExit) { staleExit = it }
                if (staleExit) {
                    FormTextField("Без нового максимума, минут", staleExitMinutes, isNumber = true) { staleExitMinutes = it }
                }
                Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "Ступенчатый трейлинг держит стоп на % от пика, и этот % уменьшается, когда цена " +
                            "растёт в иксы от входа: остаток позиции («мунбэг») может пережить откаты в начале " +
                            "и не отдаёт много прибыли на вершине. «Нет нового максимума»: через 1/6 срока без " +
                            "нового пика трейлинг сужается на 10 п.п., через половину — ещё на 10, по истечении " +
                            "срока позиция закрывается (пампы мемкоинов обычно отыгрываются за 24–48 ч).",
                        fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                    )
                }
            }

            // 5. DEX-защита
            FormSection("⚡  DEX-защита") {
                FormSlider("Ликвидность-стоп (осушение пула)", liqExit, 20f, 90f,
                    "-${"%.0f".format(liqExit)}% ликвидности") { liqExit = it }
                FormTextField("Time-stop, минут (0 = выкл)", timeStop, isNumber = true) { timeStop = it }
                Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "Ликвидность-стоп — экстренный выход при падении ликвидности пула (защита от rug pull). " +
                            "Time-stop принудительно закрывает позицию по времени.",
                        fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                    )
                }
            }

            // 6. Риск
            FormSection("🚨  Риск") {
                // Один общий выключатель гасит оба лимита сразу (дневной убыток + просадку).
                val riskDisabled = !maxDailyLossEnabled && !maxDrawdownEnabled
                FormToggle("Отключить риск-менеджмент", riskDisabled) { disabled ->
                    val enabled = !disabled
                    maxDailyLossEnabled = enabled
                    maxDrawdownEnabled = enabled
                }
                if (riskDisabled) {
                    Surface(color = Red.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "⚠️ Лимиты дневного убытка и просадки отключены — бот не остановит вход " +
                                "даже после серии убыточных сделок. Ограничение открытых позиций и кулдаун " +
                                "продолжают работать.",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                } else {
                    FormSlider("Макс. дневной убыток", maxDailyLoss, 1f, 30f,
                        "${"%.1f".format(maxDailyLoss)}% от баланса") { maxDailyLoss = it }
                    FormSlider("Макс. просадка", maxDrawdown, 5f, 50f,
                        "${"%.0f".format(maxDrawdown)}%") { maxDrawdown = it }
                }
                FormTextField("Кулдаун между сделками, сек", cooldown, isNumber = true) { cooldown = it }
            }

            // 7. Фильтры токенов
            FormSection("🔍  Фильтры токенов (сканер)") {
                FormTextField("Мин. ликвидность, USD", minLiq, isNumber = true) { minLiq = it }
                FormTextField("Мин. Market Cap, USD", minMc, isNumber = true) { minMc = it }
                FormTextField("Макс. Market Cap, USD", maxMc, isNumber = true) { maxMc = it }
                FormTextField("Мин. возраст токена, мин", minAge, isNumber = true) { minAge = it }
                FormTextField("Макс. возраст токена, мин", maxAge, isNumber = true) { maxAge = it }
            }

            // 7b. Фильтр по диапазону (общий для всех типов стратегий)
            FormSection("📊  Фильтр по диапазону") {
                FormToggle("Не покупать у верха диапазона", rangeFilter) { rangeFilter = it }
                if (rangeFilter) {
                    FormSlider("Не входить выше уровня диапазона", rangeMaxEntry, 0.5f, 1.0f,
                        "${"%.0f".format(rangeMaxEntry * 100)}%") { rangeMaxEntry = it }
                    FormTextField("Окно диапазона, свечей", rangeLookback, isNumber = true) { rangeLookback = it }
                    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Позиция входа считается по последним N свечам рабочего ТФ: 0% = минимум окна, " +
                                "100% = максимум. Вход отклоняется, если цена выше порога — чтобы не покупать " +
                                "на локальной вершине. Работает для любой стратегии.",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
            }

            // 7c. Вход по баллу (только для стратегии SCORE)
            if (type == StrategyType.SCORE.name) {
                FormSection("⭐  Вход по баллу") {
                    FormSlider("Минимальный балл для входа", scoreThreshold, 0f, 100f,
                        "≥ ${scoreThreshold.toInt()}") { scoreThreshold = it }
                    FormToggle("Выключить фильтры сканера (ликвидность / MC / возраст)",
                        !scanFiltersEnabled) { scanFiltersEnabled = !it }
                    Surface(color = Amber.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Бот покупает монету, когда её балл (тот же 0–100, что на экране «🔬 Анализ») " +
                                "не ниже порога. Больше балл — чище картинка входа.\n" +
                                "С выключенными фильтрами бот может входить в низколиквидные и рискованные " +
                                "монеты, из которых трудно выйти. На реальном счёте риск выше — сначала проверьте в демо.",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
            }

            // 8. Пробой + откат
            if (type == StrategyType.BREAKOUT_PULLBACK.name) {
                FormSection("🚀  Пробой + откат") {
                    Text("Старший таймфрейм (тренд)", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h", "4h").forEach { tf ->
                            ChoiceChip(tf, bpHigherTf == tf) { bpHigherTf = tf }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FormToggle("Требовать восходящий тренд старшего ТФ", bpHtfTrend) { bpHtfTrend = it }
                    FormTextField("Пробой максимума за N баров", bpLookback, isNumber = true) { bpLookback = it }
                    FormSlider("Объём на пробое", bpBreakoutVol, 1f, 5f,
                        "×${"%.1f".format(bpBreakoutVol)} среднего") { bpBreakoutVol = it }
                    FormTextField("Пробой не старше, баров", bpMaxBars, isNumber = true) { bpMaxBars = it }
                    FormSlider("Объём в откате не выше", bpPullbackVol, 0.3f, 1f,
                        "${"%.0f".format(bpPullbackVol * 100)}% объёма импульса") { bpPullbackVol = it }
                    FormSlider("Объём бара-триггера", bpTriggerVol, 1f, 3f,
                        "×${"%.1f".format(bpTriggerVol)} объёма отката") { bpTriggerVol = it }
                    ArmedEntryFields(armedEntry, armedExpiry, { armedEntry = it }, { armedExpiry = it })
                    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Бот не покупает сам пробой: он ждёт откат к уровню пробоя на затухающем объёме " +
                                "и входит на баре разворота вверх. Стоп — под минимумом отката. Если стоп дальше " +
                                "Stop Loss из настроек или до пика импульса меньше 1R — вход пропускается. " +
                                "Монете нужна история: на 5m — от ~3 часов.",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
            }

            // 8b. Мем-плейбук
            if (type == StrategyType.MEME_PLAYBOOK.name) {
                FormSection("🐸  Мем-плейбук") {
                    FormSlider("Импульс не меньше", mpMinImpulse, 20f, 300f,
                        "+${"%.0f".format(mpMinImpulse)}%") { mpMinImpulse = it }
                    FormTextField("Окно поиска импульса, баров", mpLookback, isNumber = true) { mpLookback = it }
                    FormSlider("Откат не мельче", mpFibMin, 0.2f, 0.618f,
                        "${"%.3f".format(mpFibMin)} импульса") { mpFibMin = it }
                    FormSlider("Откат глубже — пустой памп", mpFibMax, 0.5f, 0.9f,
                        "${"%.3f".format(mpFibMax)} импульса") { mpFibMax = it }
                    FormSlider("Объём в откате не выше", mpPullbackVol, 0.3f, 1f,
                        "${"%.0f".format(mpPullbackVol * 100)}% объёма импульса") { mpPullbackVol = it }
                    FormSlider("Объём бара-триггера", mpTriggerVol, 1f, 3f,
                        "×${"%.1f".format(mpTriggerVol)} объёма отката") { mpTriggerVol = it }
                    ArmedEntryFields(armedEntry, armedExpiry, { armedEntry = it }, { armedExpiry = it })
                    FormSlider("Ликвидность не меньше", mpLiqToMc, 0f, 20f,
                        "${"%.1f".format(mpLiqToMc)}% капитализации") { mpLiqToMc = it }
                    FormToggle("Только с отозванными mint/freeze authority", mpAuthorities) { mpAuthorities = it }
                    FormToggle("Пропускать копии (тикер уже был у более старой монеты)", mpCopycats) { mpCopycats = it }
                    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Бот ждёт импульс, затем откат в зону 0.382–0.786 по Фибоначчи на затухающем объёме " +
                                "и входит на баре разворота вверх. Откат глубже 0.786 считается пустым пампом. " +
                                "Стоп — под минимумом отката, но не глубже 0.786. Если стоп дальше Stop Loss из настроек или до " +
                                "максимума импульса меньше 1R — вход пропускается.\n" +
                                "Перед входом бот проверяет mint-аккаунт через RPC и ищет на DexScreener более " +
                                "старые монеты с тем же тикером (копии выходят на DEX в ~10 раз реже оригиналов). " +
                                "Нет ответа от сети — нет входа. Выходы — в разделах «Take Profit» и «Выходы по иксам».",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
            }

            // 8c. Волны Эллиотта
            if (type == StrategyType.ELLIOTT.name) {
                FormSection("🌊  Волны Эллиотта") {
                    FormSlider("Размер волны", ewSwingAtr, 1f, 6f,
                        "${"%.1f".format(ewSwingAtr)} × ATR") { ewSwingAtr = it }
                    FormSlider("Младшая степень", ewSubDegree, 0.2f, 0.618f,
                        "${"%.3f".format(ewSubDegree)} размера волны") { ewSubDegree = it }
                    Text("Строение волн", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("STRICT" to "Строго по книге", "SOFT" to "Не делится — допускаем", "OFF" to "Не проверять")
                            .forEach { (k, label) -> ChoiceChip(label, ewStructure == k) { ewStructure = k } }
                    }
                    Spacer(Modifier.height(8.dp))
                    FormToggle("Вход в волну 3", ewWave3) { ewWave3 = it }
                    FormToggle("Вход в волну 5", ewWave5) { ewWave5 = it }
                    FormToggle("Отскок после нисходящей пятёрки", ewCorrection) { ewCorrection = it }
                    if (!ewWave3 && !ewWave5 && !ewCorrection) {
                        Surface(color = Red.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "⚠️ Ни один сетап не включён — стратегия не войдёт ни разу.",
                                fontSize = 11.sp, color = TextSecondary,
                                modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Вход в волну 3", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("BREAK" to "На пробое конца 1-й", "TURN" to "На конце 2-й")
                            .forEach { (k, label) -> ChoiceChip(label, ewWave3Entry == k) { ewWave3Entry = k } }
                    }
                    Spacer(Modifier.height(8.dp))
                    FormTextField("Окно входа, баров после повода", ewWindow, isNumber = true) { ewWindow = it }
                    Spacer(Modifier.height(4.dp))
                    Text("Стоп", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("RULE" to "Где ломается счёт", "PIVOT" to "За конец коррекции")
                            .forEach { (k, label) -> ChoiceChip(label, ewStopAnchor == k) { ewStopAnchor = k } }
                    }
                    Spacer(Modifier.height(4.dp))
                    FormSlider("Буфер стопа", ewStopBuffer, 0f, 1f,
                        "${"%.2f".format(ewStopBuffer)} × ATR") { ewStopBuffer = it }
                    FormSlider("Цель 3-й", ewW3Target, 1f, 2.618f,
                        "${"%.3f".format(ewW3Target)} × волны 1") { ewW3Target = it }
                    FormSlider("Цель 5-й", ewW5Target, 0.5f, 1.618f,
                        "${"%.3f".format(ewW5Target)} × волны 1") { ewW5Target = it }
                    FormSlider("R:R не меньше", ewMinRr, 1f, 4f,
                        "${"%.1f".format(ewMinRr)}") { ewMinRr = it }
                    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Бот размечает волны по разворотам (отход на «размер волны» × ATR), проверяет " +
                                "правила книги — волна 2 не за началом 1-й, 3-я дальше конца 1-й и не самая " +
                                "короткая, 4-я не заходит на 1-ю — и строение волн (5 по тренду, 3 против). " +
                                "Вход в 3-ю — на закрытии за концом волны 1, в 5-ю — когда подтвердился конец 4-й. " +
                                "Разметка — только по закрытым свечам.\n" +
                                "Стоп и цель берутся из разметки, а не из процентов: Stop Loss выше — только потолок " +
                                "(стоп по правилу дальше — вход пропускается), Take Profit в % не используется. " +
                                "Монете нужно ≥ 44 закрытых свечей (на 15m — 11 часов).",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
                FormSection("📏  Эллиотт — ориентиры и старшая степень") {
                    FormSlider("Откат 2-й — от", ewW2Min, 0.1f, 0.7f,
                        "${"%.3f".format(ewW2Min)} волны 1") { ewW2Min = it }
                    FormSlider("Откат 2-й — до", ewW2Max, 0.5f, 0.99f,
                        "${"%.3f".format(ewW2Max)} волны 1") { ewW2Max = it }
                    FormSlider("Откат 4-й — от", ewW4Min, 0.1f, 0.5f,
                        "${"%.3f".format(ewW4Min)} волны 3") { ewW4Min = it }
                    FormSlider("Откат 4-й — до", ewW4Max, 0.3f, 0.8f,
                        "${"%.3f".format(ewW4Max)} волны 3") { ewW4Max = it }
                    Text("Ориентиров не меньше", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..4).forEach { n -> ChoiceChip("$n", ewMinGuidelines == n) { ewMinGuidelines = n } }
                    }
                    Spacer(Modifier.height(8.dp))
                    FormToggle("Проверять старшую степень", ewHtf) { ewHtf = it }
                    if (ewHtf) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("15m", "1h", "4h").forEach { tf ->
                                ChoiceChip(tf, ewHigherTf == tf) { ewHigherTf = tf }
                            }
                        }
                    }
                    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Правила книги абсолютны, ориентиры — вероятности: глубина отката по Фибоначчи, " +
                                "объём (2-я тише 1-й, 3-я громче), чередование 2-й и 4-й, канал, зона 4-й волны " +
                                "младшей степени. Старшая степень: текущая волна старшего ТФ должна идти вверх; " +
                                "её свечи запрашиваются только для готового сетапа, нет ответа — нет входа.",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
            }

            // 8d. Пробой на объёме
            if (type == StrategyType.VOLUME_BREAKOUT.name) {
                FormSection("📈  Пробой на объёме") {
                    FormTextField("Окно поиска максимума, баров", vbLookback, isNumber = true) { vbLookback = it }
                    FormSlider("Импульс к максимуму не меньше", vbMinImpulse, 5f, 200f,
                        "+${"%.0f".format(vbMinImpulse)}%") { vbMinImpulse = it }
                    FormSlider("Первый откат не мельче", vbMinPullback, 1f, 30f,
                        "−${"%.0f".format(vbMinPullback)}% от максимума") { vbMinPullback = it }
                    FormSlider("Откат не глубже", vbMaxRetrace, 0.382f, 0.95f,
                        "${"%.3f".format(vbMaxRetrace)} импульса") { vbMaxRetrace = it }
                    FormSlider("Ретест: закрытия под максимумом не ниже", vbNearHigh, 1f, 20f,
                        "−${"%.0f".format(vbNearHigh)}%") { vbNearHigh = it }
                    FormSlider("Объём бара пробоя", vbVolume, 1.5f, 10f,
                        "×${"%.1f".format(vbVolume)} среднего") { vbVolume = it }
                    FormSlider("Не догонять выше максимума на", vbMaxExtension, 3f, 50f,
                        "+${"%.0f".format(vbMaxExtension)}%") { vbMaxExtension = it }
                    Surface(color = Blue.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Сетап: импульс к максимуму → первый откат (минимум выше начала импульса) → цена " +
                                "снова у максимума → закрытый бар пробивает максимум на всплеске объёма. Вход — " +
                                "на самом пробое, по закрытию бара (формирующийся бар не считается). Цена, уже " +
                                "ушедшая выше максимума больше заданного, не догоняется. Стоп — под базой из трёх " +
                                "баров перед пробоем; если он дальше Stop Loss из настроек — вход пропускается.\n" +
                                "Выход важнее входа: после пробоя пампы часто полностью откатываются — держите " +
                                "частичные фиксации и трейлинг «по иксам». Монете нужно ≥ 16 закрытых баров " +
                                "(ATR-период + 2).",
                            fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                        )
                    }
                }
            }

            // 9. Dars
            if (type == StrategyType.DARS.name) {
                FormSection("🧠  Dars / Smart Money") {
                    Text("Старший таймфрейм (тренд)", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h", "4h").forEach { tf ->
                            ChoiceChip(tf, darsHigherTf == tf) { darsHigherTf = tf }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FormToggle("Требовать восходящий тренд старшего ТФ", darsHtfTrend) { darsHtfTrend = it }
                    FormSlider("Порог свинг-пивота", darsSwing, 0.3f, 5f,
                        "${"%.1f".format(darsSwing)}%") { darsSwing = it }
                    FormSlider("Доминирование импульса", darsDominance, 1f, 3f,
                        "×${"%.1f".format(darsDominance)}") { darsDominance = it }
                    FormSlider("Мин. длина коррекции", darsMinCorr, 10f, 90f,
                        "${"%.0f".format(darsMinCorr)}% импульса") { darsMinCorr = it }
                    FormToggle("Не покупать у сопротивления", darsRejectRes) { darsRejectRes = it }
                    if (darsRejectRes) {
                        FormSlider("Зона «у сопротивления»", darsResProx, 0.3f, 5f,
                            "${"%.1f".format(darsResProx)}%") { darsResProx = it }
                    }
                }
                FormSection("🧩  Dars — сетапы") {
                    FormToggle("Импульс / коррекция (Урок 1)", darsImpCorr) { darsImpCorr = it }
                    FormToggle("Тренд + уровни (Урок 2)", darsTrendLevels) { darsTrendLevels = it }
                    FormToggle("Ложный пробой (Урок 3)", darsFalseBreakout) { darsFalseBreakout = it }
                    FormToggle("Треугольник (Урок 5)", darsTriangle) { darsTriangle = it }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                val id = c?.id ?: "strategy-${Clock.System.now().toEpochMilliseconds()}"
                onSave(StrategyConfig(
                    id = id,
                    name = name.trim().ifEmpty { type.toTypeLabel() },
                    type = type,
                    isActive = c?.isActive ?: false,
                    timeframe = timeframe,
                    positionSize = positionSize.toDouble(),
                    maxPositions = maxPositions.toIntOrNull() ?: 1,
                    slippagePercent = slippage.toDouble(),
                    priorityFeeLamports = c?.priorityFeeLamports ?: 0,
                    stopLossPercent = stopLoss.toDouble(),
                    takeProfitPercent = takeProfit.toDouble(),
                    trailingStopEnabled = trailingEnabled,
                    trailingStopPercent = trailingPercent.toDouble(),
                    trailingActivationPercent = trailingActivation.toDouble(),
                    breakEvenEnabled = breakEvenEnabled,
                    breakEvenTriggerPercent = breakEvenTrigger.toDouble(),
                    breakEvenOffsetPercent = breakEvenOffset.toDouble(),
                    partialTpEnabled = partialTpEnabled,
                    tp1Percent = tp1Percent.toDouble(),
                    tp1ClosePercent = tp1Close.toDouble(),
                    tp2Percent = tp2Percent.toDouble(),
                    tp2ClosePercent = tp2Close.toDouble(),
                    timeStopMinutes = timeStop.toIntOrNull() ?: 0,
                    liquidityExitDropPercent = liqExit.toDouble(),
                    rLadderEnabled = rLadder,
                    tp1R = tp1R.toDouble(),
                    tp2R = tp2R.toDouble(),
                    breakEvenAfterTp1 = beAfterTp1,
                    runnerEnabled = runner,
                    atrTrailEnabled = atrTrail,
                    atrTrailMult = atrTrailMult.toDouble(),
                    atrTrailActivationR = atrTrailActivation.toDouble(),
                    tieredTrailEnabled = tieredTrail,
                    trailTier1Pct = trailTier1.toDouble(),
                    trailTier2Pct = trailTier2.toDouble(),
                    trailTier3Pct = trailTier3.toDouble(),
                    trailTier4Pct = trailTier4.toDouble(),
                    staleExitEnabled = staleExit,
                    staleExitMinutes = staleExitMinutes.toIntOrNull()?.coerceAtLeast(10) ?: 1440,
                    riskSizingEnabled = riskSizing,
                    riskPerTradePercent = riskPerTrade.toDouble(),
                    maxPoolSharePercent = maxPoolShare.toDouble(),
                    maxDailyLossEnabled = maxDailyLossEnabled,
                    maxDailyLoss = maxDailyLoss.toDouble(),
                    maxDrawdownEnabled = maxDrawdownEnabled,
                    maxDrawdown = maxDrawdown.toDouble(),
                    cooldownSeconds = cooldown.toIntOrNull() ?: 300,
                    minLiquidityUsd = minLiq.toDoubleOrNull() ?: 10_000.0,
                    minMarketCap = minMc.toDoubleOrNull() ?: 50_000.0,
                    maxMarketCap = maxMc.toDoubleOrNull() ?: 10_000_000.0,
                    minTokenAgeMinutes = minAge.toLongOrNull() ?: 30L,
                    maxTokenAgeMinutes = maxAge.toLongOrNull() ?: 43_200L,
                    rangeFilterEnabled = rangeFilter,
                    rangeMaxEntryPct = rangeMaxEntry.toDouble(),
                    rangeLookbackBars = rangeLookback.toIntOrNull() ?: 100,
                    scoreThreshold = scoreThreshold.toInt(),
                    scanFiltersEnabled = scanFiltersEnabled,
                    atrPeriod = c?.atrPeriod ?: 14,
                    darsHigherTf = darsHigherTf,
                    darsCandleLimit = c?.darsCandleLimit ?: 200,
                    darsSwingPivotPct = darsSwing.toDouble(),
                    darsRequireHtfTrend = darsHtfTrend,
                    darsDominanceRatio = darsDominance.toDouble(),
                    darsMinCorrectionLenPct = darsMinCorr.toDouble(),
                    darsRejectAtResistance = darsRejectRes,
                    darsResistanceProximityPct = darsResProx.toDouble(),
                    darsMinLegs = c?.darsMinLegs ?: 2,
                    darsUseImpulseCorrection = darsImpCorr,
                    darsUseTrendLevels = darsTrendLevels,
                    darsUseFalseBreakout = darsFalseBreakout,
                    darsUseTriangle = darsTriangle,
                    bpHigherTf = bpHigherTf,
                    bpRequireHtfTrend = bpHtfTrend,
                    bpBreakoutLookback = bpLookback.toIntOrNull()?.coerceAtLeast(5) ?: 20,
                    bpBreakoutVolumeMult = bpBreakoutVol.toDouble(),
                    bpMaxBarsSinceBreakout = bpMaxBars.toIntOrNull()?.coerceAtLeast(3) ?: 20,
                    bpPullbackVolumeRatio = bpPullbackVol.toDouble(),
                    bpTriggerVolumeMult = bpTriggerVol.toDouble(),
                    mpLookbackBars = mpLookback.toIntOrNull()?.coerceAtLeast(10) ?: 72,
                    mpMinImpulsePct = mpMinImpulse.toDouble(),
                    mpFibMin = mpFibMin.toDouble(),
                    // Граница «пустого пампа» всегда глубже нижней границы зоны входа.
                    mpFibMax = maxOf(mpFibMax, mpFibMin + 0.05f).toDouble(),
                    mpPullbackVolumeRatio = mpPullbackVol.toDouble(),
                    mpTriggerVolumeMult = mpTriggerVol.toDouble(),
                    mpMinLiqToMcPct = mpLiqToMc.toDouble(),
                    mpRequireRevokedAuthorities = mpAuthorities,
                    mpRejectCopycats = mpCopycats,
                    ewHigherTf = ewHigherTf,
                    ewRequireHtf = ewHtf,
                    ewSwingAtr = ewSwingAtr.toDouble(),
                    ewSubDegree = ewSubDegree.toDouble(),
                    ewStructure = ewStructure,
                    ewTradeWave3 = ewWave3,
                    ewTradeWave5 = ewWave5,
                    ewTradeCorrection = ewCorrection,
                    // Верхняя граница нормы отката всегда выше нижней.
                    ewW2RetraceMin = ewW2Min.toDouble(),
                    ewW2RetraceMax = maxOf(ewW2Max, ewW2Min + 0.05f).toDouble(),
                    ewW4RetraceMin = ewW4Min.toDouble(),
                    ewW4RetraceMax = maxOf(ewW4Max, ewW4Min + 0.05f).toDouble(),
                    ewMinGuidelines = ewMinGuidelines,
                    ewWave3Entry = ewWave3Entry,
                    ewEntryWindowBars = ewWindow.toIntOrNull()?.coerceIn(1, 10) ?: 3,
                    ewStopAnchor = ewStopAnchor,
                    ewStopBufferAtr = ewStopBuffer.toDouble(),
                    ewW3Target = ewW3Target.toDouble(),
                    ewW5Target = ewW5Target.toDouble(),
                    ewMinRewardRisk = ewMinRr.toDouble(),
                    vbLookbackBars = vbLookback.toIntOrNull()?.coerceAtLeast(10) ?: 60,
                    vbMinImpulsePct = vbMinImpulse.toDouble(),
                    vbMinPullbackPct = vbMinPullback.toDouble(),
                    vbMaxPullbackRetrace = vbMaxRetrace.toDouble(),
                    vbNearHighPct = vbNearHigh.toDouble(),
                    vbVolumeMult = vbVolume.toDouble(),
                    vbMaxExtensionPct = vbMaxExtension.toDouble(),
                    armedEntryEnabled = armedEntry,
                    armedExpiryBars = armedExpiry.toIntOrNull()?.coerceIn(1, 50) ?: 3,
                ))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            Text(
                if (isEdit) "💾  Сохранить изменения" else "✅  Создать стратегию",
                fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 16.sp
            )
        }
    }
}

/**
 * «🎯 Вход по касанию уровня» в форме «Пробоя + отката» и «Мем-плейбука»: всё сошлось, кроме бара-триггера,
 * — бот следит за ценой и входит, как только она пройдёт максимум прошлого бара (см. StrategyConfig.armedEntryEnabled).
 */
@Composable
private fun ArmedEntryFields(enabled: Boolean, expiry: String, onEnabled: (Boolean) -> Unit, onExpiry: (String) -> Unit) {
    FormToggle("🎯 Вход по касанию уровня (взвод)", enabled, onEnabled)
    if (enabled) {
        FormTextField("Взвод живёт, баров", expiry, isNumber = true, onChange = onExpiry)
        Text(
            "Когда не хватает только бара-триггера, бот взводит сетап и проверяет цену каждые 5 с: прошла максимум " +
                "прошлого бара (не выше чем на 3%) — вход сразу, а не через скан после закрытия бара. Цена у точки " +
                "слома сетапа или срок вышел — взвод снимается. Сила закрытия и объём триггера при этом не " +
                "проверяются: бар ещё не закрыт.",
            fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
        )
    }
}

// ─── Поля форм (форма стратегии, бэктест, ручная покупка) ─────────────────────

@Composable
fun FormSection(title: String, content: @Composable () -> Unit) {
    Surface(color = SurfaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun FormTextField(label: String, value: String, isNumber: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (isNumber) v.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' } else v) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        keyboardOptions = if (isNumber) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    )
}

@Composable
fun FormToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingSwitch(label, checked, onChange = onChange)
}

@Composable
fun FormSlider(label: String, value: Float, min: Float, max: Float, valueLabel: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = TextSecondary)
            Text(valueLabel, fontSize = 13.sp, color = Green, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = BorderColor)
        )
    }
}

/** Выбор одного из вариантов (тип стратегии, таймфрейм, период бэктеста). */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Blue.copy(alpha = 0.18f) else BgDark,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .border(1.dp, if (selected) Blue else BorderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) Blue else TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}
