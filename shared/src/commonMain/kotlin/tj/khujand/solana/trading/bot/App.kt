package tj.khujand.solana.trading.bot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import tj.khujand.solana.trading.bot.core.engine.ActivityEvent
import tj.khujand.solana.trading.bot.core.engine.ActivityLevel
import tj.khujand.solana.trading.bot.core.engine.ActivityStats
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.data.AccountBalance
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.OpenPosition
import tj.khujand.solana.trading.bot.data.db.DrxDatabase
import tj.khujand.solana.trading.bot.telegram.TelegramBotController
import tj.khujand.solana.trading.bot.util.formatDexTime
import tj.khujand.solana.trading.bot.util.formatLocalTime

// ─── Палитра (1-в-1 из MRX) ───────────────────────────────────────────────────

private val BgDark        = Color(0xFF0A0E1A)
private val SurfaceCard   = Color(0xFF1A2235)
private val Green         = Color(0xFF00D97E)
private val GreenDim      = Color(0xFF00D97E).copy(alpha = 0.15f)
private val Red           = Color(0xFFFF4757)
private val RedDim        = Color(0xFFFF4757).copy(alpha = 0.15f)
private val Blue          = Color(0xFF4E9EFF)
private val Purple        = Color(0xFF9B7AFF)
private val Amber         = Color(0xFFF59E0B)
private val TextPrimary   = Color(0xFFEAF0FB)
private val TextSecondary = Color(0xFF6B7A99)
private val BorderColor   = Color(0xFF1E2D45)

enum class Screen { DASHBOARD, HISTORY, STATS, STRATEGIES, SETTINGS }

// ─── Root ─────────────────────────────────────────────────────────────────────

@Composable
fun App(runtime: DrxRuntime? = null) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            background = BgDark,
            surface = SurfaceCard,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        )
    ) {
        DrxApp(runtime)
    }
}

@Composable
fun DrxApp(runtime: DrxRuntime? = null) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    val strategies = remember { mutableStateListOf<StrategyConfig>() }
    var editingStrategy by remember { mutableStateOf<StrategyConfig?>(null) }
    var showForm by remember { mutableStateOf(false) }

    val db = runtime?.db
    val settingsStore = runtime?.settingsStore
    val engine = runtime?.engine

    // Настройки из БД (кошелёк, Telegram, RPC)
    var settings by remember {
        mutableStateOf(
            settingsStore?.let { s ->
                DrxSettings(
                    rpcUrl = s.getRpcUrl() ?: "https://api.mainnet-beta.solana.com",
                    walletSeed = s.getWalletSeed() ?: "",
                    telegramToken = s.getTelegramToken() ?: "",
                    telegramChatId = s.getTelegramChatId()?.toString() ?: ""
                )
            } ?: DrxSettings()
        )
    }

    // Режим DEMO/REAL — применяется сразу (исполнитель читает флаг из БД на каждой сделке).
    var demoMode by remember { mutableStateOf(settingsStore?.getDemoMode() ?: true) }

    // Режим «только сигнал» — общий StateFlow с Telegram.
    val fallbackSignalOnly = remember { MutableStateFlow(false) }
    val signalOnly by (runtime?.strategyManager?.signalOnly ?: fallbackSignalOnly).collectAsState()

    // Telegram-контроллер: слушает команды. Пересоздаётся при смене токена/чата.
    val telegramController = remember(runtime, settings.telegramToken, settings.telegramChatId) {
        val rt = runtime
        val token = settings.telegramToken
        val chatId = settings.telegramChatId.toLongOrNull() ?: 0L
        if (rt != null && token.isNotBlank() && chatId != 0L) {
            TelegramBotController(
                botToken = token,
                allowedChatId = chatId,
                engine = rt.engine,
                strategyManager = rt.strategyManager,
                executor = rt.executor,
                db = rt.db,
                tokenCache = rt.tokenCache,
                settingsStore = rt.settingsStore,
            )
        } else null
    }

    LaunchedEffect(telegramController) {
        telegramController?.let {
            runtime?.activityLog?.info("📲 Telegram подключён — слушаю команды")
            it.startPolling()
        }
    }

    DisposableEffect(telegramController) {
        onDispose { telegramController?.close() }
    }

    // Статус бота из движка
    val fallbackStatus = remember { MutableStateFlow(BotStatus.STOPPED) }
    val botStatus by (engine?.status ?: fallbackStatus).collectAsState()

    // Живая лента активности
    val activityFallback = remember { MutableStateFlow<List<ActivityEvent>>(emptyList()) }
    val activity by (runtime?.activityLog?.events ?: activityFallback).collectAsState()

    // Пульс сессии для шапки
    val statsFallback = remember { MutableStateFlow(ActivityStats()) }
    val activityStats by (runtime?.activityLog?.stats ?: statsFallback).collectAsState()

    var balance by remember { mutableStateOf<AccountBalance?>(null) }

    LaunchedEffect(engine, demoMode) {
        val e = engine ?: return@LaunchedEffect
        while (true) {
            balance = runCatching { e.getBalance() }.getOrNull()
            delay(30_000)
        }
    }

    // «Живые» открытые позиции с текущим PnL — для секции на дашборде и ручного закрытия.
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

    // Закрытые сделки — перечитываются при смене экрана (TradeMonitor закрывает их в БД).
    val closedTrades = remember(screen) { loadClosedTrades(db) }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Box(modifier = Modifier.weight(1f)) {
            if (showForm) {
                StrategyFormScreen(
                    initial = editingStrategy,
                    onSave = { cfg ->
                        runtime?.strategyStore?.save(cfg)
                        reload()
                        showForm = false
                        editingStrategy = null
                    },
                    onCancel = { showForm = false; editingStrategy = null }
                )
            } else {
                when (screen) {
                    Screen.DASHBOARD -> DashboardScreen(
                        botStatus = botStatus,
                        strategies = strategies,
                        balance = balance,
                        connected = runtime != null,
                        activity = activity,
                        stats = activityStats,
                        trades = closedTrades,
                        openPositions = openPositions,
                        onClosePosition = { tradeId ->
                            scope.launch {
                                engine?.closePosition(tradeId)
                                refreshPositions() // сразу обновляем список после закрытия
                            }
                        }
                    ) { target ->
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
                    Screen.HISTORY -> HistoryScreen(closedTrades)
                    Screen.STATS -> StatsScreen(closedTrades)
                    Screen.STRATEGIES -> StrategiesScreen(
                        strategies = strategies,
                        onCreate = { showForm = true; editingStrategy = null },
                        onEdit = { cfg -> editingStrategy = cfg; showForm = true },
                        onDelete = { cfg ->
                            runtime?.strategyStore?.delete(cfg.id)
                            reload()
                        },
                        onToggle = { cfg ->
                            runtime?.strategyStore?.setActive(cfg.id, !cfg.isActive)
                            scope.launch { engine?.applyStrategyActive(cfg.id, !cfg.isActive) }
                            reload()
                        }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        connected = runtime != null,
                        demoMode = demoMode,
                        demoBalance = balance?.demoUsd ?: 10_000.0,
                        signalOnly = signalOnly,
                        onDemoModeChange = { v ->
                            // REAL требует seed-фразы — без неё исполнитель всё равно не откроет сделку.
                            demoMode = v
                            settingsStore?.setDemoMode(v)
                        },
                        onSignalOnlyChange = { v ->
                            val sm = runtime?.strategyManager
                            if (sm != null) sm.setSignalOnly(v) else fallbackSignalOnly.value = v
                        },
                        onResetDemo = { runtime?.executor?.resetDemoBalance() },
                        onResetBot = { runtime?.resetBotData() },
                        onSave = { newSettings ->
                            settingsStore?.apply {
                                setRpcUrl(newSettings.rpcUrl.trim())
                                if (newSettings.walletSeed.isNotBlank()) setWalletSeed(newSettings.walletSeed.trim())
                                setTelegramToken(newSettings.telegramToken.trim())
                                setTelegramChatId(newSettings.telegramChatId.trim().toLongOrNull() ?: 0L)
                            }
                            // Новый токен/чат применяем к живому notifier движка сразу.
                            runtime?.notifier?.updateCredentials(
                                newSettings.telegramToken.trim(),
                                newSettings.telegramChatId.trim().toLongOrNull() ?: 0L
                            )
                            settings = newSettings
                        }
                    )
                }
            }
        }
        if (!showForm) BottomNav(screen) { screen = it }
    }
}

