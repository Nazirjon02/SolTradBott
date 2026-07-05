package tj.khujand.solana.trading.bot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.createServiceController
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.data.StrategySlot
import tj.khujand.solana.trading.bot.data.StrategySlotsManager
import tj.khujand.solana.trading.bot.data.parseStrategyColor
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.isAndroid
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import tj.khujand.solana.trading.bot.util.AppSettings
import tj.khujand.solana.trading.bot.util.formatDemoBalance
import tj.khujand.solana.trading.bot.util.formatSimpleNumber

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun StrategiesScreen() {
    val service          = remember { TradingRuntime.tradingBotService() }
    val serviceController = remember { createServiceController() }

    val runningIds by TradingRuntime.runningStrategiesFlow.collectAsState()
    val isMonitoring = runningIds.isNotEmpty()

    var slots          by remember { mutableStateOf(StrategySlotsManager.getSlots()) }
    var selectedSlotId by remember { mutableStateOf(StrategySlotsManager.getActiveId()) }

    var monitoredTokens by remember { mutableStateOf(emptyList<MonitoredToken>()) }
    var tokenCounts     by remember { mutableStateOf(emptyMap<String, Int>()) }
    var isRequesting    by remember { mutableStateOf(false) }
    var demoBalance     by remember { mutableStateOf(DemoAccountManager.getBalance()) }

    var showEditFilter   by remember { mutableStateOf(false) }
    var showAddDialog    by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<StrategySlot?>(null) }

    val scope = rememberCoroutineScope()

    val selectedSlot = slots.find { it.id == selectedSlotId } ?: slots.firstOrNull()
    val runningSlots = slots.filter { runningIds.contains(it.id) }

    fun refreshBalance() { demoBalance = DemoAccountManager.getBalance() }
    fun reloadSlots() { slots = StrategySlotsManager.getSlots() }

    fun requestKey(id: String): String =
        if (id == "default") AppSettings.KEY_REQUEST_IN_PROGRESS else "${AppSettings.KEY_REQUEST_IN_PROGRESS}_$id"

    fun aggregateTokens() {
        val all    = mutableListOf<MonitoredToken>()
        val counts = mutableMapOf<String, Int>()
        runningIds.forEach { id ->
            val toks = TradingRuntime.monitorFor(id).monitoredTokens.toList()
            counts[id] = toks.size
            all += toks
        }
        monitoredTokens = all
        tokenCounts = counts
    }

    LaunchedEffect(Unit) {
        val ids = StrategySlotsManager.getRunningIds()
        ids.forEach { TradingRuntime.monitorFor(it).restoreFromCache() }
        monitoredTokens = ids.flatMap { TradingRuntime.monitorFor(it).monitoredTokens.toList() }
        refreshBalance()
        if (!isAndroid()) {
            ids.forEach { id ->
                if (!TradingRuntime.controllerFor(id).isMonitoring()) {
                    TradingRuntime.controllerFor(id).startMonitoringAsync(10)
                }
            }
        }
    }

    LaunchedEffect(isMonitoring, runningIds) {
        if (!isMonitoring) { isRequesting = false; return@LaunchedEffect }
        while (true) {
            delay(2_000)
            if (isAndroid()) runningIds.forEach { TradingRuntime.monitorFor(it).restoreFromCache() }
            isRequesting = runningIds.any { AppSettings.getBooleanSafe(requestKey(it), false) }
            aggregateTokens()
            refreshBalance()
        }
    }

    fun startStrategy(slot: StrategySlot) {
        StrategySlotsManager.setActiveId(slot.id)
        if (isAndroid()) serviceController?.startMonitoring()
        service.startStrategy(slot.id)
    }

    fun stopStrategy(slot: StrategySlot) {
        service.stopStrategy(slot.id)
        if (isAndroid() && service.getRunningStrategyIds().isEmpty()) {
            serviceController?.stopMonitoring()
        }
        isRequesting = false
    }

    fun clearStrategy(slot: StrategySlot) {
        scope.launch {
            TradingRuntime.monitorFor(slot.id).clearAllTokens()
            aggregateTokens()
            refreshBalance()
        }
    }

    fun saveCurrentToSlot() {
        val slotId = selectedSlotId
        val global = FilterSettingsManager.loadSettings()
        StrategySlotsManager.syncSlotFromGlobal(slotId, global)
        reloadSlots()
    }

    // If editing filter, show FilterScreen overlay
    if (showEditFilter && selectedSlot != null) {
        FilterEditOverlay(
            settings  = FilterSettingsManager.loadSettings(),
            slotName  = selectedSlot.name,
            onChanged = { updated ->
                FilterSettingsManager.saveSettings(updated)
                if (runningIds.contains(selectedSlotId)) {
                    TradingRuntime.monitorFor(selectedSlotId).filterSettings = updated
                }
            },
            onSaveToSlot = { saveCurrentToSlot() },
            onClose = { showEditFilter = false }
        )
        return
    }

    StrategiesContent(
        slots          = slots,
        selectedSlotId = selectedSlotId,
        runningIds     = runningIds,
        monitoredTokens = monitoredTokens,
        tokenCounts    = tokenCounts,
        isMonitoring   = isMonitoring,
        isRequesting   = isRequesting,
        demoBalance    = demoBalance,
        onSelectSlot   = { slot ->
            selectedSlotId = slot.id
            StrategySlotsManager.setActiveId(slot.id)
        },
        onStart = { slot -> startStrategy(slot) },
        onStop  = { slot -> stopStrategy(slot) },
        onClear = { selectedSlot?.let { clearStrategy(it) } },
        onEditFilter  = { showEditFilter = true },
        onDeleteSlot  = { slot -> showDeleteConfirm = slot },
        onAddSlot     = { showAddDialog = true },
        onCloseToken  = { address, isProfit ->
            scope.launch {
                TradingRuntime.activeMonitors().forEach { it.closeTokenManually(address, isProfit) }
                aggregateTokens()
                refreshBalance()
            }
        },
    )

    // ── Add Strategy Dialog ──────────────────────────────────────────────────
    if (showAddDialog) {
        AddStrategyDialog(
            existingNames = slots.map { it.name },
            onConfirm = { name, emoji, colorHex, baseSlot ->
                val newSlot = StrategySlot(
                    id       = "custom_${name.lowercase().replace(' ', '_')}_${kotlin.time.Clock.System.now().toEpochMilliseconds()}",
                    name     = name,
                    emoji    = emoji,
                    colorHex = colorHex,
                    settings = baseSlot?.settings ?: FilterSettingsManager.loadSettings()
                        .copy(seedPhrase = "", jupiterApiKey = "", aiApiKey = ""),
                    isCustom = true,
                )
                StrategySlotsManager.saveOrUpdateSlot(newSlot)
                reloadSlots()
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
            baseSlots = slots,
        )
    }

    // ── Delete Confirm ───────────────────────────────────────────────────────
    showDeleteConfirm?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor   = DarkSurface,
            title = { Text("Удалить ${toDelete.name}?", color = DangerRed) },
            text  = {
                Text(
                    "Стратегия «${toDelete.name}» будет удалена. Это действие нельзя отменить.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        StrategySlotsManager.deleteSlot(toDelete.id)
                        reloadSlots()
                        if (selectedSlotId == toDelete.id) {
                            selectedSlotId = slots.firstOrNull()?.id ?: ""
                        }
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                ) { Text("Удалить", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
        )
    }
}

// ─── Main Content ─────────────────────────────────────────────────────────────

@Composable
private fun StrategiesContent(
    slots: List<StrategySlot>,
    selectedSlotId: String,
    runningIds: Set<String>,
    monitoredTokens: List<MonitoredToken>,
    tokenCounts: Map<String, Int>,
    isMonitoring: Boolean,
    isRequesting: Boolean,
    demoBalance: Double,
    onSelectSlot: (StrategySlot) -> Unit,
    onStart: (StrategySlot) -> Unit,
    onStop: (StrategySlot) -> Unit,
    onClear: () -> Unit,
    onEditFilter: () -> Unit,
    onDeleteSlot: (StrategySlot) -> Unit,
    onAddSlot: () -> Unit,
    onCloseToken: (String, Boolean) -> Unit,
) {
    val selectedSlot = slots.find { it.id == selectedSlotId } ?: slots.firstOrNull()
    val runningSlots = slots.filter { runningIds.contains(it.id) }
    val settings     = remember(selectedSlotId) { FilterSettingsManager.loadSettings() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ── Status Banner ────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(52.dp))
            StatusBanner(
                isMonitoring    = isMonitoring,
                isRequesting    = isRequesting,
                runningSlots    = runningSlots,
                monitoredTokens = monitoredTokens,
                demoBalance     = demoBalance,
                settings        = settings,
                modifier        = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(16.dp))
        }

        // ── Strategy Selector ────────────────────────────────────────────────
        item {
            StrategySelectorHeader(onAddSlot = onAddSlot)
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(slots, key = { it.id }) { slot ->
                    StrategyCard(
                        slot       = slot,
                        isSelected = slot.id == selectedSlotId,
                        isRunning  = runningIds.contains(slot.id),
                        tokenCount = tokenCounts[slot.id] ?: 0,
                        onClick    = { onSelectSlot(slot) },
                    )
                }
                item {
                    AddStrategyCard(onClick = onAddSlot)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Selected Strategy Controls ───────────────────────────────────────
        if (selectedSlot != null) {
            item {
                SelectedStrategyPanel(
                    slot         = selectedSlot,
                    isRunning    = runningIds.contains(selectedSlot.id),
                    hasTokens    = (tokenCounts[selectedSlot.id] ?: 0) > 0,
                    onStart      = { onStart(selectedSlot) },
                    onStop       = { onStop(selectedSlot) },
                    onEdit       = onEditFilter,
                    onClear      = onClear,
                    onDelete     = if (selectedSlot.isCustom) ({ onDeleteSlot(selectedSlot) }) else null,
                    modifier     = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── Active Filters Strip ─────────────────────────────────────────────
        if (isMonitoring) {
            item {
                ActiveFiltersStrip(
                    settings = settings,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        // ── Token list ───────────────────────────────────────────────────────
        if (monitoredTokens.isNotEmpty()) {
            items(monitoredTokens) { token ->
                TokenItemCard(
                    token = token,
                    onCloseToken = onCloseToken,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        } else {
            item {
                Spacer(Modifier.height(24.dp))
                StrategiesEmptyState(
                    isMonitoring = isMonitoring,
                    runningSlot  = runningSlots.firstOrNull(),
                    modifier     = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

// ─── Status Banner ────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(
    isMonitoring: Boolean,
    isRequesting: Boolean,
    runningSlots: List<StrategySlot>,
    monitoredTokens: List<MonitoredToken>,
    demoBalance: Double,
    settings: FilterSettings,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
    )

    val primarySlot = runningSlots.firstOrNull()
    val gradientBrush = if (isMonitoring && primarySlot != null) {
        val (r, g, b) = parseStrategyColor(primarySlot.colorHex)
        Brush.linearGradient(
            colors = listOf(
                Color(r, g, b, 200),
                Color(r, g, b, 100),
            )
        )
    } else {
        // Стоп-состояние — фирменное свечение Solana поверх тёмной карточки
        SolanaGradientSoft
    }

    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface, RoundedCornerShape(20.dp))
                .background(gradientBrush, RoundedCornerShape(20.dp))
                .border(1.dp, SolanaGradient, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // Left: title + status
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "SolTradeBot",
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(
                                when {
                                    isRequesting -> Color(0xFFFBBF24).copy(alpha = pulse)
                                    isMonitoring -> SuccessGreen
                                    else -> Color.White.copy(alpha = 0.35f)
                                }
                            )
                        )
                        Text(
                            when {
                                isRequesting -> "Scanning…"
                                isMonitoring && runningSlots.size == 1 && primarySlot != null ->
                                    "${primarySlot.emoji} ${primarySlot.name}"
                                isMonitoring && runningSlots.size > 1 ->
                                    "⚡ ${runningSlots.size} стратегий"
                                isMonitoring -> "Running"
                                else -> "Stopped"
                            },
                            fontSize = 13.sp,
                            color    = Color.White.copy(alpha = 0.9f),
                            fontWeight = if (isMonitoring) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                    Text(
                        "Balance  \$${formatDemoBalance(demoBalance)}",
                        fontSize = 12.sp,
                        color    = Color.White.copy(alpha = 0.7f),
                    )
                }

                // Right: token count badge
                val atLimit = monitoredTokens.size >= settings.maxTokensToMonitor
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = if (atLimit) 0.25f else 0.15f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${monitoredTokens.size}",
                            fontSize   = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                        )
                        Text(
                            "/ ${settings.maxTokensToMonitor}",
                            fontSize = 11.sp,
                            color    = Color.White.copy(alpha = 0.7f),
                        )
                        if (atLimit) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "FULL",
                                fontSize   = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color      = WarnAmber,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Strategy Selector Header ─────────────────────────────────────────────────

@Composable
private fun StrategySelectorHeader(onAddSlot: () -> Unit) {
    SectionHeader(
        title = "Стратегии",
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        TextButton(
            onClick = onAddSlot,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = SolGreen)
            Spacer(Modifier.width(4.dp))
            Text("Новая", fontSize = 13.sp, color = SolGreen)
        }
    }
}

// ─── Strategy Card ────────────────────────────────────────────────────────────

@Composable
private fun StrategyCard(
    slot: StrategySlot,
    isSelected: Boolean,
    isRunning: Boolean,
    tokenCount: Int,
    onClick: () -> Unit,
) {
    val (r, g, b) = parseStrategyColor(slot.colorHex)
    val accentColor = Color(r, g, b)

    val transition = rememberInfiniteTransition()
    val runPulse by transition.animateFloat(
        initialValue  = 0.6f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
    )

    val borderModifier = when {
        isRunning  -> Modifier.border(2.dp, accentColor, RoundedCornerShape(14.dp))
        isSelected -> Modifier.border(1.5.dp, SolPurple.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
        else       -> Modifier
    }

    Surface(
        modifier = Modifier
            .width(86.dp)
            .then(borderModifier)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = when {
            isRunning  -> accentColor.copy(alpha = 0.15f)
            isSelected -> DarkSurfaceVar
            else       -> DarkSurface
        },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(slot.emoji, fontSize = 26.sp)
            Text(
                slot.name,
                fontSize  = 10.sp,
                color     = if (isSelected || isRunning) TextPrimary else TextSecondary,
                fontWeight = if (isSelected || isRunning) FontWeight.SemiBold else FontWeight.Normal,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier.size(5.dp).clip(CircleShape).background(
                        if (isRunning) accentColor.copy(alpha = runPulse) else TextMuted
                    )
                )
                Text(
                    if (isRunning) "$tokenCount токен${tokenSuffix(tokenCount)}" else "Стоп",
                    fontSize = 9.sp,
                    color    = if (isRunning) accentColor else TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AddStrategyCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(86.dp)
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = DarkBg,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Добавить",
                tint     = TextSecondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text("Добавить", fontSize = 9.sp, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

// ─── Selected Strategy Panel ──────────────────────────────────────────────────

@Composable
private fun SelectedStrategyPanel(
    slot: StrategySlot,
    isRunning: Boolean,
    hasTokens: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val (r, g, b) = parseStrategyColor(slot.colorHex)
    val accentColor = Color(r, g, b)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(slot.emoji, fontSize = 20.sp)
                    Column {
                        Text(
                            slot.name,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = TextPrimary,
                        )
                        Text(
                            strategyDescription(slot),
                            fontSize = 11.sp,
                            color    = TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = DangerRed.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Quick stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatChip("MC", "\$${formatSimpleNumber(slot.settings.entryMinMarketCap.toInt())}–${formatSimpleNumber(slot.settings.entryMaxMarketCap.toInt())}", accentColor)
                StatChip("Liq", "≥\$${formatSimpleNumber(slot.settings.entryMinLiquidity.toInt())}", accentColor)
                StatChip("Age", "≤${slot.settings.entryMaxAgeMinutes}m", accentColor)
            }

            HorizontalDivider(color = DarkBorder)

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // START / STOP — фирменный градиент purple→green; стоп — danger
                GradientButton(
                    text     = if (isRunning) "СТОП" else "СТАРТ",
                    onClick  = if (isRunning) onStop else onStart,
                    modifier = Modifier.weight(1f),
                    icon     = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    danger   = isRunning,
                )

                // EDIT
                OutlinedButton(
                    onClick  = onEdit,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Настройки", fontSize = 13.sp)
                }

                // CLEAR
                if (hasTokens && isRunning) {
                    IconButton(
                        onClick  = onClear,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить", tint = DangerRed.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

// ─── Active Filters Strip (with modifier) ─────────────────────────────────────

@Composable
private fun ActiveFiltersStrip(settings: FilterSettings, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        color    = SolPurpleBg,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(13.dp), tint = SolPurple)
                Text("Фильтры", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SolPurpleDim)
            }
            Text(
                "MC \$${formatSimpleNumber(settings.entryMinMarketCap.toInt())}–${formatSimpleNumber(settings.entryMaxMarketCap.toInt())} " +
                "• Liq ≥\$${formatSimpleNumber(settings.entryMinLiquidity.toInt())} " +
                "• Age ≤${settings.entryMaxAgeMinutes}m",
                fontSize = 10.sp,
                color    = TextSecondary,
            )
        }
    }
}

// ─── Empty State ──────────────────────────────────────────────────────────────

@Composable
private fun StrategiesEmptyState(
    isMonitoring: Boolean,
    runningSlot: StrategySlot?,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = if (isMonitoring) Icons.Default.TrackChanges else Icons.Default.Search,
        title = if (isMonitoring) "Сканирую рынок…" else "Готов к запуску",
        subtitle = if (isMonitoring)
            "Токены, соответствующие фильтрам ${runningSlot?.name ?: ""}, появятся здесь"
        else
            "Выберите стратегию и нажмите СТАРТ",
        modifier = modifier,
    )
}

// ─── Filter Edit Overlay ──────────────────────────────────────────────────────

@Composable
private fun FilterEditOverlay(
    settings: FilterSettings,
    slotName: String,
    onChanged: (FilterSettings) -> Unit,
    onSaveToSlot: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(DarkBg),
    ) {
        Spacer(Modifier.height(48.dp))
        // Close / Save bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
            }
            Text(
                "Настройки: $slotName",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            TextButton(onClick = {
                onSaveToSlot()
                onClose()
            }) {
                Text("Сохранить", color = SolPurple, fontSize = 13.sp)
            }
        }
        HorizontalDivider(color = DarkBorder)
        // Reuse existing FilterScreen
        FilterScreen(
            currentSettings   = settings,
            onSettingsChanged = onChanged,
            onTestSwap        = { "" },
            onClose           = onClose,
        )
    }
}

// ─── Add Strategy Dialog ──────────────────────────────────────────────────────

@Composable
private fun AddStrategyDialog(
    existingNames: List<String>,
    baseSlots: List<StrategySlot>,
    onConfirm: (name: String, emoji: String, colorHex: String, baseSlot: StrategySlot?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name     by remember { mutableStateOf("") }
    var emoji    by remember { mutableStateOf("⚡") }
    var colorIdx by remember { mutableStateOf(0) }
    var baseSlot by remember { mutableStateOf<StrategySlot?>(null) }
    val nameError = name.isNotBlank() && (name.length < 2 || name in existingNames)

    val colorOptions = listOf("#FF6B35", "#3B82F6", "#10B981", "#9747FF", "#F59E0B", "#EF4444", "#00E5FF")
    val emojiOptions = listOf("⚡", "🔥", "💎", "🚀", "🎯", "⚖️", "🛡", "🦁", "🐉", "🌊")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = DarkSurface,
        title = { Text("Новая стратегия", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название", color = TextSecondary, fontSize = 12.sp) },
                    isError = nameError,
                    supportingText = if (nameError) ({ Text(if (name in existingNames) "Имя уже занято" else "Минимум 2 символа", color = DangerRed, fontSize = 11.sp) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SolPurple,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                    ),
                    singleLine = true,
                )

                // Emoji picker
                Text("Иконка", fontSize = 12.sp, color = TextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(emojiOptions) { e ->
                        Surface(
                            modifier  = Modifier.size(40.dp).clickable { emoji = e },
                            shape     = CircleShape,
                            color     = if (emoji == e) SolPurpleBg else DarkSurfaceVar,
                            border    = if (emoji == e) androidx.compose.foundation.BorderStroke(1.5.dp, SolPurple) else null,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(e, fontSize = 20.sp)
                            }
                        }
                    }
                }

                // Color picker
                Text("Цвет", fontSize = 12.sp, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEachIndexed { idx, hex ->
                        val (r, g, b) = parseStrategyColor(hex)
                        val col = Color(r, g, b)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(col)
                                .then(if (colorIdx == idx) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                .clickable { colorIdx = idx }
                        )
                    }
                }

                // Base strategy
                Text("Скопировать настройки из", fontSize = 12.sp, color = TextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = baseSlot == null,
                            onClick  = { baseSlot = null },
                            label    = { Text("Текущие", fontSize = 11.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SolPurpleBg,
                                selectedLabelColor     = SolPurple,
                            ),
                        )
                    }
                    items(baseSlots) { s ->
                        FilterChip(
                            selected = baseSlot?.id == s.id,
                            onClick  = { baseSlot = s },
                            label    = { Text("${s.emoji} ${s.name}", fontSize = 11.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SolPurpleBg,
                                selectedLabelColor     = SolPurple,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = {
                    if (name.isNotBlank() && !nameError)
                        onConfirm(name.trim(), emoji, colorOptions[colorIdx], baseSlot)
                },
                enabled  = name.isNotBlank() && !nameError,
                colors   = ButtonDefaults.buttonColors(containerColor = SolPurple),
            ) { Text("Создать", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
        },
    )
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun strategyDescription(slot: StrategySlot): String {
    val s = slot.settings
    return "MC \$${formatSimpleNumber(s.entryMinMarketCap.toInt())}–${formatSimpleNumber(s.entryMaxMarketCap.toInt())} • ${s.exitStrategy}"
}

private fun tokenSuffix(count: Int): String = when {
    count == 1 -> ""
    count in 2..4 -> "а"
    else -> "ов"
}
