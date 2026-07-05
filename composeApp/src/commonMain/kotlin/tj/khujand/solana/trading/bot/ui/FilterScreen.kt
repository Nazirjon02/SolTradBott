package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotSettings
import tj.khujand.solana.trading.bot.crypto.createSignerFromSeedPhrase
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.network.FilterSettings
import tj.khujand.solana.trading.bot.util.AppSettings
import tj.khujand.solana.trading.bot.util.formatDemoBalance

// ─── Tab Definitions ─────────────────────────────────────────────────────────

private enum class FilterTab(val label: String, val icon: ImageVector) {
    ENTRY("Вход",      Icons.Default.FilterList),
    EXIT ("Выход",     Icons.Default.ExitToApp),
    RISK ("Риск",      Icons.Default.Security),
    AI   ("ИИ",        Icons.Default.AutoAwesome),
    SETUP("Настройка", Icons.Default.Settings)
}

// ─── Main Screen ─────────────────────────────────────────────────────────────

@Composable
fun FilterScreen(
    currentSettings: FilterSettings,
    onSettingsChanged: (FilterSettings) -> Unit,
    onTestSwap: suspend () -> String,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var showResetDemoDialog by remember { mutableStateOf(false) }
    var demoBalance by remember { mutableStateOf(DemoAccountManager.getBalance()) }

    var telegramBotEnabled by remember {
        mutableStateOf(AppSettings.getBooleanSafe(TelegramBotSettings.KEY_ENABLED, false))
    }
    var telegramBotToken by remember {
        mutableStateOf(AppSettings.getStringSafe(TelegramBotSettings.KEY_TOKEN, ""))
    }
    var telegramAdminChatId by remember {
        mutableStateOf(AppSettings.getStringSafe(TelegramBotSettings.KEY_ADMIN_CHAT_ID, ""))
    }
    var telegramAdminUserId by remember {
        mutableStateOf(AppSettings.getStringSafe(TelegramBotSettings.KEY_ADMIN_USER_ID, ""))
    }

    fun saveSettings(settings: FilterSettings) {
        scope.launch { FilterSettingsManager.saveSettings(settings) }
    }

    fun normalizeSettings(settings: FilterSettings): FilterSettings {
        var entryMin = settings.entryMinMarketCap
        var entryMax = settings.entryMaxMarketCap
        if (entryMin > entryMax) { val tmp = entryMin; entryMin = entryMax; entryMax = tmp }
        val p1 = settings.exitStage1Pct.coerceIn(0.0, 100.0)
        val p2 = settings.exitStage2Pct.coerceIn(0.0, 100.0)
        val p3 = settings.exitStage3Pct.coerceIn(0.0, 100.0)
        val p4 = (100.0 - (p1 + p2 + p3)).coerceIn(0.0, 100.0)
        return settings.copy(
            entryMinMarketCap = entryMin,
            entryMaxMarketCap = entryMax,
            exitStage1Pct = p1,
            exitStage2Pct = p2,
            exitStage3Pct = p3,
            exitStage4Pct = p4,
            tradeUsdAmount = settings.tradeUsdAmount.roundToInt().toDouble()
        )
    }

    fun saveTelegramSettings() {
        AppSettings.putBoolean(TelegramBotSettings.KEY_ENABLED, telegramBotEnabled)
        AppSettings.putString(TelegramBotSettings.KEY_TOKEN, telegramBotToken.trim())
        AppSettings.putString(TelegramBotSettings.KEY_ADMIN_CHAT_ID, telegramAdminChatId.trim())
        AppSettings.putString(TelegramBotSettings.KEY_ADMIN_USER_ID, telegramAdminUserId.trim())
    }

    fun applySettings(updated: FilterSettings) {
        val normalized = normalizeSettings(updated)
        onSettingsChanged(normalized)
        saveSettings(normalized)
    }

    fun isStageCapOrderValid(s: FilterSettings) =
        s.exitStage1Cap <= s.exitStage2Cap &&
        s.exitStage2Cap <= s.exitStage3Cap &&
        s.exitStage3Cap <= s.exitStage4Cap

    val tabs = FilterTab.values()

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 36.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    saveTelegramSettings()
                    onClose()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Закрыть")
                }
                Text(
                    "Настройки",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = {
                    applySettings(FilterSettings())
                }) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = "Сбросить к умолчанию",
                        tint = TextSecondary
                    )
                }
                GradientButton(
                    text = "Сохранить",
                    onClick = {
                        saveTelegramSettings()
                        onClose()
                    },
                    icon = Icons.Default.Check,
                )
            }
        }

        // ── Tab row ──────────────────────────────────────────────────────────
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = { HorizontalDivider(color = DarkBorder) }
        ) {
            tabs.forEachIndexed { idx, tab ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = TextMuted
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(tab.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ── Tab content ──────────────────────────────────────────────────────
        when (tabs[selectedTab]) {
            FilterTab.ENTRY -> EntryTab(
                settings = currentSettings,
                onApply = ::applySettings
            )
            FilterTab.EXIT -> ExitTab(
                settings = currentSettings,
                onApply = ::applySettings,
                isCapOrderValid = ::isStageCapOrderValid
            )
            FilterTab.RISK -> RiskTab(
                settings = currentSettings,
                onApply = ::applySettings
            )
            FilterTab.AI -> AiTab(
                settings = currentSettings,
                onApply = ::applySettings
            )
            FilterTab.SETUP -> SetupTab(
                settings = currentSettings,
                onApply = ::applySettings,
                onTestSwap = onTestSwap,
                telegramBotEnabled = telegramBotEnabled,
                telegramBotToken = telegramBotToken,
                telegramAdminChatId = telegramAdminChatId,
                telegramAdminUserId = telegramAdminUserId,
                onTelegramBotEnabledChange = { telegramBotEnabled = it; saveTelegramSettings() },
                onTelegramBotTokenChange   = { telegramBotToken = it; saveTelegramSettings() },
                onTelegramChatIdChange     = { telegramAdminChatId = it; saveTelegramSettings() },
                onTelegramUserIdChange     = { telegramAdminUserId = it; saveTelegramSettings() },
                demoBalance = demoBalance,
                onResetDemo = { showResetDemoDialog = true }
            )
        }
    }

    // ── Reset Demo Dialog ────────────────────────────────────────────────────
    if (showResetDemoDialog) {
        AlertDialog(
            onDismissRequest = { showResetDemoDialog = false },
            title = { Text("Сбросить демо-счёт?") },
            text = { Text("Баланс демо-счёта будет сброшен до $10 000.") },
            confirmButton = {
                TextButton(onClick = {
                    DemoAccountManager.resetBalance()
                    demoBalance = DemoAccountManager.getBalance()
                    showResetDemoDialog = false
                }) {
                    Text("Сбросить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDemoDialog = false }) { Text("Отмена") }
            }
        )
    }
}

// ─── Tab: Entry ───────────────────────────────────────────────────────────────

@Composable
private fun EntryTab(settings: FilterSettings, onApply: (FilterSettings) -> Unit) {
    TabContent {
        // ── Token Age & Market Cap ───────────────────────────────────────────
        SettingCard("Возраст токена и Market Cap", Icons.Default.Schedule) {
            SliderRow(
                label = "Макс. возраст токена",
                valueText = "${settings.entryMaxAgeMinutes} мин",
                value = settings.entryMaxAgeMinutes.toFloat(),
                valueRange = 5f..120f,
                steps = 23,
                onValueChange = { onApply(settings.copy(entryMaxAgeMinutes = it.toInt())) }
            )
            HintText("Только токены моложе этого значения допускаются к входу")

            Spacer(modifier = Modifier.height(12.dp))
            SliderRow(
                label = "Market Cap (мин.)",
                valueText = "\$${settings.entryMinMarketCap.toInt()}",
                value = settings.entryMinMarketCap.toFloat().coerceIn(500f, 200_000f),
                valueRange = 500f..200_000f,
                steps = 399,
                onValueChange = {
                    val v = ((it / 500f).roundToInt() * 500).toDouble().coerceIn(500.0, 200_000.0)
                    onApply(settings.copy(entryMinMarketCap = v))
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "Market Cap (макс.)",
                valueText = "\$${settings.entryMaxMarketCap.toInt()}",
                value = settings.entryMaxMarketCap.toFloat().coerceIn(100_000f, 500_000f),
                valueRange = 100_000f..500_000f,
                steps = 79,
                onValueChange = { onApply(settings.copy(entryMaxMarketCap = it.toDouble())) }
            )
            HintText("Зона входа: \$${settings.entryMinMarketCap.toInt()} – \$${settings.entryMaxMarketCap.toInt()}")
        }

        // ── Liquidity & Volume ───────────────────────────────────────────────
        SettingCard("Ликвидность и объём", Icons.Default.WaterDrop) {
            SliderRow(
                label = "Мин. ликвидность",
                valueText = "\$${settings.entryMinLiquidity.toInt()}",
                value = settings.entryMinLiquidity.toFloat().coerceIn(100f, 20_000f),
                valueRange = 100f..20_000f,
                steps = 198,
                onValueChange = { onApply(settings.copy(entryMinLiquidity = it.toDouble())) }
            )
            HintText("Защита от низколиквидных токенов с большим спредом")

            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                label = "Фильтр объёма за 24ч",
                checked = settings.useVolumeH24,
                onCheckedChange = { onApply(settings.copy(useVolumeH24 = it)) }
            )
            SliderRow(
                label = "Мин. объём за 24ч",
                valueText = "\$${settings.entryMinVolume.toInt()}",
                value = settings.entryMinVolume.toFloat().coerceIn(100f, 250_000f),
                valueRange = 100f..250_000f,
                steps = 2498,
                enabled = settings.useVolumeH24,
                onValueChange = { onApply(settings.copy(entryMinVolume = it.toDouble())) }
            )

            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(
                label = "Фильтр объёма за 5 мин",
                checked = settings.useVolumeM5,
                onCheckedChange = { onApply(settings.copy(useVolumeM5 = it)) }
            )
            SliderRow(
                label = "Мин. объём за 5 мин",
                valueText = "\$${settings.entryMinVolumeM5.toInt()}",
                value = settings.entryMinVolumeM5.toFloat().coerceIn(100f, 250_000f),
                valueRange = 100f..250_000f,
                steps = 2498,
                enabled = settings.useVolumeM5,
                onValueChange = { onApply(settings.copy(entryMinVolumeM5 = it.toDouble())) }
            )
        }

        // ── Quality Filters ──────────────────────────────────────────────────
        SettingCard("Качество токена", Icons.Default.Verified) {
            if (settings.useAiAnalysis) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "ИИ проверяет: соцсети, сайт, давление покупок и моментум",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            } else {
                SwitchRow(
                    label = "Требовать соцсети (Telegram / X)",
                    checked = settings.requireSocials,
                    onCheckedChange = { onApply(settings.copy(requireSocials = it)) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                SwitchRow(
                    label = "Требовать сайт",
                    checked = settings.requireWebsite,
                    onCheckedChange = { onApply(settings.copy(requireWebsite = it)) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                SwitchRow(
                    label = "Соотношение покупок/продаж за 5м > порога",
                    subtitle = "Отсеивает токены без давления покупателей",
                    checked = settings.useMinBuysToSellsRatioM5,
                    onCheckedChange = { onApply(settings.copy(useMinBuysToSellsRatioM5 = it)) }
                )
                if (settings.useMinBuysToSellsRatioM5) {
                    SliderRow(
                        label = "Мин. Buys/Sells (5м)",
                        valueText = "${(settings.minBuysToSellsRatioM5 * 10).roundToInt() / 10.0}×",
                        value = settings.minBuysToSellsRatioM5.toFloat().coerceIn(0.5f, 5f),
                        valueRange = 0.5f..5f,
                        steps = 44,
                        onValueChange = { onApply(settings.copy(minBuysToSellsRatioM5 = it.toDouble())) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                SwitchRow(
                    label = "Мин. изменение цены за 5 мин",
                    subtitle = "Детектор памpa — вход только на моментуме",
                    checked = settings.useMinPriceChangeM5Pct,
                    onCheckedChange = { onApply(settings.copy(useMinPriceChangeM5Pct = it)) }
                )
                if (settings.useMinPriceChangeM5Pct) {
                    SliderRow(
                        label = "Мин. рост цены за 5 мин",
                        valueText = "+${settings.minPriceChangeM5Pct.toInt()}%",
                        value = settings.minPriceChangeM5Pct.toFloat().coerceIn(0f, 500f),
                        valueRange = 0f..500f,
                        steps = 49,
                        onValueChange = { onApply(settings.copy(minPriceChangeM5Pct = it.toDouble())) }
                    )
                }
            }
        }

        // ── Scoring ──────────────────────────────────────────────────────────
        SettingCard("Скоринг", Icons.Default.Star) {
            SliderRow(
                label = "Мин. балл для допуска",
                valueText = "${settings.minScoreAccept} / 100",
                value = settings.minScoreAccept.toFloat().coerceIn(0f, 100f),
                valueRange = 0f..100f,
                steps = 19,
                onValueChange = { onApply(settings.copy(minScoreAccept = it.toInt())) }
            )
            HintText("Внутренний скоринг 0–100. Токены ниже порога пропускаются.")

            Spacer(modifier = Modifier.height(12.dp))
            SliderRow(
                label = "Макс. токенов за цикл поиска",
                valueText = "${settings.maxTokensPerTick}",
                value = settings.maxTokensPerTick.toFloat().coerceIn(1f, 10f),
                valueRange = 1f..10f,
                steps = 8,
                onValueChange = { onApply(settings.copy(maxTokensPerTick = it.toInt())) }
            )
        }

        // ── Dars — вход по методике ──────────────────────────────────────────
        SettingCard("Dars — вход по методике", Icons.Default.CandlestickChart) {
            SwitchRow(
                label = "Включить вход по методике «Dars»",
                subtitle = "Свечной прайс-экшн на свечах OHLCV (GeckoTerminal, Solana)",
                checked = settings.darsEnabled,
                onCheckedChange = { onApply(settings.copy(darsEnabled = it)) }
            )

            if (settings.darsEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                SwitchRow(
                    label = "Режим «Только Dars»",
                    subtitle = "Старые momentum-фильтры смягчаются, вход решает методика",
                    checked = settings.darsOnlyMode,
                    onCheckedChange = { onApply(settings.copy(darsOnlyMode = it)) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                HintText("Сетапы")
                SwitchRow(
                    label = "Импульс / коррекция + доминирование",
                    subtitle = "Урок 1: сильный импульс, слабая коррекция, одна сторона",
                    checked = settings.darsUseImpulseCorrection,
                    onCheckedChange = { onApply(settings.copy(darsUseImpulseCorrection = it)) }
                )
                SwitchRow(
                    label = "Тренд старшего ТФ + уровни",
                    subtitle = "Урок 2: торговать только по тренду, не у сопротивления",
                    checked = settings.darsUseTrendLevels,
                    onCheckedChange = { onApply(settings.copy(darsUseTrendLevels = it)) }
                )
                SwitchRow(
                    label = "Ложный пробой",
                    subtitle = "Урок 3",
                    checked = settings.darsUseFalseBreakout,
                    onCheckedChange = { onApply(settings.copy(darsUseFalseBreakout = it)) }
                )
                SwitchRow(
                    label = "Треугольник",
                    subtitle = "Урок 5",
                    checked = settings.darsUseTriangle,
                    onCheckedChange = { onApply(settings.copy(darsUseTriangle = it)) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                ChoiceRow(
                    label = "Старший ТФ (тренд)",
                    options = darsTfLabels,
                    selected = settings.darsHigherTf,
                    onSelect = { tf ->
                        onApply(settings.copy(darsHigherTf = tf, darsHigherTfAggregate = darsDefaultAgg(tf)))
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ChoiceRow(
                    label = "Агрегация старшего ТФ",
                    options = darsAggOptions(settings.darsHigherTf).map { "$it" to it },
                    selected = settings.darsHigherTfAggregate,
                    onSelect = { onApply(settings.copy(darsHigherTfAggregate = it)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ChoiceRow(
                    label = "Рабочий ТФ (вход)",
                    options = darsTfLabels,
                    selected = settings.darsEntryTf,
                    onSelect = { tf ->
                        onApply(settings.copy(darsEntryTf = tf, darsEntryTfAggregate = darsDefaultAgg(tf)))
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ChoiceRow(
                    label = "Агрегация рабочего ТФ",
                    options = darsAggOptions(settings.darsEntryTf).map { "$it" to it },
                    selected = settings.darsEntryTfAggregate,
                    onSelect = { onApply(settings.copy(darsEntryTfAggregate = it)) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                SwitchRow(
                    label = "Требовать восходящий тренд старшего ТФ",
                    checked = settings.darsRequireHtfTrend,
                    onCheckedChange = { onApply(settings.copy(darsRequireHtfTrend = it)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SliderRow(
                    label = "Порог пивота (ZigZag)",
                    valueText = "${(settings.darsSwingPivotPct * 10).roundToInt() / 10.0}%",
                    value = settings.darsSwingPivotPct.toFloat().coerceIn(0.3f, 5f),
                    valueRange = 0.3f..5f,
                    steps = 46,
                    onValueChange = { onApply(settings.copy(darsSwingPivotPct = it.toDouble())) }
                )
                SliderRow(
                    label = "Доминирование импульса",
                    valueText = "${(settings.darsDominanceRatio * 10).roundToInt() / 10.0}×",
                    value = settings.darsDominanceRatio.toFloat().coerceIn(1f, 4f),
                    valueRange = 1f..4f,
                    steps = 29,
                    onValueChange = { onApply(settings.copy(darsDominanceRatio = it.toDouble())) }
                )
                SliderRow(
                    label = "Мин. длина коррекции",
                    valueText = "${settings.darsMinCorrectionLenPct.toInt()}% от импульса",
                    value = settings.darsMinCorrectionLenPct.toFloat().coerceIn(0f, 100f),
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChange = { onApply(settings.copy(darsMinCorrectionLenPct = it.toDouble())) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SwitchRow(
                    label = "Не покупать у сопротивления",
                    subtitle = "Урок 4",
                    checked = settings.darsRejectAtResistance,
                    onCheckedChange = { onApply(settings.copy(darsRejectAtResistance = it)) }
                )
                if (settings.darsRejectAtResistance) {
                    SliderRow(
                        label = "Близость к сопротивлению",
                        valueText = "${(settings.darsResistanceProximityPct * 10).roundToInt() / 10.0}%",
                        value = settings.darsResistanceProximityPct.toFloat().coerceIn(0.2f, 10f),
                        valueRange = 0.2f..10f,
                        steps = 48,
                        onValueChange = { onApply(settings.copy(darsResistanceProximityPct = it.toDouble())) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SliderRow(
                    label = "Мин. число ног для анализа",
                    valueText = "${settings.darsMinLegs}",
                    value = settings.darsMinLegs.toFloat().coerceIn(2f, 8f),
                    valueRange = 2f..8f,
                    steps = 5,
                    onValueChange = { onApply(settings.copy(darsMinLegs = it.toInt())) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SwitchRow(
                    label = "Отклонять при нехватке свечей",
                    subtitle = "fail-closed: без данных — пропускать монету",
                    checked = settings.darsFailClosed,
                    onCheckedChange = { onApply(settings.copy(darsFailClosed = it)) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                SwitchRow(
                    label = "Окно новостей: блокировать входы",
                    subtitle = "Урок 4: не торговать в заданный интервал UTC",
                    checked = settings.darsNewsBlackoutEnabled,
                    onCheckedChange = { onApply(settings.copy(darsNewsBlackoutEnabled = it)) }
                )
                if (settings.darsNewsBlackoutEnabled) {
                    SliderRow(
                        label = "Начало окна",
                        valueText = utcHhMm(settings.darsBlackoutStartUtcMin),
                        value = settings.darsBlackoutStartUtcMin.toFloat().coerceIn(0f, 1439f),
                        valueRange = 0f..1439f,
                        steps = 95,
                        onValueChange = {
                            val v = (it / 15f).roundToInt() * 15
                            onApply(settings.copy(darsBlackoutStartUtcMin = v.coerceIn(0, 1439)))
                        }
                    )
                    SliderRow(
                        label = "Конец окна",
                        valueText = utcHhMm(settings.darsBlackoutEndUtcMin),
                        value = settings.darsBlackoutEndUtcMin.toFloat().coerceIn(0f, 1439f),
                        valueRange = 0f..1439f,
                        steps = 95,
                        onValueChange = {
                            val v = (it / 15f).roundToInt() * 15
                            onApply(settings.copy(darsBlackoutEndUtcMin = v.coerceIn(0, 1439)))
                        }
                    )
                }

                HintText("Новые монеты часто без истории свечей — тогда вход по Dars пропускается (входов будет меньше). Данные: GeckoTerminal OHLCV по пулам Solana.")
            }
        }
    }
}

// ─── Tab: Exit ────────────────────────────────────────────────────────────────

@Composable
private fun ExitTab(
    settings: FilterSettings,
    onApply: (FilterSettings) -> Unit,
    isCapOrderValid: (FilterSettings) -> Boolean
) {
    TabContent {
        // ── Strategy selector ────────────────────────────────────────────────
        SettingCard("Стратегия выхода", Icons.Default.ExitToApp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.exitStrategy == "stages",
                    onClick  = { onApply(settings.copy(exitStrategy = "stages")) },
                    label    = { Text("Этапы (4 уровня)") }
                )
                FilterChip(
                    selected = settings.exitStrategy == "aggressive",
                    onClick  = { onApply(settings.copy(exitStrategy = "aggressive")) },
                    label    = { Text("Агрессивный") }
                )
            }
        }

        // ── Aggressive ───────────────────────────────────────────────────────
        if (settings.exitStrategy == "aggressive") {
            SettingCard("Агрессивный режим", Icons.Default.Bolt) {
                HintText("Фиксируем часть позиции при достижении цели, остаток удерживается с трейлингом.")
                Spacer(modifier = Modifier.height(10.dp))
                SliderRow(
                    label = "Тейк-профит при",
                    valueText = "+${settings.aggressiveTakeProfitPct.toInt()}%",
                    value = settings.aggressiveTakeProfitPct.toFloat().coerceIn(20f, 300f),
                    valueRange = 20f..300f,
                    steps = 27,
                    onValueChange = { onApply(settings.copy(aggressiveTakeProfitPct = it.toDouble())) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SliderRow(
                    label = "Продать % на цели",
                    valueText = "${settings.aggressiveSellPct.toInt()}%",
                    value = settings.aggressiveSellPct.toFloat().coerceIn(10f, 100f),
                    valueRange = 10f..100f,
                    steps = 9,
                    onValueChange = { onApply(settings.copy(aggressiveSellPct = it.toDouble())) }
                )
                HintText("Оставшиеся ${(100 - settings.aggressiveSellPct.toInt())}% удерживаются с трейлинг-стопом")
            }
        }

        // ── Stages ───────────────────────────────────────────────────────────
        if (settings.exitStrategy == "stages") {
            if (!isCapOrderValid(settings)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Уровни Market Cap должны идти по возрастанию (Этап1 ≤ Этап2 ≤ Этап3 ≤ Этап4)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            listOf(
                Triple("Этап 1", settings.exitStage1Cap, settings.exitStage1Pct),
                Triple("Этап 2", settings.exitStage2Cap, settings.exitStage2Pct),
                Triple("Этап 3", settings.exitStage3Cap, settings.exitStage3Pct),
                Triple("Этап 4", settings.exitStage4Cap, settings.exitStage4Pct)
            ).forEachIndexed { idx, (label, cap, pct) ->
                val capRange = when (idx) {
                    0 -> 40_000f..250_000f;  1 -> 50_000f..350_000f
                    2 -> 60_000f..450_000f;  else -> 70_000f..550_000f
                }
                SettingCard(label, Icons.Default.Flag) {
                    SliderRow(
                        label = "Цель Market Cap",
                        valueText = "\$${cap.toInt()}",
                        value = cap.toFloat().coerceIn(capRange.start, capRange.endInclusive),
                        valueRange = capRange,
                        steps = ((capRange.endInclusive - capRange.start) / 5000).toInt() - 1,
                        onValueChange = { v ->
                            onApply(when (idx) {
                                0 -> settings.copy(exitStage1Cap = v.toDouble())
                                1 -> settings.copy(exitStage2Cap = v.toDouble())
                                2 -> settings.copy(exitStage3Cap = v.toDouble())
                                else -> settings.copy(exitStage4Cap = v.toDouble())
                            })
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SliderRow(
                        label = "Продать % позиции",
                        valueText = "${pct.toInt()}%",
                        value = pct.toFloat().coerceIn(5f, 50f),
                        valueRange = 5f..50f,
                        steps = 45,
                        onValueChange = { v ->
                            onApply(when (idx) {
                                0 -> settings.copy(exitStage1Pct = v.toDouble())
                                1 -> settings.copy(exitStage2Pct = v.toDouble())
                                2 -> settings.copy(exitStage3Pct = v.toDouble())
                                else -> settings.copy(exitStage4Pct = v.toDouble())
                            })
                        }
                    )
                }
            }

            // Stage totals hint
            val totalPct = (settings.exitStage1Pct + settings.exitStage2Pct +
                    settings.exitStage3Pct + settings.exitStage4Pct).toInt()
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (totalPct == 100)
                    MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (totalPct == 100) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (totalPct == 100) MaterialTheme.colorScheme.secondary
                               else MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Итого: $totalPct% (${
                            if (totalPct == 100) "отлично — позиция полностью закрыта"
                            else if (totalPct < 100) "оставшиеся ${100 - totalPct}% остаются открытыми"
                            else "больше 100% — уменьшите проценты этапов"
                        })",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (totalPct == 100) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // ── Time-based exit ──────────────────────────────────────────────────
        SettingCard("Выход по времени", Icons.Default.Timer) {
            HintText("Принудительный выход если первая цель не достигнута за отведённое время.")
            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(
                label = if (settings.useTimeBasedExit) "Выход по времени ВКЛ" else "Выход по времени ВЫКЛ",
                subtitle = if (settings.useTimeBasedExit)
                    "Выход через ${settings.timeBasedExitMinutes} мин без достижения Этапа 1 / TP"
                else null,
                checked = settings.useTimeBasedExit,
                onCheckedChange = { onApply(settings.copy(useTimeBasedExit = it)) }
            )
            if (settings.useTimeBasedExit) {
                Spacer(modifier = Modifier.height(8.dp))
                SliderRow(
                    label = "Тайм-аут",
                    valueText = "${settings.timeBasedExitMinutes} мин",
                    value = settings.timeBasedExitMinutes.toFloat().coerceIn(5f, 120f),
                    valueRange = 5f..120f,
                    steps = 22,
                    onValueChange = { onApply(settings.copy(timeBasedExitMinutes = it.toInt())) }
                )
            }
        }

        // ── Trading hours ────────────────────────────────────────────────────
        SettingCard("Часы торговли (UTC)", Icons.Default.AccessTime) {
            HintText("Поиск новых токенов только в активные часы. Открытые позиции управляются всегда.")
            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(
                label = if (settings.tradingHoursEnabled) "Часы торговли ВКЛ" else "Часы торговли ВЫКЛ",
                subtitle = if (settings.tradingHoursEnabled)
                    "Активно: ${settings.tradingHoursStartUtcHour}:00 – ${settings.tradingHoursEndUtcHour}:00 UTC"
                else null,
                checked = settings.tradingHoursEnabled,
                onCheckedChange = { onApply(settings.copy(tradingHoursEnabled = it)) }
            )
            if (settings.tradingHoursEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                SliderRow(
                    label = "Начало (час UTC)",
                    valueText = "${settings.tradingHoursStartUtcHour}:00 UTC",
                    value = settings.tradingHoursStartUtcHour.toFloat().coerceIn(0f, 23f),
                    valueRange = 0f..23f,
                    steps = 22,
                    onValueChange = { onApply(settings.copy(tradingHoursStartUtcHour = it.toInt())) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SliderRow(
                    label = "Конец (час UTC)",
                    valueText = "${settings.tradingHoursEndUtcHour}:00 UTC",
                    value = settings.tradingHoursEndUtcHour.toFloat().coerceIn(0f, 23f),
                    valueRange = 0f..23f,
                    steps = 22,
                    onValueChange = { onApply(settings.copy(tradingHoursEndUtcHour = it.toInt())) }
                )
                val s = settings.tradingHoursStartUtcHour
                val e = settings.tradingHoursEndUtcHour
                HintText(
                    "EST: ${(s - 4 + 24) % 24}:00 – ${(e - 4 + 24) % 24}:00  •  " +
                    "MSK: ${(s + 3) % 24}:00 – ${(e + 3) % 24}:00"
                )
            }
        }
    }
}

// ─── Tab: Risk ────────────────────────────────────────────────────────────────

@Composable
private fun RiskTab(settings: FilterSettings, onApply: (FilterSettings) -> Unit) {
    TabContent {
        // ── Stop Loss ────────────────────────────────────────────────────────
        SettingCard("Стоп-лосс", Icons.Default.GppBad) {
            HintText("Автоматический выход при падении позиции на указанный процент.")
            Spacer(modifier = Modifier.height(10.dp))
            SliderRow(
                label = "Стоп-лосс по цене",
                valueText = "-${settings.stopLossByPricePct.toInt()}%",
                value = settings.stopLossByPricePct.toFloat().coerceIn(5f, 90f),
                valueRange = 5f..90f,
                steps = 16,
                onValueChange = { onApply(settings.copy(stopLossByPricePct = it.toDouble())) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "Стоп-лосс по Market Cap",
                valueText = "-${settings.stopLossByMarketCapPct.toInt()}%",
                value = settings.stopLossByMarketCapPct.toFloat().coerceIn(5f, 90f),
                valueRange = 5f..90f,
                steps = 16,
                onValueChange = { onApply(settings.copy(stopLossByMarketCapPct = it.toDouble())) }
            )
        }

        // ── Trailing & Pullback ──────────────────────────────────────────────
        SettingCard("Трейлинг-стоп и откат", Icons.Default.TrendingDown) {
            HintText("Активируется после первой частичной фиксации. Защищает прибыль при росте.")
            Spacer(modifier = Modifier.height(10.dp))
            SliderRow(
                label = "Трейлинг-стоп от пика MC",
                valueText = "-${settings.trailingStopPct.toInt()}%",
                value = settings.trailingStopPct.toFloat().coerceIn(5f, 90f),
                valueRange = 5f..90f,
                steps = 16,
                onValueChange = { onApply(settings.copy(trailingStopPct = it.toDouble())) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "Откат от пика цены (после этапа)",
                valueText = "-${settings.stagePullbackPct.toInt()}%",
                value = settings.stagePullbackPct.toFloat().coerceIn(5f, 90f),
                valueRange = 5f..90f,
                steps = 16,
                onValueChange = { onApply(settings.copy(stagePullbackPct = it.toDouble())) }
            )
        }

        // ── Portfolio Limits ─────────────────────────────────────────────────
        SettingCard("Лимиты портфеля", Icons.Default.AccountBalance) {
            HintText("Дневные ограничители. Новые входы блокируются при достижении лимитов.")
            Spacer(modifier = Modifier.height(10.dp))
            SliderRow(
                label = "Макс. дневной убыток",
                valueText = "\$${settings.maxDailyLossUsd.toInt()}",
                value = settings.maxDailyLossUsd.toFloat().coerceIn(10f, 10_000f),
                valueRange = 10f..10_000f,
                steps = 99,
                onValueChange = { onApply(settings.copy(maxDailyLossUsd = it.toDouble())) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "Макс. суммарная открытая позиция",
                valueText = "\$${settings.maxTotalExposureUsd.toInt()}",
                value = settings.maxTotalExposureUsd.toFloat().coerceIn(10f, 20_000f),
                valueRange = 10f..20_000f,
                steps = 199,
                onValueChange = { onApply(settings.copy(maxTotalExposureUsd = it.toDouble())) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "Макс. просадка от пика депозита",
                valueText = "${settings.maxDrawdownPct.toInt()}%",
                value = settings.maxDrawdownPct.toFloat().coerceIn(5f, 80f),
                valueRange = 5f..80f,
                steps = 14,
                onValueChange = { onApply(settings.copy(maxDrawdownPct = it.toDouble())) }
            )
        }

        // ── Consecutive Losses ───────────────────────────────────────────────
        SettingCard("Блокировка при серии убытков", Icons.Default.Block) {
            SliderRow(
                label = "Макс. убытков подряд",
                valueText = "${settings.maxConsecutiveLosses}",
                value = settings.maxConsecutiveLosses.toFloat().coerceIn(1f, 20f),
                valueRange = 1f..20f,
                steps = 18,
                onValueChange = { onApply(settings.copy(maxConsecutiveLosses = it.toInt())) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "Пауза после срабатывания",
                valueText = "${settings.cooldownMinutesAfterLossLimit} мин",
                value = settings.cooldownMinutesAfterLossLimit.toFloat().coerceIn(0f, 240f),
                valueRange = 0f..240f,
                steps = 23,
                onValueChange = { onApply(settings.copy(cooldownMinutesAfterLossLimit = it.toInt())) }
            )
            HintText("После ${settings.maxConsecutiveLosses} убытков подряд новые входы останавливаются на ${settings.cooldownMinutesAfterLossLimit} мин")
        }
    }
}

// ─── Tab: AI ──────────────────────────────────────────────────────────────────

@Composable
private fun AiTab(settings: FilterSettings, onApply: (FilterSettings) -> Unit) {
    var showModelDialog by remember { mutableStateOf(false) }

    TabContent {
        // ── Enable AI ────────────────────────────────────────────────────────
        SettingCard("ИИ-анализ токенов", Icons.Default.AutoAwesome) {
            SwitchRow(
                label = "Включить ИИ-анализ",
                subtitle = "Анализирует токены на риск rug pull, фазу моментума и оптимальный вход",
                checked = settings.useAiAnalysis,
                onCheckedChange = { onApply(settings.copy(useAiAnalysis = it)) }
            )
        }

        if (settings.useAiAnalysis) {
            // ── Provider ─────────────────────────────────────────────────────
            SettingCard("Провайдер", Icons.Default.Cloud) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.aiProvider == "groq",
                        onClick  = { onApply(settings.copy(aiProvider = "groq", aiModel = "llama-3.1-8b-instant")) },
                        label    = { Text("Groq") }
                    )
                    FilterChip(
                        selected = settings.aiProvider == "claude",
                        onClick  = { onApply(settings.copy(aiProvider = "claude", aiModel = "claude-3-5-sonnet-20241022")) },
                        label    = { Text("Claude") }
                    )
                    FilterChip(
                        selected = settings.aiProvider == "openai",
                        onClick  = { onApply(settings.copy(aiProvider = "openai", aiModel = "gpt-4o-mini")) },
                        label    = { Text("OpenAI") }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (settings.aiProvider == "groq")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = when (settings.aiProvider) {
                            "groq"   -> "Есть бесплатный план • $0.05 / 1M токенов • 560 t/s • console.groq.com"
                            "claude" -> "$3 – $15 / 1M tokens • console.anthropic.com"
                            "openai" -> "$0.15 – $5 / 1M tokens • platform.openai.com"
                            else     -> ""
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (settings.aiProvider == "groq")
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                var showKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = settings.aiApiKey,
                    onValueChange = { onApply(settings.copy(aiApiKey = it.trim())) },
                    label = { Text("${settings.aiProvider.uppercase()} API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )
            }

            // ── Model selector ────────────────────────────────────────────────
            SettingCard("Модель ИИ", Icons.Default.Psychology) {
                OutlinedButton(
                    onClick = { showModelDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Выбранная модель", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text(
                            settings.aiModel.ifEmpty { "Нажмите для выбора…" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }

            // ── Thresholds ───────────────────────────────────────────────────
            SettingCard("Пороги и безопасность", Icons.Default.Shield) {
                SliderRow(
                    label = "Мин. балл ИИ для входа",
                    valueText = "${settings.minAiScore} / 100",
                    value = settings.minAiScore.toFloat().coerceIn(0f, 100f),
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChange = { onApply(settings.copy(minAiScore = it.toInt())) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Макс. риск rug pull",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("LOW", "MEDIUM", "HIGH").forEach { risk ->
                        FilterChip(
                            selected = settings.maxAiRugRisk == risk,
                            onClick  = { onApply(settings.copy(maxAiRugRisk = risk)) },
                            label    = { Text(risk) }
                        )
                    }
                }
                HintText("Токены с риском выше этого уровня отклоняются")
                Spacer(modifier = Modifier.height(12.dp))
                SwitchRow(
                    label = "Блокировать при ошибке ИИ",
                    subtitle = "Если ВКЛ: токен отклоняется при ошибке запроса к ИИ",
                    checked = settings.aiFailClosed,
                    onCheckedChange = { onApply(settings.copy(aiFailClosed = it)) }
                )
            }
        }
    }

    // ── Model dialog ─────────────────────────────────────────────────────────
    if (showModelDialog) {
        val models = when (settings.aiProvider) {
            "groq"   -> listOf(
                "llama-3.1-8b-instant"       to "Llama 3.1 8B — быстрый, очень дёшево",
                "llama-3.3-70b-versatile"    to "Llama 3.3 70B — лучше качество",
                "openai/gpt-oss-120b"        to "GPT-OSS 120B — максимальная мощность",
                "openai/gpt-oss-20b"         to "GPT-OSS 20B — баланс скорости и качества"
            )
            "claude" -> listOf(
                "claude-3-5-sonnet-20241022" to "Claude 3.5 Sonnet — лучшее качество",
                "claude-3-5-haiku-20241022"  to "Claude 3.5 Haiku — быстрый",
                "claude-3-opus-20240229"     to "Claude 3 Opus — устаревший"
            )
            "openai" -> listOf(
                "gpt-4o-mini"    to "GPT-4o Mini — дёшево",
                "gpt-4o"         to "GPT-4o — мощный",
                "gpt-4-turbo"    to "GPT-4 Turbo — быстрый"
            )
            else -> emptyList()
        }
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Выбор модели ИИ") },
            text = {
                LazyColumn {
                    items(models) { (id, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onApply(settings.copy(aiModel = id)); showModelDialog = false }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.aiModel == id,
                                onClick  = { onApply(settings.copy(aiModel = id)); showModelDialog = false }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(name, style = MaterialTheme.typography.labelMedium, color = TextMuted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("Закрыть") }
            }
        )
    }
}

// ─── Tab: Setup ───────────────────────────────────────────────────────────────

@Composable
private fun SetupTab(
    settings: FilterSettings,
    onApply: (FilterSettings) -> Unit,
    onTestSwap: suspend () -> String,
    telegramBotEnabled: Boolean,
    telegramBotToken: String,
    telegramAdminChatId: String,
    telegramAdminUserId: String,
    onTelegramBotEnabledChange: (Boolean) -> Unit,
    onTelegramBotTokenChange: (String) -> Unit,
    onTelegramChatIdChange: (String) -> Unit,
    onTelegramUserIdChange: (String) -> Unit,
    demoBalance: Double,
    onResetDemo: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showSeed by remember { mutableStateOf(false) }
    var showBotToken by remember { mutableStateOf(false) }
    var testSwapStatus by remember { mutableStateOf("") }

    val publicKey = remember(settings.seedPhrase) {
        if (settings.seedPhrase.isBlank()) "" else {
            try { createSignerFromSeedPhrase(settings.seedPhrase.trim()).publicKeyBase58() }
            catch (_: Exception) { "invalid" }
        }
    }
    val seedValid = remember(settings.seedPhrase, publicKey) {
        val words = settings.seedPhrase.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        (words.size == 12 || words.size == 24) && publicKey.isNotBlank() && publicKey != "invalid"
    }

    TabContent {
        // ── Jupiter Trading ──────────────────────────────────────────────────
        SettingCard("Jupiter Trading", Icons.Default.CurrencyExchange) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "Автоподпись рискованна. Хранение сид-фразы внутри приложения небезопасно.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                label = if (settings.jupiterEnabled) "Реальная торговля ВКЛ" else "Реальная торговля ВЫКЛ (Демо)",
                subtitle = if (settings.jupiterEnabled)
                    "Реальные свапы через Jupiter Aggregator"
                else "Все сделки симулируются — безопасно для экспериментов",
                checked = settings.jupiterEnabled,
                onCheckedChange = { onApply(settings.copy(jupiterEnabled = it)) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = settings.seedPhrase,
                onValueChange = { onApply(settings.copy(seedPhrase = it)) },
                label = { Text("Сид-фраза (12 или 24 слова)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                visualTransformation = if (showSeed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSeed = !showSeed }) {
                        Icon(
                            if (showSeed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            when {
                settings.seedPhrase.isBlank() -> Unit
                seedValid -> HintText("Кошелёк: $publicKey")
                else -> Text(
                    "Неверная сид-фраза",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.jupiterApiKey,
                onValueChange = { onApply(settings.copy(jupiterApiKey = it.trim())) },
                label = { Text("Jupiter API ключ (необязательно)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            SliderRow(
                label = "Сумма входа",
                valueText = "\$${settings.tradeUsdAmount.toInt()}",
                value = settings.tradeUsdAmount.toFloat().coerceIn(1f, 20f),
                valueRange = 1f..20f,
                steps = 19,
                onValueChange = { onApply(settings.copy(tradeUsdAmount = it.roundToInt().toDouble())) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SliderRow(
                label = "Проскальзывание",
                valueText = "${settings.slippageBps / 100.0}%",
                value = settings.slippageBps.toFloat().coerceIn(10f, 200f),
                valueRange = 10f..200f,
                steps = 19,
                onValueChange = { onApply(settings.copy(slippageBps = it.toInt())) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch { testSwapStatus = onTestSwap() }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                enabled = settings.jupiterEnabled && seedValid
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Тестовый свап")
            }
            if (testSwapStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                HintText(testSwapStatus)
            }
        }

        // ── Telegram Bot ─────────────────────────────────────────────────────
        SettingCard("Telegram-бот", Icons.Default.Send) {
            HintText("Управление через Telegram. Работает в режиме JVM и Android-сервиса.")
            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(
                label = if (telegramBotEnabled) "Telegram-бот ВКЛ" else "Telegram-бот ВЫКЛ",
                checked = telegramBotEnabled,
                onCheckedChange = onTelegramBotEnabledChange
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = telegramBotToken,
                onValueChange = onTelegramBotTokenChange,
                label = { Text("Токен бота") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showBotToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showBotToken = !showBotToken }) {
                        Icon(
                            if (showBotToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = telegramAdminChatId,
                onValueChange = onTelegramChatIdChange,
                label = { Text("Chat ID администратора") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = telegramAdminUserId,
                onValueChange = onTelegramUserIdChange,
                label = { Text("User ID администратора (необязательно)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // ── Demo Account ─────────────────────────────────────────────────────
        SettingCard("Демо-счёт", Icons.Default.Savings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Текущий баланс", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text(
                        "\$${formatDemoBalance(demoBalance)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Размер сделки: \$${DemoAccountManager.DEMO_TRADE_AMOUNT.toInt()} / вход",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onResetDemo,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Сбросить баланс до \$10 000")
            }
        }

        // ── General ──────────────────────────────────────────────────────────
        SettingCard("Общие", Icons.Default.Tune) {
            SliderRow(
                label = "Макс. токенов в мониторинге",
                valueText = "${settings.maxTokensToMonitor}",
                value = settings.maxTokensToMonitor.toFloat().coerceIn(1f, 50f),
                valueRange = 1f..50f,
                steps = 49,
                onValueChange = { onApply(settings.copy(maxTokensToMonitor = it.toInt())) }
            )
            HintText("Больше токенов = больше нагрузки. Рекомендуется: 4–10.")

            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                label = "Исключать подозрительные токены",
                subtitle = "Фильтрует потенциальные скамы и rug pull",
                checked = settings.excludeRugPull,
                onCheckedChange = { onApply(settings.copy(excludeRugPull = it)) }
            )
        }

        // ── Blockchains ───────────────────────────────────────────────────────
        SettingCard("Блокчейны", Icons.Default.Link) {
            val chains = listOf("solana" to "Solana", "ethereum" to "Ethereum", "bsc" to "BNB Chain")
            chains.forEach { (id, name) ->
                val selected = settings.chains.contains(id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val updated = if (selected) settings.chains - id else settings.chains + id
                            onApply(settings.copy(chains = updated))
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { checked ->
                            val updated = if (checked) settings.chains + id else settings.chains - id
                            onApply(settings.copy(chains = updated))
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (selected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onApply(settings.copy(chains = chains.map { it.first })) }) {
                    Text("Все", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onApply(settings.copy(chains = emptyList())) }) {
                    Text("Нет", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ─── Shared Composables ───────────────────────────────────────────────────────

@Composable
private fun TabContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SettingCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = SolGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            valueText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) TextPrimary else TextMuted
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = TextMuted)
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = TextMuted
    )
}

// ─── Dars helpers ──────────────────────────────────────────────────────────────

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (lbl, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(lbl) }
                )
            }
        }
    }
}

private val darsTfLabels = listOf("Минуты" to "minute", "Часы" to "hour", "Дни" to "day")

private fun darsAggOptions(tf: String): List<Int> = when (tf) {
    "minute" -> listOf(1, 5, 15)
    "hour" -> listOf(1, 4, 12)
    else -> listOf(1)
}

private fun darsDefaultAgg(tf: String): Int = when (tf) {
    "minute" -> 5
    "hour" -> 1
    else -> 1
}

private fun utcHhMm(min: Int): String {
    val m = ((min % 1440) + 1440) % 1440
    val h = m / 60
    val mm = m % 60
    val hs = if (h < 10) "0$h" else "$h"
    val ms = if (mm < 10) "0$mm" else "$mm"
    return "$hs:$ms UTC"
}