data class DrxSettings(
    val rpcUrl: String = "https://api.mainnet-beta.solana.com",
    val walletSeed: String = "",
    val telegramToken: String = "",
    val telegramChatId: String = "",
)

// ─── Bottom nav ───────────────────────────────────────────────────────────────

@Composable
fun BottomNav(current: Screen, onSelect: (Screen) -> Unit) {
    Surface(color = SurfaceCard, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            NavItem("📊", "Главная",   current == Screen.DASHBOARD)  { onSelect(Screen.DASHBOARD) }
            NavItem("📋", "История",   current == Screen.HISTORY)    { onSelect(Screen.HISTORY) }
            NavItem("📈", "Аналитика", current == Screen.STATS)      { onSelect(Screen.STATS) }
            NavItem("🤖", "Стратегии", current == Screen.STRATEGIES) { onSelect(Screen.STRATEGIES) }
            NavItem("⚙️", "Настройки", current == Screen.SETTINGS)   { onSelect(Screen.SETTINGS) }
        }
    }
}

@Composable
fun NavItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Green else TextSecondary
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = color,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(4.dp).clip(CircleShape).background(Green))
        }
    }
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    botStatus: BotStatus,
    strategies: List<StrategyConfig>,
    balance: AccountBalance? = null,
    connected: Boolean = false,
    activity: List<ActivityEvent> = emptyList(),
    stats: ActivityStats = ActivityStats(),
    trades: List<TradeHistoryItem> = emptyList(),
    openPositions: List<OpenPosition> = emptyList(),
    onClosePosition: (String) -> Unit = {},
    onStatusChange: (BotStatus) -> Unit
) {
    val pnlData = equityCurve(trades)
    val recentTrades = trades.take(3)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item { DashboardHeader(botStatus, stats, connected) }

        item {
            Spacer(Modifier.height(12.dp))
            BalanceCard(balance, connected)
        }

        item {
            Spacer(Modifier.height(12.dp))
            PnlChartCard(pnlData, trades)
        }

        item {
            Spacer(Modifier.height(12.dp))
            StatsRow(trades)
        }

        item {
            Spacer(Modifier.height(12.dp))
            ControlButtons(botStatus, onStatusChange)
        }

        item {
            Spacer(Modifier.height(12.dp))
            ActivityFeedCard(activity, botStatus == BotStatus.RUNNING)
        }

        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Стратегии", "${strategies.count { it.isActive }} активных")
        }

        if (strategies.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                EmptyState("Стратегии не настроены")
            }
        } else {
            items(strategies) { cfg ->
                Spacer(Modifier.height(6.dp))
                DashStrategyCard(cfg, trades)
            }
        }

        if (openPositions.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Открытые позиции", "${openPositions.size} шт.")
            }
            items(openPositions, key = { it.tradeId }) { p ->
                Spacer(Modifier.height(6.dp))
                PositionCard(p, onClose = { onClosePosition(p.tradeId) })
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Последние сделки", "История →")
        }

        if (recentTrades.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                EmptyState("Сделок ещё нет")
            }
        } else {
            items(recentTrades) { t ->
                Spacer(Modifier.height(6.dp))
                TradeCard(t)
            }
        }
    }
}

@Composable
fun DashboardHeader(
    status: BotStatus,
    stats: ActivityStats = ActivityStats(),
    connected: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("DRX Bot", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            PulseLine(stats, connected)
        }
        StatusChip(status)
    }
}

/** Пульс сессии: точка + счётчик запросов + время последнего успешного ответа DEX-API. */
@Composable
private fun PulseLine(stats: ActivityStats, connected: Boolean) {
    val lastOk = stats.lastOkAt
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape)
                .background(if (lastOk != null) Green else TextSecondary)
        )
        Spacer(Modifier.width(6.dp))
        when {
            !connected -> Text("Рантайм не подключен", fontSize = 12.sp, color = TextSecondary)
            lastOk != null -> Text(
                "${stats.okCount} запр. · ответ ${formatClock(lastOk)}",
                fontSize = 12.sp, color = TextSecondary
            )
            else -> Text("ожидание ответа DEX…", fontSize = 12.sp, color = TextSecondary)
        }
        if (stats.errorCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text("· ${stats.errorCount} ош.", fontSize = 12.sp, color = Red, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun StatusChip(status: BotStatus) {
    val (bg, fg, label) = when (status) {
        BotStatus.RUNNING -> Triple(GreenDim,                  Green, "● RUNNING")
        BotStatus.STOPPED -> Triple(RedDim,                    Red,   "● STOPPED")
        BotStatus.PAUSED  -> Triple(Amber.copy(alpha = 0.15f), Amber, "⏸ PAUSED")
    }
    Surface(color = bg, shape = RoundedCornerShape(20.dp)) {
        Text(label, color = fg, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 13.sp)
    }
}

// ─── Balance card ─────────────────────────────────────────────────────────────

@Composable
fun BalanceCard(balance: AccountBalance?, connected: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = SurfaceCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    if (balance?.isDemo != false) "💰 Баланс (DEMO)" else "💰 Баланс кошелька",
                    fontSize = 12.sp, color = TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                when {
                    !connected -> Text(
                        "Рантайм не подключен",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Amber
                    )
                    balance == null -> Text(
                        "Загрузка…",
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextSecondary
                    )
                    else -> Text(
                        "${"%.2f".format(balance.totalUsd)} USD",
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Green
                    )
                }
            }
            when {
                balance == null -> {}
                balance.isDemo -> Surface(color = Purple.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                    Text("🎮 DEMO", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                else -> Column(horizontalAlignment = Alignment.End) {
                    Text("SOL", fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "%.4f".format(balance.sol),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary
                    )
                }
            }
        }
    }
}

// ─── Activity feed ────────────────────────────────────────────────────────────

@Composable
fun ActivityFeedCard(events: List<ActivityEvent>, running: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = SurfaceCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(if (running) Green else TextSecondary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Активность", fontSize = 13.sp, color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (running) "в работе" else "ожидание",
                    fontSize = 11.sp,
                    color = if (running) Green else TextSecondary
                )
            }

            Spacer(Modifier.height(12.dp))

            if (events.isEmpty()) {
                Text(
                    "Журнал пуст — нажмите «Старт», чтобы бот начал сканировать",
                    fontSize = 12.sp, color = TextSecondary
                )
            } else {
                events.takeLast(8).asReversed().forEach { e ->
                    ActivityRow(e)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(e: ActivityEvent) {
    val dotColor = when (e.level) {
        ActivityLevel.SUCCESS -> Green
        ActivityLevel.WARN    -> Amber
        ActivityLevel.ERROR   -> Red
        ActivityLevel.INFO    -> TextSecondary
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            formatClock(e.timestamp),
            fontSize = 11.sp, color = TextSecondary,
            modifier = Modifier.width(60.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.padding(top = 5.dp).size(6.dp)
                .clip(CircleShape).background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            e.message,
            fontSize = 12.sp, color = TextPrimary, lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatClock(millis: Long): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${p(dt.hour)}:${p(dt.minute)}:${p(dt.second)}"
}

// ─── PnL Chart ────────────────────────────────────────────────────────────────

@Composable
fun PnlChartCard(data: List<Double>, trades: List<TradeHistoryItem> = emptyList()) {
    val totalPnl = trades.sumOf { it.pnl }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = SurfaceCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("P&L Кривая", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        "${if (totalPnl > 0) "+" else ""}${"%.2f".format(totalPnl)} USD",
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                        color = when {
                            trades.isEmpty() -> TextSecondary
                            totalPnl >= 0 -> Green
                            else -> Red
                        }
                    )
                }
                Surface(color = SurfaceCard, shape = RoundedCornerShape(8.dp)) {
                    Text("${trades.size} сделок", color = TextSecondary, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            if (data.size >= 2) {
                Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    drawPnlChart(data)
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Нет данных", color = TextSecondary, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            val chronological = trades.sortedBy { it.closedAt }
            if (chronological.size >= 2) {
                val first = chronological.first().closedAt
                val last = chronological.last().closedAt
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    (0..4).forEach { i ->
                        Text(dateLabel(first + (last - first) * i / 4),
                            fontSize = 10.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawPnlChart(data: List<Double>) {
    if (data.size < 2) return
    val w = size.width
    val h = size.height
    val pad = 4.dp.toPx()
    val minVal = data.min()
    val maxVal = data.max()
    val range = maxVal - minVal

    fun xOf(i: Int) = w * i / (data.size - 1)
    fun yOf(v: Double) = if (range == 0.0) h / 2f
    else (h - pad) - ((v - minVal) / range * (h - 2 * pad)).toFloat()

    // Grid lines
    repeat(4) { i ->
        val y = pad + (h - 2 * pad) * i / 3f
        drawLine(BorderColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }

    // Area fill
    val areaPath = Path().apply {
        moveTo(xOf(0), yOf(data[0]))
        data.forEachIndexed { i, v -> if (i > 0) lineTo(xOf(i), yOf(v)) }
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        areaPath,
        brush = Brush.verticalGradient(
            colors = listOf(Green.copy(alpha = 0.30f), Green.copy(alpha = 0f)),
            startY = 0f, endY = h
        )
    )

    // Line
    val linePath = Path().apply {
        moveTo(xOf(0), yOf(data[0]))
        data.forEachIndexed { i, v -> if (i > 0) lineTo(xOf(i), yOf(v)) }
    }
    drawPath(
        linePath, Green,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Last point glow
    val lx = xOf(data.size - 1)
    val ly = yOf(data.last())
    drawCircle(Green.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = Offset(lx, ly))
    drawCircle(Green, radius = 4.5.dp.toPx(), center = Offset(lx, ly))
}

// ─── Stats row ────────────────────────────────────────────────────────────────

@Composable
fun StatsRow(trades: List<TradeHistoryItem> = emptyList()) {
    val totalPnl = trades.sumOf { it.pnl }
    val wins = trades.count { it.isWin }
    val winrate = if (trades.isNotEmpty()) wins * 100 / trades.size else 0
    // Макс. просадка кривой доходности (USD): худший провал от достигнутого пика.
    val equity = equityCurve(trades)
    var peak = 0.0
    var maxDd = 0.0
    equity.forEach { v ->
        if (v > peak) peak = v
        if (v - peak < maxDd) maxDd = v - peak
    }
    val pnlColor = when {
        trades.isEmpty() -> TextSecondary
        totalPnl >= 0 -> Green
        else -> Red
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard("P&L", "%.2f".format(totalPnl), "USD", pnlColor, Modifier.weight(1f))
        StatCard("Winrate", "$winrate%", "${trades.size} сделок",
            if (trades.isEmpty()) TextSecondary else if (winrate >= 50) Green else Red, Modifier.weight(1f))
        StatCard("Просадка", "%.2f".format(maxDd), "USD макс.",
            if (maxDd < 0) Red else TextSecondary, Modifier.weight(1f))
    }
}

@Composable
fun StatCard(
    title: String, value: String, sub: String,
    color: Color, modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = SurfaceCard, shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                color = color, textAlign = TextAlign.Center)
            Text(sub, fontSize = 10.sp, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

// ─── Control buttons ──────────────────────────────────────────────────────────

@Composable
fun ControlButtons(status: BotStatus, onStatusChange: (BotStatus) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { onStatusChange(BotStatus.RUNNING) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            shape = RoundedCornerShape(12.dp),
            enabled = status != BotStatus.RUNNING,
            contentPadding = PaddingValues(vertical = 14.dp)
        ) { Text("▶ Старт", fontWeight = FontWeight.Bold, color = Color.Black) }

        Button(
            onClick = { onStatusChange(BotStatus.PAUSED) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Amber),
            shape = RoundedCornerShape(12.dp),
            enabled = status == BotStatus.RUNNING,
            contentPadding = PaddingValues(vertical = 14.dp)
        ) { Text("⏸ Пауза", fontWeight = FontWeight.Bold, color = Color.Black) }

        Button(
            onClick = { onStatusChange(BotStatus.STOPPED) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Red),
            shape = RoundedCornerShape(12.dp),
            enabled = status != BotStatus.STOPPED,
            contentPadding = PaddingValues(vertical = 14.dp)
        ) { Text("■ Стоп", fontWeight = FontWeight.Bold) }
    }
}

// ─── Section header ───────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, action: String = "") {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (action.isNotEmpty()) Text(action, fontSize = 12.sp, color = Blue)
    }
}

// ─── Strategy card (Dashboard) ────────────────────────────────────────────────

@Composable
fun DashStrategyCard(cfg: StrategyConfig, trades: List<TradeHistoryItem>) {
    val own = trades.filter { it.strategy == cfg.name }
    val pnl = own.sumOf { it.pnl }
    val winrate = if (own.isNotEmpty()) own.count { it.isWin } * 100 / own.size else 0
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = SurfaceCard, shape = RoundedCornerShape(14.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (cfg.isActive) GreenDim else RedDim),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (cfg.isActive) "✓" else "✗",
                    color = if (cfg.isActive) Green else Red,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cfg.name.ifEmpty { cfg.type.toTypeLabel() }, fontWeight = FontWeight.SemiBold,
                    color = TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Tag("🔍 СКАНЕР", Green)
                    Tag(cfg.timeframe, Purple)
                    Tag(cfg.type.toTypeLabel(), Amber)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)} USD",
                    color = if (own.isEmpty()) TextSecondary else if (pnl >= 0) Green else Red,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Text("WR: $winrate%", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
        Text(
            text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun String.toTypeLabel(): String =
    StrategyType.entries.firstOrNull { it.name == this }?.displayName ?: this

// ─── Trade card ───────────────────────────────────────────────────────────────

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

/** Закрытые сделки из БД (новые сверху) → элементы истории. */
private fun loadClosedTrades(db: DrxDatabase?): List<TradeHistoryItem> {
    if (db == null) return emptyList()
    return runCatching {
        db.tradeQueries.getAll().executeAsList()
            .filter { it.status == "CLOSED" }
            .map { t ->
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
    }.getOrDefault(emptyList())
}

private fun formatTradeDuration(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}ч ${m}м" else "${m}м"
}

/** Кривая доходности: кумулятивный PnL по закрытым сделкам в хронологическом порядке. */
private fun equityCurve(trades: List<TradeHistoryItem>): List<Double> {
    var acc = 0.0
    return trades.sortedBy { it.closedAt }.map { acc += it.pnl; acc }
}

private fun startOfTodayMillis(): Long {
    val tz = TimeZone.currentSystemDefault()
    return Clock.System.now().toLocalDateTime(tz).date.atStartOfDayIn(tz).toEpochMilliseconds()
}

private fun dateLabel(ms: Long): String {
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.day.toString().padStart(2, '0')}.${(dt.month.ordinal + 1).toString().padStart(2, '0')}"
}

/** «Ab3dEf…7xYz» — короткий вид адреса контракта. */
private fun shortMint(mint: String): String =
    if (mint.length <= 14) mint else "${mint.take(6)}…${mint.takeLast(6)}"

/** Цены мемкоинов крошечные — до 8 значащих знаков после точки, без хвостовых нулей. */
private fun fmtPrice(v: Double): String {
    if (v == 0.0) return "0"
    if (v >= 1) return "%.4f".format(v).trimEnd('0').trimEnd('.')
    return "%.8f".format(v).trimEnd('0').trimEnd('.')
}

/** Карточка «живой» открытой позиции: текущий PnL + ручное закрытие с подтверждением. */
@Composable
fun PositionCard(pos: OpenPosition, onClose: () -> Unit) {
    val pnlColor = if (pos.pnlUsd >= 0) Green else Red
    var confirm by remember(pos.tradeId) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = SurfaceCard, shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // Строка 1: OPEN + символ + режим + PnL%
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(color = GreenDim, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "OPEN", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(pos.symbol, fontWeight = FontWeight.ExtraBold, color = TextPrimary, fontSize = 15.sp)
                    Tag(if (pos.isDemo) "DEMO" else "REAL", if (pos.isDemo) Purple else Amber)
                }
                Text(
                    "${if (pos.pnlPercent >= 0) "+" else ""}${"%.1f".format(pos.pnlPercent)}%",
                    color = pnlColor, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            // Строка 2: Вход → Сейчас + P&L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PriceCol("Вход", fmtPrice(pos.entryPrice), pos.openedAt)
                Text(
                    "→", color = TextSecondary, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                PriceCol("Сейчас", fmtPrice(pos.currentPrice))
                Column(horizontalAlignment = Alignment.End) {
                    Text("P&L", fontSize = 10.sp, color = TextSecondary)
                    Text(
                        "${if (pos.pnlUsd > 0) "+" else ""}${"%.2f".format(pos.pnlUsd)} USD",
                        fontWeight = FontWeight.ExtraBold, color = pnlColor, fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "SL ${fmtPrice(pos.stopLoss)}  •  TP ${fmtPrice(pos.takeProfit)}",
                fontSize = 11.sp, color = TextSecondary
            )

            Spacer(Modifier.height(10.dp))

            // Ручное закрытие: рыночный выход необратим — подтверждаем вторым тапом.
            if (!confirm) {
                Surface(
                    color = RedDim, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clickable { confirm = true }
                ) {
                    Text(
                        "🔴 Закрыть позицию", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(10.dp), textAlign = TextAlign.Center
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = Red, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).clickable { confirm = false; onClose() }
                    ) {
                        Text(
                            "Да, закрыть", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp), textAlign = TextAlign.Center
                        )
                    }
                    Surface(
                        color = BgDark, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).clickable { confirm = false }
                    ) {
                        Text(
                            "Отмена", color = TextSecondary, fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp), textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TradeCard(item: TradeHistoryItem) {
    val pnlColor = if (item.isWin) Green else Red

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = SurfaceCard, shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // Строка 1: бейджи + символ + режим + WIN/LOSS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(color = GreenDim, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "LONG", color = Green, fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(item.symbol, fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary, fontSize = 15.sp)
                    Tag(if (item.isDemo) "DEMO" else "REAL", if (item.isDemo) Purple else Amber)
                }
                Surface(
                    color = if (item.isWin) GreenDim else RedDim,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        if (item.isWin) "WIN" else "LOSS",
                        color = pnlColor, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Строка 2: Вход → Выход → P&L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PriceCol("Вход", fmtPrice(item.entryPrice), item.openedAt)
                Text(
                    "→", color = TextSecondary, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                PriceCol("Выход", fmtPrice(item.exitPrice), item.closedAt)
                Column(horizontalAlignment = Alignment.End) {
                    Text("P&L", fontSize = 10.sp, color = TextSecondary)
                    Text(
                        "${if (item.pnl > 0) "+" else ""}${"%.2f".format(item.pnl)} USD",
                        fontWeight = FontWeight.ExtraBold, color = pnlColor, fontSize = 14.sp
                    )
                    Text(
                        "${if (item.pnlPercent > 0) "+" else ""}${"%.2f".format(item.pnlPercent)}%",
                        color = pnlColor.copy(alpha = 0.70f), fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            Spacer(Modifier.height(8.dp))

            // Строка 3: стратегия, время, причина закрытия
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoChip("📊", item.strategy)
                InfoChip("⏱", item.duration)
                InfoChip("🎯", item.closeReason)
            }

            // Строка 4: причина открытия
            Spacer(Modifier.height(6.dp))
            Text(
                "Сигнал: ${item.openReason}",
                fontSize = 11.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            // Строка 5: смарт-контракт — тап по строке копирует полный адрес
            if (item.mint.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                MintCopyRow(item.mint)
            }
        }
    }
}

/** Адрес контракта (mint) с копированием по тапу и индикацией «Скопировано». */
@Composable
fun MintCopyRow(mint: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(mint) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2_000); copied = false }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable {
            clipboard.setText(AnnotatedString(mint))
            copied = true
        }
    ) {
        Text("📋", fontSize = 11.sp)
        Text(
            if (copied) "✓ Скопировано" else shortMint(mint),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (copied) Green else TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Колонка «Вход/Выход»: цена + две метки времени одного момента [ms]:
 *  • DEX  — в UTC, как на сайте/графике DexScreener;
 *  • наше — в локальной таймзоне (реальное время исполнения у нас).
 */
@Composable
fun PriceCol(label: String, value: String, ms: Long? = null) {
    Column {
        Text(label, fontSize = 10.sp, color = TextSecondary)
        Text(value, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
        if (ms != null) {
            Text("DEX ${formatDexTime(ms)}", fontSize = 9.sp, color = TextSecondary)
            Text("наше ${formatLocalTime(ms)}", fontSize = 9.sp, color = TextSecondary)
        }
    }
}

@Composable
fun InfoChip(icon: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(icon, fontSize = 11.sp)
        Text(text, fontSize = 11.sp, color = TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── History screen ───────────────────────────────────────────────────────────

@Composable
fun HistoryScreen(allTrades: List<TradeHistoryItem> = emptyList()) {
    var filter by remember { mutableStateOf("Все") }
    val filterOptions = listOf("Все", "WIN", "LOSS", "DEMO", "REAL")

    val filtered = when (filter) {
        "WIN"  -> allTrades.filter { it.isWin }
        "LOSS" -> allTrades.filter { !it.isWin }
        "DEMO" -> allTrades.filter { it.isDemo }
        "REAL" -> allTrades.filter { !it.isDemo }
        else   -> allTrades
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("История сделок", fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Surface(color = SurfaceCard, shape = RoundedCornerShape(8.dp)) {
                Text("${filtered.size} сделок", fontSize = 12.sp, color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        // Фильтры
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterOptions) { f ->
                HistoryFilterChip(f, filter == f) { filter = f }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Мини-итоги
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val wins = filtered.count { it.isWin }
            val losses = filtered.count { !it.isWin }
            val totalPnl = filtered.sumOf { it.pnl }
            MiniStat("Прибыльных", "$wins", Green, Modifier.weight(1f))
            MiniStat("Убыточных", "$losses", Red, Modifier.weight(1f))
            MiniStat("Итого", "${if (totalPnl >= 0) "+" else ""}${"%.1f".format(totalPnl)}",
                if (totalPnl >= 0) Green else Red, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            EmptyState("Сделок нет")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { trade -> TradeCard(trade) }
            }
        }
    }
}

@Composable
fun HistoryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Green else SurfaceCard,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (selected) Color.Black else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun MiniStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = SurfaceCard, shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp, color = TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

// ─── Stats screen ─────────────────────────────────────────────────────────────

@Composable
fun StatsScreen(trades: List<TradeHistoryItem> = emptyList()) {
    val pnlData = equityCurve(trades)
    val totalPnl = trades.sumOf { it.pnl }
    val wins = trades.count { it.isWin }
    val losses = trades.size - wins
    val winrate = if (trades.isNotEmpty()) wins * 100 / trades.size else 0
    val totalFees = trades.sumOf { it.fee }

    val todayStart = remember { startOfTodayMillis() }
    val today = trades.filter { it.closedAt >= todayStart }
    val todayPnl = today.sumOf { it.pnl }
    val todayWins = today.count { it.isWin }
    val todayWinrate = if (today.isNotEmpty()) todayWins * 100 / today.size else 0

    val best = trades.maxByOrNull { it.pnl }
    val worst = trades.minByOrNull { it.pnl }

    val chronological = trades.sortedBy { it.closedAt }
    val dateLabels = if (chronological.size >= 2) {
        val first = chronological.first().closedAt
        val last = chronological.last().closedAt
        (0..4).map { i -> dateLabel(first + (last - first) * i / 4) }
    } else emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Аналитика", fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Surface(color = GreenDim, shape = RoundedCornerShape(8.dp)) {
                    Text("${trades.size} сделок", fontSize = 12.sp, color = Green,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }

        // Кривая доходности
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = SurfaceCard, shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Кривая доходности", fontSize = 13.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${if (totalPnl > 0) "+" else ""}${"%.2f".format(totalPnl)} USD",
                        fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                        color = when {
                            trades.isEmpty() -> TextSecondary
                            totalPnl >= 0 -> Green
                            else -> Red
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    if (pnlData.size >= 2) {
                        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                            drawPnlChart(pnlData)
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Нет данных", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (dateLabels.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            dateLabels.forEach { d ->
                                Text(d, fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // Сегодня vs. Всё время
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatsPanel(
                    "Сегодня",
                    listOf(
                        "P&L" to "${"%.2f".format(todayPnl)} USD",
                        "Сделок" to "${today.size}",
                        "Winrate" to "$todayWinrate%",
                        "Комиссия" to "${"%.2f".format(today.sumOf { it.fee })} USD"
                    ),
                    Modifier.weight(1f)
                )
                StatsPanel(
                    "Всё время",
                    listOf(
                        "P&L" to "${"%.2f".format(totalPnl)} USD",
                        "Сделок" to "${trades.size}",
                        "Winrate" to "$winrate%",
                        "Комиссия" to "${"%.2f".format(totalFees)} USD"
                    ),
                    Modifier.weight(1f)
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // Лучшая / Худшая
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = SurfaceCard, shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Экстремальные сделки", fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(12.dp))
                    if (best != null) {
                        ExtremeRow(
                            "🏆 Лучшая", "${best.symbol} LONG",
                            "${if (best.pnl > 0) "+" else ""}${"%.2f".format(best.pnl)} USD",
                            if (best.pnl >= 0) Green else Red
                        )
                    } else {
                        ExtremeRow("🏆 Лучшая", "—", "—", TextSecondary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                    Spacer(Modifier.height(8.dp))
                    if (worst != null) {
                        ExtremeRow(
                            "💀 Худшая", "${worst.symbol} LONG",
                            "${if (worst.pnl > 0) "+" else ""}${"%.2f".format(worst.pnl)} USD",
                            if (worst.pnl >= 0) Green else Red
                        )
                    } else {
                        ExtremeRow("💀 Худшая", "—", "—", TextSecondary)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // Win/Loss distribution
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = SurfaceCard, shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Распределение Win / Loss", fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(12.dp))
                    WinLossBar(wins = wins, losses = losses)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        LegendDot("WIN  $wins ($winrate%)", if (trades.isEmpty()) TextSecondary else Green)
                        LegendDot("LOSS  $losses (${if (trades.isEmpty()) 0 else 100 - winrate}%)", if (trades.isEmpty()) TextSecondary else Red)
                    }
                }
            }
        }
    }
}

@Composable
fun StatsPanel(title: String, rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = SurfaceCard, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontSize = 12.sp, color = TextSecondary)
                    Text(value, fontSize = 12.sp, color = TextPrimary,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun ExtremeRow(label: String, trade: String, pnl: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, fontSize = 11.sp, color = TextSecondary)
            Text(trade, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        Text(pnl, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
fun WinLossBar(wins: Int, losses: Int) {
    val total = wins + losses
    val winFraction = if (total == 0) 0.5f else wins.toFloat() / total
    Row(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        Box(Modifier.weight(winFraction.coerceAtLeast(0.01f)).fillMaxHeight().background(if (total == 0) BorderColor else Green))
        Box(Modifier.weight((1f - winFraction).coerceAtLeast(0.01f)).fillMaxHeight().background(if (total == 0) BorderColor else Red))
    }
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = TextSecondary)
    }
}

@Composable
fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = TextSecondary, fontSize = 14.sp)
    }
}

// ─── Strategies screen ────────────────────────────────────────────────────────

@Composable
fun StrategiesScreen(
    strategies: List<StrategyConfig>,
    onCreate: () -> Unit,
    onEdit: (StrategyConfig) -> Unit,
    onDelete: (StrategyConfig) -> Unit,
    onToggle: (StrategyConfig) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Стратегии", fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Surface(color = SurfaceCard, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "${strategies.count { it.isActive }} активных",
                        fontSize = 12.sp, color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (strategies.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🤖", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Стратегий нет", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text("Нажмите + чтобы создать первую стратегию",
                            fontSize = 13.sp, color = TextSecondary)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(strategies) { cfg ->
                        Spacer(Modifier.height(8.dp))
                        StrategyConfigCard(cfg, onEdit, onDelete, onToggle)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = Green,
            contentColor = Color.Black
        ) {
            Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StrategyConfigCard(
    cfg: StrategyConfig,
    onEdit: (StrategyConfig) -> Unit,
    onDelete: (StrategyConfig) -> Unit,
    onToggle: (StrategyConfig) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = SurfaceCard, shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (cfg.isActive) GreenDim else RedDim),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (cfg.isActive) "✓" else "✗",
                        color = if (cfg.isActive) Green else Red,
                        fontWeight = FontWeight.Bold, fontSize = 18.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cfg.name.ifEmpty { cfg.type.toTypeLabel() },
                        fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Tag("🔍 СКАНЕР", Green)
                        Tag(cfg.timeframe, Purple)
                        Tag(cfg.type.toTypeLabel(), Amber)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "мемкоины: LIQ ≥ ${fmtShortUsd(cfg.minLiquidityUsd)}, MC ${fmtShortUsd(cfg.minMarketCap)}–${fmtShortUsd(cfg.maxMarketCap)}",
                        fontSize = 10.sp, color = TextSecondary
                    )
                }
                Switch(
                    checked = cfg.isActive,
                    onCheckedChange = { onToggle(cfg) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Green
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoChip("🛡", "SL ${cfg.stopLossPercent}%")
                    InfoChip("🎯", "TP ${cfg.takeProfitPercent}%")
                    InfoChip("📦", "${cfg.positionSize}%")
                    if (cfg.trailingStopEnabled) InfoChip("📈", "трейл")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallIconBtn("✏️") { onEdit(cfg) }
                    SmallIconBtn("🗑") { onDelete(cfg) }
                }
            }
        }
    }
}

@Composable
fun SmallIconBtn(icon: String, onClick: () -> Unit) {
    Surface(
        color = BgDark, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(34.dp)
            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 14.sp)
        }
    }
}

private fun fmtShortUsd(v: Double): String = when {
    v >= 1_000_000 -> "$${"%.1f".format(v / 1_000_000).trimEnd('0').trimEnd('.')}M"
    v >= 1_000 -> "$${"%.0f".format(v / 1_000)}K"
    else -> "$${"%.0f".format(v)}"
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

    // ── Риск ──
    var maxDailyLoss by remember { mutableStateOf(c?.maxDailyLoss?.toFloat() ?: 5f) }
    var maxDrawdown by remember { mutableStateOf(c?.maxDrawdown?.toFloat() ?: 15f) }
    var cooldown by remember { mutableStateOf((c?.cooldownSeconds ?: 300).toString()) }

    // ── Фильтры токенов ──
    var minLiq by remember { mutableStateOf((c?.minLiquidityUsd ?: 10_000.0).toLong().toString()) }
    var minMc by remember { mutableStateOf((c?.minMarketCap ?: 50_000.0).toLong().toString()) }
    var maxMc by remember { mutableStateOf((c?.maxMarketCap ?: 10_000_000.0).toLong().toString()) }
    var minAge by remember { mutableStateOf((c?.minTokenAgeMinutes ?: 30L).toString()) }
    var maxAge by remember { mutableStateOf((c?.maxTokenAgeMinutes ?: 43_200L).toString()) }
    var minVolH1 by remember { mutableStateOf((c?.minVolumeH1Usd ?: 5_000.0).toLong().toString()) }
    var minRatio by remember { mutableStateOf(c?.minBuySellRatio?.toFloat() ?: 1f) }
    var rugcheckEnabled by remember { mutableStateOf(c?.rugcheckEnabled ?: true) }
    var rugcheckMax by remember { mutableStateOf((c?.rugcheckMaxScore ?: 5_000).toString()) }

    // Фильтр по диапазону (общий для всех типов)
    var rangeFilter by remember { mutableStateOf(c?.rangeFilterEnabled ?: false) }
    var rangeMaxEntry by remember { mutableStateOf(c?.rangeMaxEntryPct?.toFloat() ?: 0.8f) }
    var rangeLookback by remember { mutableStateOf((c?.rangeLookbackBars ?: 100).toString()) }

    // ── Индикаторы ──
    var rsiPeriod by remember { mutableStateOf((c?.rsiPeriod ?: 14).toString()) }
    var rsiOverbought by remember { mutableStateOf(c?.rsiOverbought?.toFloat() ?: 70f) }
    var rsiOversold by remember { mutableStateOf(c?.rsiOversold?.toFloat() ?: 30f) }
    var emaFast by remember { mutableStateOf((c?.emaFast ?: 9).toString()) }
    var emaSlow by remember { mutableStateOf((c?.emaSlow ?: 21).toString()) }
    var volThreshold by remember { mutableStateOf(c?.volumeThreshold?.toFloat() ?: 1.5f) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StrategyType.entries.forEach { t ->
                        ChoiceChip(t.displayName.substringBefore(" /").substringBefore(" Trend"), type == t.name) { type = t.name }
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
                FormSlider("Размер входа", positionSize, 1f, 25f,
                    "${"%.1f".format(positionSize)}% от баланса") { positionSize = it }
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
                    FormSlider("TP1 — прибыль", tp1Percent, 5f, 100f,
                        "+${"%.0f".format(tp1Percent)}%") { tp1Percent = it }
                    FormSlider("TP1 — закрыть", tp1Close, 10f, 90f,
                        "${"%.0f".format(tp1Close)}% позиции") { tp1Close = it }
                    FormSlider("TP2 — прибыль", tp2Percent, 10f, 150f,
                        "+${"%.0f".format(tp2Percent)}%") { tp2Percent = it }
                    FormSlider("TP2 — закрыть", tp2Close, 10f, 90f,
                        "${"%.0f".format(tp2Close)}% позиции") { tp2Close = it }
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
                FormSlider("Макс. дневной убыток", maxDailyLoss, 1f, 30f,
                    "${"%.1f".format(maxDailyLoss)}% от баланса") { maxDailyLoss = it }
                FormSlider("Макс. просадка", maxDrawdown, 5f, 50f,
                    "${"%.0f".format(maxDrawdown)}%") { maxDrawdown = it }
                FormTextField("Кулдаун между сделками, сек", cooldown, isNumber = true) { cooldown = it }
            }

            // 7. Фильтры токенов
            FormSection("🔍  Фильтры токенов (сканер)") {
                FormTextField("Мин. ликвидность, USD", minLiq, isNumber = true) { minLiq = it }
                FormTextField("Мин. Market Cap, USD", minMc, isNumber = true) { minMc = it }
                FormTextField("Макс. Market Cap, USD", maxMc, isNumber = true) { maxMc = it }
                FormTextField("Мин. возраст токена, мин", minAge, isNumber = true) { minAge = it }
                FormTextField("Макс. возраст токена, мин", maxAge, isNumber = true) { maxAge = it }
                FormTextField("Мин. объём за 1ч, USD", minVolH1, isNumber = true) { minVolH1 = it }
                FormSlider("Мин. buys/sells за 1ч", minRatio, 0.5f, 3f,
                    "${"%.1f".format(minRatio)}") { minRatio = it }
                FormToggle("RugCheck-проверка", rugcheckEnabled) { rugcheckEnabled = it }
                if (rugcheckEnabled) {
                    FormTextField("Макс. risk score (RugCheck)", rugcheckMax, isNumber = true) { rugcheckMax = it }
                }
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

            // 8. Индикаторы (по типу стратегии)
            if (type == StrategyType.RSI_EMA.name) {
                FormSection("📊  RSI") {
                    FormTextField("Период RSI", rsiPeriod, isNumber = true) { rsiPeriod = it }
                    FormSlider("Зона перекупленности (OB)", rsiOverbought, 60f, 90f,
                        "%.0f".format(rsiOverbought)) { rsiOverbought = it }
                    FormSlider("Зона перепроданности (OS)", rsiOversold, 10f, 40f,
                        "%.0f".format(rsiOversold)) { rsiOversold = it }
                }
                FormSection("📉  EMA") {
                    FormTextField("Быстрая EMA", emaFast, isNumber = true) { emaFast = it }
                    FormTextField("Медленная EMA", emaSlow, isNumber = true) { emaSlow = it }
                }
            }

            if (type == StrategyType.MOMENTUM.name) {
                FormSection("📦  Momentum-фильтры") {
                    FormSlider("Минимальный объём (×средний)", volThreshold, 1f, 5f,
                        "${"%.1f".format(volThreshold)}x") { volThreshold = it }
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
                    maxDailyLoss = maxDailyLoss.toDouble(),
                    maxDrawdown = maxDrawdown.toDouble(),
                    cooldownSeconds = cooldown.toIntOrNull() ?: 300,
                    minLiquidityUsd = minLiq.toDoubleOrNull() ?: 10_000.0,
                    minMarketCap = minMc.toDoubleOrNull() ?: 50_000.0,
                    maxMarketCap = maxMc.toDoubleOrNull() ?: 10_000_000.0,
                    minTokenAgeMinutes = minAge.toLongOrNull() ?: 30L,
                    maxTokenAgeMinutes = maxAge.toLongOrNull() ?: 43_200L,
                    minVolumeH1Usd = minVolH1.toDoubleOrNull() ?: 5_000.0,
                    minBuySellRatio = minRatio.toDouble(),
                    rugcheckEnabled = rugcheckEnabled,
                    rugcheckMaxScore = rugcheckMax.toIntOrNull() ?: 5_000,
                    rangeFilterEnabled = rangeFilter,
                    rangeMaxEntryPct = rangeMaxEntry.toDouble(),
                    rangeLookbackBars = rangeLookback.toIntOrNull() ?: 100,
                    rsiPeriod = rsiPeriod.toIntOrNull() ?: 14,
                    rsiOverbought = rsiOverbought.toDouble(),
                    rsiOversold = rsiOversold.toDouble(),
                    emaFast = emaFast.toIntOrNull() ?: 9,
                    emaSlow = emaSlow.toIntOrNull() ?: 21,
                    atrPeriod = c?.atrPeriod ?: 14,
                    volumeThreshold = volThreshold.toDouble(),
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

// ─── Settings screen ──────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    settings: DrxSettings,
    connected: Boolean = false,
    demoMode: Boolean = true,
    demoBalance: Double = 10_000.0,
    signalOnly: Boolean = false,
    onDemoModeChange: (Boolean) -> Unit = {},
    onSignalOnlyChange: (Boolean) -> Unit = {},
    onResetDemo: () -> Unit = {},
    onResetBot: () -> Unit = {},
    onSave: (DrxSettings) -> Unit
) {
    var rpcUrl by remember(settings) { mutableStateOf(settings.rpcUrl) }
    var walletSeed by remember(settings) { mutableStateOf(settings.walletSeed) }
    var showSeed by remember { mutableStateOf(false) }
    var telegramToken by remember(settings) { mutableStateOf(settings.telegramToken) }
    var telegramChatId by remember(settings) { mutableStateOf(settings.telegramChatId) }
    var saved by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Настройки", fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text("Управление DRX", fontSize = 12.sp, color = TextSecondary)
            }
            if (saved) {
                Surface(color = GreenDim, shape = RoundedCornerShape(8.dp)) {
                    Text("✓ Сохранено", color = Green, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Режим торговли ────────────────────────────────────────────
            FormSection("🎮  Режим торговли") {
                Surface(
                    color = if (demoMode) Purple.copy(alpha = 0.12f) else Red.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (demoMode) "🎮  DEMO — сделки виртуальные (счёт ${"%.0f".format(demoBalance)} USD)"
                        else "💸  REAL — реальные свопы через Jupiter с вашего кошелька!",
                        fontSize = 12.sp,
                        color = if (demoMode) Purple else Red,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                FormToggle("Режим DEMO (paper-trading)", demoMode) { onDemoModeChange(it) }
                if (!demoMode) {
                    Surface(color = Red.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                        Text("⚠ Реальная торговля — реальные деньги! Убедитесь, что seed-фраза задана и на кошельке есть SOL.",
                            color = Red, fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = BgDark, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                        .clickable { onResetDemo() }
                ) {
                    Text("🔄 Сбросить демо-счёт к $10 000", color = TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(4.dp))
                // Применяется сразу и синхронизируется с Telegram.
                FormToggle("Только сигнал (без сделок)", signalOnly) { onSignalOnlyChange(it) }
                if (signalOnly) {
                    Surface(color = Blue.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                        Text("📣 Бот только присылает сигнал и параметры входа — сделки не открываются",
                            color = Blue, fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            }

            // ── Кошелёк Solana ────────────────────────────────────────────
            FormSection("🔑  Кошелёк Solana") {
                Surface(
                    color = if (settings.walletSeed.isNotBlank()) GreenDim else RedDim,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (settings.walletSeed.isNotBlank()) "✅  Seed-фраза сохранена (нужна для REAL-режима)"
                        else "⚠️  Seed-фраза не задана — REAL-режим недоступен",
                        fontSize = 12.sp,
                        color = if (settings.walletSeed.isNotBlank()) Green else Red,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = walletSeed,
                        onValueChange = { walletSeed = it; saved = false },
                        label = { Text("Seed-фраза (12/24 слова)", color = TextSecondary) },
                        modifier = Modifier.weight(1f),
                        visualTransformation = if (showSeed) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = SurfaceCard, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(52.dp)
                            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                            .clickable { showSeed = !showSeed }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (showSeed) "🙈" else "👁", fontSize = 20.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                FormTextField("Solana RPC URL", rpcUrl) { rpcUrl = it; saved = false }
                Text("Смена RPC применяется после перезапуска приложения.",
                    fontSize = 11.sp, color = TextSecondary)
            }

            // ── Telegram ──────────────────────────────────────────────────
            FormSection("📲  Telegram") {
                FormTextField("Bot Token", telegramToken) { telegramToken = it; saved = false }
                FormTextField("Chat ID", telegramChatId, isNumber = true) { telegramChatId = it; saved = false }
                Text(
                    "Управление ботом и алерты о сделках. Токен — у @BotFather, свой ID — у @userinfobot.",
                    fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                )
            }

            // ── Сброс ─────────────────────────────────────────────────────
            FormSection("🚨  Сброс бота") {
                Text(
                    "Удаляет все сделки, кеш токенов и балансов, сбрасывает демо-счёт. Стратегии и настройки остаются.",
                    fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                if (!confirmReset) {
                    Surface(
                        color = RedDim, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { confirmReset = true }
                    ) {
                        Text("🗑 Полный сброс данных…", color = Red, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = Red, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).clickable {
                                onResetBot(); confirmReset = false
                            }
                        ) {
                            Text("Да, стереть всё", color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center)
                        }
                        Surface(
                            color = SurfaceCard, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).clickable { confirmReset = false }
                        ) {
                            Text("Отмена", color = TextSecondary, fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                onSave(DrxSettings(
                    rpcUrl = rpcUrl,
                    walletSeed = walletSeed,
                    telegramToken = telegramToken,
                    telegramChatId = telegramChatId,
                ))
                saved = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            Text("💾  Сохранить настройки", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 16.sp)
        }
    }
}

// ─── Form helpers (в стиле MRX) ───────────────────────────────────────────────

@Composable
fun FormSection(title: String, content: @Composable () -> Unit) {
    Surface(color = SurfaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun FormTextField(label: String, value: String, isNumber: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (isNumber) v.filter { it.isDigit() || it == '.' || it == '-' } else v) },
        label = { Text(label, color = TextSecondary) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(10.dp),
        singleLine = true
    )
}

@Composable
fun FormToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Green
            )
        )
    }
}

@Composable
fun FormSlider(label: String, value: Float, min: Float, max: Float, valueLabel: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 12.sp, color = TextSecondary)
            Text(valueLabel, fontSize = 12.sp, color = Green, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = Green,
                activeTrackColor = Green,
                inactiveTrackColor = BorderColor
            )
        )
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Blue else BgDark,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .border(1.dp, if (selected) Blue else BorderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) Color.White else TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Green,
    unfocusedBorderColor = BorderColor,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Green,
    focusedContainerColor = BgDark,
    unfocusedContainerColor = BgDark,
)
