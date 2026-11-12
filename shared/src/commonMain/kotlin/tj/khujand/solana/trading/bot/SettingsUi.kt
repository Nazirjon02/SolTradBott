@file:OptIn(ExperimentalLayoutApi::class)

package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tj.khujand.solana.trading.bot.core.engine.EXIT_BLOCKED_AFTER
import tj.khujand.solana.trading.bot.data.ProtectionSettings
import tj.khujand.solana.trading.bot.exchange.dex.DEFAULT_KEY_MONTHLY_QUOTA
import tj.khujand.solana.trading.bot.exchange.dex.GeckoTerminalApi
import tj.khujand.solana.trading.bot.util.formatLocalTime
import tj.khujand.solana.trading.bot.util.plural

// ─── Настройки ────────────────────────────────────────────────────────────────
//
// Четыре раздела вместо одной длинной ленты. Режим счёта и тумблеры автоматики применяются сразу; поля защиты и
// подключений — черновик: панель «Сохранить» появляется, только когда есть несохранённые правки, а неверное
// число не даёт сохранить. Секреты (seed-фраза, токены, ключ в адресе RPC) скрыты, пока их не открыть глазом.

/** Режим счёта: DEMO — по цене графика, SHADOW — DEMO по котировкам Jupiter, REAL — настоящие свопы. */
enum class AccountMode(val label: String, val color: Color) {
    DEMO("DEMO", Purple),
    SHADOW("SHADOW", Blue),
    REAL("REAL", Amber),
}

internal fun accountMode(demoMode: Boolean, shadowMode: Boolean): AccountMode = when {
    !demoMode -> AccountMode.REAL
    shadowMode -> AccountMode.SHADOW
    else -> AccountMode.DEMO
}

/** Разделы настроек. */
enum class SettingsCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    TRADING("Торговля", "режим счёта и автоматика", Icons.Rounded.Tune),
    PROTECTION("Защита", "проверка монеты и лимиты", Icons.Rounded.Shield),
    CONNECTIONS("Подключения", "кошелёк, данные, Telegram", Icons.Rounded.Hub),
    MAINTENANCE("Данные", "забеги и сброс", Icons.Rounded.Storage),
}

/** Метка раздела настроек — по ней раздел находят тесты интерфейса. */
internal fun settingsCategoryTag(c: SettingsCategory): String = "settings-${c.name}"

/** Ширина колонки настроек: поля шире этого читать и заполнять неудобно. */
private val SettingsMaxWidth = 720.dp

/**
 * Экран настроек.
 *
 * @param settings сохранённые настройки; [draft] — черновик правок (живёт у вызывающего, чтобы переход на другую
 *   вкладку не терял набранное). Черновик отличается от сохранённого — внизу панель «Сохранить / Отменить».
 * @param formVersion растёт, когда черновик заменили целиком (сохранили, отменили, перечитали) — поля сбрасывают
 *   свой текст.
 */
@Composable
fun SettingsScreen(
    settings: DrxSettings,
    draft: DrxSettings = settings,
    formVersion: Int = 0,
    onDraftChange: (DrxSettings) -> Unit = {},
    onDiscard: () -> Unit = {},
    connected: Boolean = false,
    demoMode: Boolean = true,
    shadowMode: Boolean = false,
    demoBalance: Double = 10_000.0,
    signalOnly: Boolean = false,
    resumeOnLaunch: Boolean = false,
    onDemoModeChange: (Boolean) -> Unit = {},
    onShadowModeChange: (Boolean) -> Unit = {},
    onSignalOnlyChange: (Boolean) -> Unit = {},
    onResumeOnLaunchChange: (Boolean) -> Unit = {},
    onResetDemo: () -> Unit = {},
    onResetBot: () -> Unit = {},
    onNewRun: (Double?) -> String = { "" },
    onCorrectKeyUsage: (Int) -> Unit = {},
    wide: Boolean = false,
    initialCategory: SettingsCategory = SettingsCategory.TRADING,
    onSave: (DrxSettings) -> Unit,
) {
    var category by remember { mutableStateOf(initialCategory) }
    // Поля с неразобранным числом: пока они есть, сохранить нельзя — иначе сохранилось бы не то, что видно.
    val invalid = remember(formVersion) { mutableStateMapOf<String, Boolean>() }
    var savedFlash by remember { mutableStateOf(false) }
    LaunchedEffect(savedFlash) { if (savedFlash) { delay(2_500); savedFlash = false } }

    val mode = accountMode(demoMode, shadowMode)
    val dirty = draft != settings
    val dirtyCategories = buildSet {
        if (draft.protection != settings.protection) add(SettingsCategory.PROTECTION)
        if (draft.copy(protection = settings.protection) != settings) add(SettingsCategory.CONNECTIONS)
    }
    val hasErrors = invalid.values.any { it }

    val content: @Composable ColumnScope.() -> Unit = {
        when (category) {
            SettingsCategory.TRADING -> TradingSettings(
                mode, demoBalance, signalOnly, resumeOnLaunch, savedSeed = settings.walletSeed.isNotBlank(),
                onMode = { m ->
                    when (m) {
                        AccountMode.DEMO -> { onDemoModeChange(true); onShadowModeChange(false) }
                        AccountMode.SHADOW -> { onDemoModeChange(true); onShadowModeChange(true) }
                        AccountMode.REAL -> onDemoModeChange(false)
                    }
                },
                onSignalOnly = onSignalOnlyChange,
                onResume = onResumeOnLaunchChange,
            )
            SettingsCategory.PROTECTION -> ProtectionSettingsForm(draft.protection, formVersion, invalid) {
                onDraftChange(draft.copy(protection = it))
            }
            SettingsCategory.CONNECTIONS -> ConnectionsSettings(draft, settings, formVersion, invalid, onDraftChange, onCorrectKeyUsage)
            SettingsCategory.MAINTENANCE -> MaintenanceSettings(demoMode, onNewRun, onResetDemo, onResetBot)
        }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (wide) {
                Column(Modifier.widthIn(max = SettingsMaxWidth + 300.dp).fillMaxSize().padding(horizontal = 24.dp)) {
                    ScreenHeader("Настройки", if (connected) null else "рантайм не подключён") {
                        if (savedFlash) Pill("Сохранено", Green, icon = Icons.Rounded.Check)
                    }
                    SettingsStatusPills(mode, settings)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(260.dp).fillMaxHeight()) {
                            SettingsCategory.entries.forEach { c ->
                                CategoryRow(c, c == category, c in dirtyCategories) { category = c }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        Spacer(Modifier.width(24.dp))
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).imePadding()
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = content,
                        )
                    }
                }
            } else {
                Column(
                    Modifier.widthIn(max = SettingsMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScreenHeader("Настройки", if (connected) null else "рантайм не подключён") {
                        if (savedFlash) Pill("Сохранено", Green, icon = Icons.Rounded.Check)
                    }
                    SettingsStatusPills(mode, settings)
                    CategoryTabs(category, dirtyCategories) { category = it }
                    content()
                }
            }
        }
        if (dirty) {
            SaveBar(
                categories = dirtyCategories,
                hasErrors = hasErrors,
                onDiscard = onDiscard,
                onSave = { onSave(draft); savedFlash = true },
            )
        }
    }
}

// ─── Навигация по разделам ────────────────────────────────────────────────────

/** Сводка состояния под заголовком: режим, кошелёк, Telegram, ключ CoinGecko — видно, не открывая разделы. */
@Composable
private fun SettingsStatusPills(mode: AccountMode, saved: DrxSettings) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(mode.label, mode.color)
        StatusPill("Кошелёк", saved.walletSeed.isNotBlank())
        StatusPill("Telegram", saved.telegramToken.isNotBlank() && saved.telegramChatId.isNotBlank())
        StatusPill("CoinGecko", saved.coinGeckoKey.isNotBlank())
    }
}

@Composable
private fun StatusPill(label: String, on: Boolean) {
    Pill(label, if (on) Green else TextSecondary, icon = if (on) Icons.Rounded.Check else Icons.Rounded.Close)
}

/**
 * Разделы на телефоне: четыре равные вкладки с иконкой над подписью — все видны сразу, без прокрутки вбок.
 * Точка у иконки — в разделе есть несохранённые правки.
 */
@Composable
private fun CategoryTabs(selected: SettingsCategory, dirty: Set<SettingsCategory>, onSelect: (SettingsCategory) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceCard).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsCategory.entries.forEach { c ->
            val isSelected = c == selected
            Column(
                Modifier.weight(1f).testTag(settingsCategoryTag(c)).clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) SurfaceRaised else Color.Transparent)
                    .clickable { onSelect(c) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(c.icon, contentDescription = null, tint = if (isSelected) Green else TextSecondary, modifier = Modifier.size(20.dp))
                    if (c in dirty) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(Amber))
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    c.title, fontSize = 11.sp, color = if (isSelected) TextPrimary else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(c: SettingsCategory, selected: Boolean, dirty: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) SurfaceCard else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag(settingsCategoryTag(c)).clip(RoundedCornerShape(12.dp)).clickable { onClick() },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(c.icon, contentDescription = null, tint = if (selected) Green else TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, fontSize = 14.sp, color = TextPrimary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                Text(c.subtitle, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (dirty) StatusDot(Amber, 8.dp)
        }
    }
}

/** Панель внизу, пока черновик не сохранён: что изменено и две кнопки. */
@Composable
private fun SaveBar(categories: Set<SettingsCategory>, hasErrors: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    Surface(color = SurfaceRaised) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Не сохранено", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1)
                    Text(
                        if (hasErrors) "есть поля с ошибкой"
                        else categories.joinToString(", ") { it.title }.ifEmpty { "Настройки" },
                        fontSize = 12.sp, color = if (hasErrors) Red else TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                ActionButton("Отменить", TextSecondary, onClick = onDiscard)
                Spacer(Modifier.width(8.dp))
                ActionButton("Сохранить", Green, filled = true, enabled = !hasErrors, onClick = onSave)
            }
        }
    }
}

// ─── Торговля ─────────────────────────────────────────────────────────────────

@Composable
private fun TradingSettings(
    mode: AccountMode,
    demoBalance: Double,
    signalOnly: Boolean,
    resumeOnLaunch: Boolean,
    savedSeed: Boolean,
    onMode: (AccountMode) -> Unit,
    onSignalOnly: (Boolean) -> Unit,
    onResume: (Boolean) -> Unit,
) {
    var confirmReal by remember { mutableStateOf(false) }
    HintText("Режим и переключатели применяются сразу — сохранять не нужно.")
    DrxCard {
        CardTitle("Режим счёта", icon = Icons.Rounded.Savings, subtitle = "куда идут сделки бота")
        Spacer(Modifier.height(12.dp))
        SegmentedControl(
            options = AccountMode.entries.map { it.label },
            selected = mode.ordinal,
            colors = AccountMode.entries.map { it.color },
        ) { i ->
            val target = AccountMode.entries[i]
            if (target == mode) return@SegmentedControl
            if (target == AccountMode.REAL) confirmReal = true else onMode(target)
        }
        Spacer(Modifier.height(12.dp))
        val balance = fmtUsd(demoBalance, 0)
        when (mode) {
            AccountMode.DEMO -> Banner(
                "Сделки виртуальные (счёт $balance USD): покупка и продажа по цене графика, без комиссий пулов и " +
                    "проскальзывания — результат получается лучше реального.", Tone.INFO,
            )
            AccountMode.SHADOW -> Banner(
                "Счёт виртуальный ($balance USD), но суммы входа и выхода — из настоящих котировок Jupiter на ваш размер. " +
                    "Транзакции не отправляются; монету без маршрута продажи не продать и тут.", Tone.INFO,
            )
            AccountMode.REAL -> Banner("Реальные свопы через Jupiter с вашего кошелька — деньги настоящие.", Tone.WARN)
        }
    }
    DrxCard {
        CardTitle("Автоматика", icon = Icons.Rounded.Bolt)
        Spacer(Modifier.height(6.dp))
        SettingSwitch(
            "Только сигнал (без сделок)", signalOnly,
            description = "Бот присылает сигнал и параметры входа, но не покупает. Действует и для Telegram.",
            onChange = onSignalOnly,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
        SettingSwitch(
            "Возобновлять торговлю при запуске", resumeOnLaunch,
            description = "После перезапуска приложения или телефона бот вернётся в состояние, в котором его оставили. " +
                "Не включайте на десктопе, если ту же базу (DB_PATH) использует сервер: торговали бы два бота.",
            onChange = onResume,
        )
    }

    if (confirmReal) {
        ConfirmDialog(
            title = "Включить реальную торговлю?",
            text = "Бот начнёт покупать монеты за настоящие деньги с вашего кошелька через Jupiter. Лимиты и проверка " +
                "монеты из раздела «Защита» действуют, но мемкоины могут падать быстрее, чем срабатывает стоп.",
            confirmLabel = "Включить REAL",
            confirmColor = Amber,
            confirmEnabled = savedSeed,
            icon = Icons.Rounded.WarningAmber,
            onConfirm = { confirmReal = false; onMode(AccountMode.REAL) },
            onDismiss = { confirmReal = false },
        ) {
            if (savedSeed) {
                Banner("Seed-фраза сохранена. Проверьте, что на кошельке есть SOL на комиссии.", Tone.OK)
            } else {
                Banner("Seed-фраза не сохранена — без неё бот не сможет подписать сделку. Сначала добавьте её в «Подключения».", Tone.BAD)
            }
        }
    }
}

// ─── Защита ───────────────────────────────────────────────────────────────────

@Composable
private fun ProtectionSettingsForm(
    p: ProtectionSettings,
    formKey: Int,
    invalid: MutableMap<String, Boolean>,
    onChange: (ProtectionSettings) -> Unit,
) {
    HintText("Правила для всех стратегий сразу. Действуют со следующего входа или выхода после сохранения. 0 — лимит выключен.")
    DrxCard {
        CardTitle("Проверка монеты перед покупкой", icon = Icons.Rounded.Shield)
        Spacer(Modifier.height(6.dp))
        SettingSwitch(
            "Проверять монету", p.guardEnabled,
            description = "Перед покупкой бот спрашивает у Jupiter котировку на свой размер и обратную продажу: нет маршрута " +
                "продажи — монету не покупаем.",
        ) { onChange(p.copy(guardEnabled = it)) }
        if (p.guardEnabled) {
            SettingSwitch("Mint и freeze authority отозваны", p.requireRevokedAuthorities) { onChange(p.copy(requireRevokedAuthorities = it)) }
            SettingSwitch(
                "Отклонять опасный Token-2022", p.rejectRiskyToken2022,
                description = "Комиссия перевода, перманентный делегат, хук перевода, пауза или заморозка счетов.",
            ) { onChange(p.copy(rejectRiskyToken2022 = it)) }
            Spacer(Modifier.height(6.dp))
            FieldPair(
                { DraftDecimal("Влияние покупки на цену", p.maxPriceImpactPct, formKey, invalid, "%") { onChange(p.copy(maxPriceImpactPct = it)) } },
                { DraftDecimal("Потери «купить и продать»", p.maxRoundTripLossPct, formKey, invalid, "%") { onChange(p.copy(maxRoundTripLossPct = it)) } },
            )
        }
    }
    DrxCard {
        CardTitle("Лимиты портфеля", icon = Icons.Rounded.Savings, subtitle = "все стратегии текущего режима вместе")
        Spacer(Modifier.height(6.dp))
        SettingSwitch("Ограничивать портфель", p.portfolioEnabled) { onChange(p.copy(portfolioEnabled = it)) }
        if (p.portfolioEnabled) {
            Spacer(Modifier.height(6.dp))
            FieldPair(
                { DraftInt("Открытых позиций", p.maxOpenPositions, formKey, invalid, "шт.") { onChange(p.copy(maxOpenPositions = it)) } },
                { DraftDecimal("В позициях", p.maxExposurePct, formKey, invalid, "% кап.") { onChange(p.copy(maxExposurePct = it)) } },
            )
            FieldPair(
                { DraftDecimal("Убыток за день", p.maxDailyLossPct, formKey, invalid, "% кап.") { onChange(p.copy(maxDailyLossPct = it)) } },
                { DraftDecimal("Убыток за 7 дней", p.maxWeeklyLossPct, formKey, invalid, "% кап.") { onChange(p.copy(maxWeeklyLossPct = it)) } },
            )
            FieldPair(
                { DraftInt("Пауза после убытков подряд", p.lossStreakLimit, formKey, invalid, "сделок") { onChange(p.copy(lossStreakLimit = it)) } },
                { DraftInt("Длительность паузы", p.lossStreakPauseMinutes, formKey, invalid, "мин") { onChange(p.copy(lossStreakPauseMinutes = it)) } },
            )
            DraftDecimal("Просадка портфеля от пика", p.maxDrawdownPct, formKey, invalid, "%") { onChange(p.copy(maxDrawdownPct = it)) }
            SettingSwitch(
                "Останавливать бота при лимите убытка", p.autoStopOnLimit,
                description = "При лимите за день, 7 дней или просадке бот не ждёт следующего дня, а останавливается целиком — " +
                    "запуск только вручную.",
            ) { onChange(p.copy(autoStopOnLimit = it)) }
            HintText(
                "Капитал — свободный баланс плюс позиции; убыток — закрытые сделки и открытые по последней цене. Просадка — " +
                    "от лучшей точки кривой P&L по сделкам (пополнение кошелька и цена SOL не считаются)."
            )
        }
    }
    DrxCard {
        CardTitle("Продажа и выход", icon = Icons.Rounded.Flag)
        Spacer(Modifier.height(10.dp))
        DraftDecimal(
            "Потолок slippage продажи", p.maxSellSlippagePct, formKey, invalid, "%",
            hint = "Неудачная продажа повторяется с бо́льшим slippage (3% → 6% → 12%… до потолка). После $EXIT_BLOCKED_AFTER " +
                "неудач подряд — «продажа заблокирована»: попытки идут с паузой, в Telegram приходит предупреждение.",
        ) { onChange(p.copy(maxSellSlippagePct = it)) }
        SettingSwitch(
            "Выход по развороту рынка", p.reversalExitEnabled,
            description = "Сделка продаётся, не дожидаясь стопа, когда на закрытых барах EMA(21) ушла ниже EMA(50), а после " +
                "входа сложились нижние максимум и минимум. Проверка — раз на закрытый бар.",
        ) { onChange(p.copy(reversalExitEnabled = it)) }
    }
}

/** Два поля рядом на широком экране; на телефоне — друг под другом, чтобы подписи не обрезались. */
@Composable
private fun FieldPair(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 520.dp) {
            Column {
                left()
                right()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { left() }
                Box(Modifier.weight(1f)) { right() }
            }
        }
    }
}

/** Дробное поле черновика: текст хранится как набран, в черновик уходит только разобранное число. */
@Composable
private fun DraftDecimal(
    label: String,
    value: Double,
    formKey: Int,
    invalid: MutableMap<String, Boolean>,
    suffix: String? = null,
    hint: String? = null,
    onValue: (Double) -> Unit,
) {
    // Текст — с полной точностью значения: округлённое «0.13» вместо сохранённого 0.125 при первой же правке
    // соседнего символа записало бы в настройки другое число.
    var text by remember(formKey) { mutableStateOf(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()) }
    val error = if (text.toDoubleOrNull() == null) "Введите число" else null
    TrackValidity(label, error != null, invalid)
    NumberField(label, text, onChange = { v ->
        text = v
        v.toDoubleOrNull()?.let { onValue(it.coerceAtLeast(0.0)) }
    }, suffix = suffix, hint = hint, error = error)
}

/** Целое поле черновика (см. [DraftDecimal]); число меньше [min] — ошибка, в черновик не уходит. */
@Composable
private fun DraftInt(
    label: String,
    value: Int,
    formKey: Int,
    invalid: MutableMap<String, Boolean>,
    suffix: String? = null,
    hint: String? = null,
    min: Int = 0,
    onValue: (Int) -> Unit,
) {
    var text by remember(formKey) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val error = when {
        parsed == null -> "Введите целое число"
        parsed < min -> "Не меньше $min"
        else -> null
    }
    TrackValidity(label, error != null, invalid)
    NumberField(label, text, onChange = { v ->
        text = v
        v.toIntOrNull()?.takeIf { it >= min }?.let(onValue)
    }, suffix = suffix, hint = hint, error = error, allowDecimal = false)
}

/**
 * Поле сообщает экрану, что в нём ошибка; скрытое поле (ушли в другой раздел) ошибкой больше не считается.
 * Запись — в эффекте, а не в композиции: карта [invalid] — состояние, которое читает панель «Сохранить».
 */
@Composable
private fun TrackValidity(key: String, isError: Boolean, invalid: MutableMap<String, Boolean>) {
    DisposableEffect(key, isError, invalid) {
        invalid[key] = isError
        onDispose { invalid.remove(key) }
    }
}

// ─── Подключения ──────────────────────────────────────────────────────────────

@Composable
private fun ConnectionsSettings(
    draft: DrxSettings,
    saved: DrxSettings,
    formKey: Int,
    invalid: MutableMap<String, Boolean>,
    onDraft: (DrxSettings) -> Unit,
    onCorrectKeyUsage: (Int) -> Unit,
) {
    HintText("Изменения вступают в силу после «Сохранить». Секреты скрыты — глаз показывает их.")

    // ── Кошелёк и узел ──
    DrxCard {
        CardTitle("Кошелёк Solana", icon = Icons.Rounded.AccountBalanceWallet, subtitle = "нужен только для REAL")
        Spacer(Modifier.height(12.dp))
        if (saved.walletSeed.isNotBlank()) Banner("Seed-фраза сохранена — REAL доступен.", Tone.OK)
        else Banner("Seed-фраза не задана — REAL недоступен.", Tone.WARN)
        Spacer(Modifier.height(10.dp))
        val words = draft.walletSeed.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val seedError = draft.walletSeed.isNotBlank() && words != 12 && words != 24
        TrackValidity("seed", seedError, invalid)
        SecretField(
            label = "Seed-фраза (12 или 24 слова)",
            value = draft.walletSeed,
            onChange = { onDraft(draft.copy(walletSeed = it)) },
            hint = when {
                draft.walletSeed.isBlank() -> if (saved.walletSeed.isNotBlank()) "Пусто — сохранённая фраза останется" else "Слова через пробел"
                seedError -> "Сейчас $words ${plural(words, "слово", "слова", "слов")} — нужно 12 или 24"
                else -> "$words слов ✓ · хранится только на этом устройстве"
            },
            isError = seedError,
        )
        Spacer(Modifier.height(8.dp))
        RpcUrlField(draft.rpcUrl, invalid) { onDraft(draft.copy(rpcUrl = it)) }
        if (draft.rpcUrl.trim() != saved.rpcUrl.trim()) {
            Spacer(Modifier.height(8.dp))
            Banner("Новый узел заработает после перезапуска приложения.", Tone.INFO)
        }
    }

    // ── Данные рынка ──
    MarketDataCard(draft, formKey, invalid, onDraft, onCorrectKeyUsage)

    // ── Telegram ──
    DrxCard {
        val linked = saved.telegramToken.isNotBlank() && saved.telegramChatId.isNotBlank()
        CardTitle("Telegram", icon = Icons.AutoMirrored.Rounded.Send, subtitle = "уведомления о сделках и управление ботом")
        Spacer(Modifier.height(12.dp))
        if (linked) Banner("Подключён — бот присылает уведомления и слушает команды.", Tone.OK)
        else Banner("Не подключён — уведомлений и команд нет.", Tone.NEUTRAL)
        Spacer(Modifier.height(10.dp))
        SecretField(
            label = "Токен бота",
            value = draft.telegramToken,
            onChange = { onDraft(draft.copy(telegramToken = it)) },
            hint = "Выдаёт @BotFather в Telegram",
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = draft.telegramChatId,
            onValueChange = { v -> onDraft(draft.copy(telegramChatId = v.filterIndexed { i, ch -> ch.isDigit() || (i == 0 && ch == '-') })) },
            label = { Text("Chat ID") },
            supportingText = { Text("Свой ID — у @userinfobot; у группы он с минусом", fontSize = 12.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = settingsFieldColors(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Адрес RPC-узла. Ключ провайдера в адресе (`?api-key=…`) скрыт точками, пока не нажать глаз: адрес видно на
 * скриншотах и при демонстрации экрана.
 */
@Composable
private fun RpcUrlField(value: String, invalid: MutableMap<String, Boolean>, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val trimmed = value.trim()
    val error = when {
        trimmed.isEmpty() -> "Укажите адрес узла"
        !trimmed.startsWith("https://") && !trimmed.startsWith("http://") -> "Адрес начинается с https://"
        else -> null
    }
    TrackValidity("rpc", error != null, invalid)
    val hasSecret = rpcSecretRange(value) != null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Solana RPC URL") },
        supportingText = { Text(error ?: "Узел: ${rpcProviderName(trimmed)} · применяется после перезапуска", fontSize = 12.sp) },
        isError = error != null,
        singleLine = true,
        visualTransformation = if (shown || !hasSecret) VisualTransformation.None else RpcSecretMask,
        trailingIcon = if (hasSecret) {
            {
                Icon(
                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (shown) "Скрыть ключ" else "Показать ключ",
                    tint = TextSecondary,
                    modifier = Modifier.clip(CircleShape).clickable { shown = !shown }.padding(8.dp),
                )
            }
        } else null,
        leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Где в адресе RPC секрет: значение параметра api-key / apikey / token / key. null — секрета нет. */
internal fun rpcSecretRange(url: String): IntRange? {
    val m = Regex("[?&](api-key|apikey|api_key|token|key)=([^&#]+)", RegexOption.IGNORE_CASE).find(url) ?: return null
    return m.groups[2]?.range
}

/** Маска ключа в адресе RPC: длина не меняется, поэтому курсор и выделение работают как обычно. */
private object RpcSecretMask : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val range = rpcSecretRange(text.text) ?: return TransformedText(text, OffsetMapping.Identity)
        val masked = buildString {
            append(text.text, 0, range.first)
            repeat(range.last - range.first + 1) { append('•') }
            append(text.text, range.last + 1, text.text.length)
        }
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}

/** Понятное имя провайдера по адресу узла. */
internal fun rpcProviderName(url: String): String {
    val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
    return when {
        host.isEmpty() -> "не задан"
        "helius" in host -> "Helius"
        "quiknode" in host || "quicknode" in host -> "QuickNode"
        "publicnode" in host -> "PublicNode"
        "alchemy" in host -> "Alchemy"
        "ankr" in host -> "Ankr"
        "triton" in host || "rpcpool" in host -> "Triton"
        host == "api.mainnet-beta.solana.com" -> "публичный узел Solana"
        else -> host
    }
}

/**
 * Ключ CoinGecko и его расход. Торговля всегда идёт без ключа (бесплатно, без месячного предела); ключ тратят только
 * бэктест, «Анализ» и график — не больше дневного лимита, чтобы квоты хватило до конца месяца.
 */
@Composable
private fun MarketDataCard(
    draft: DrxSettings,
    formKey: Int,
    invalid: MutableMap<String, Boolean>,
    onDraft: (DrxSettings) -> Unit,
    onCorrectKeyUsage: (Int) -> Unit,
) {
    // Новые сутки могли начаться, пока экран был закрыт, — пересчитываем расход при открытии.
    LaunchedEffect(Unit) { GeckoTerminalApi.refreshKeyBudget() }
    val feed by GeckoTerminalApi.status.collectAsState()
    var correcting by remember { mutableStateOf(false) }

    DrxCard {
        CardTitle("Данные рынка · CoinGecko", icon = Icons.Rounded.Sensors, subtitle = "свечи для бэктеста, «Анализа» и графика")
        Spacer(Modifier.height(12.dp))
        val (text, tone) = when {
            feed.keyRejected -> "Ключ отклонён сервером — всё идёт без ключа. Проверьте ключ и сохраните заново." to Tone.BAD
            feed.keyExhaustedUntilMs > 0L -> ("Ключ упирается в лимит — похоже, кончилась месячная квота. Бэктест и «Анализ» идут " +
                "без ключа (медленнее); ключ бот попробует снова ${formatLocalTime(feed.keyExhaustedUntilMs)}.") to Tone.WARN
            feed.usingKey && feed.keyDailyAllowance > 0 && feed.keyUsedToday >= feed.keyDailyAllowance ->
                ("Дневной лимит ключа потрачен. До ${formatLocalTime(feed.keyNextDayMs)} бэктест и «Анализ» идут без ключа — " +
                    "медленнее, но работают.") to Tone.WARN
            feed.usingKey -> "Торговля идёт без ключа — бесплатно. Ключ тратят только бэктест, «Анализ» и график." to Tone.OK
            else -> ("Без ключа GeckoTerminal даёт около 4 запросов свечей в минуту без месячного предела — торговле хватает. " +
                "Ключ ускорит бэктест, «Анализ» и график.") to Tone.INFO
        }
        Banner(text, tone)
        if (feed.usingKey) {
            Spacer(Modifier.height(12.dp))
            UsageMeter("Сегодня", feed.keyUsedToday, feed.keyDailyAllowance, "новый лимит ${formatLocalTime(feed.keyNextDayMs)}")
            Spacer(Modifier.height(10.dp))
            UsageMeter("За месяц", feed.keyUsedMonth, feed.keyMonthlyQuota, null)
            Spacer(Modifier.height(6.dp))
            Text(
                "Сверить расход с кабинетом CoinGecko ›", fontSize = 13.sp, color = Blue, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { correcting = true }.padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        SecretField(
            label = "Ключ CoinGecko (Demo API)",
            value = draft.coinGeckoKey,
            onChange = { onDraft(draft.copy(coinGeckoKey = it)) },
            hint = "coingecko.com → Developer Dashboard → Add New Key (план Demo)",
        )
        Spacer(Modifier.height(4.dp))
        DraftInt(
            "Месячная квота ключа", draft.coinGeckoQuota, formKey, invalid, "запросов",
            hint = "Дневной лимит = остаток квоты ÷ дней до конца месяца (сутки по UTC). У Demo — $DEFAULT_KEY_MONTHLY_QUOTA.",
            min = 1,
        ) { onDraft(draft.copy(coinGeckoQuota = it)) }
    }

    if (correcting) {
        var spentText by remember { mutableStateOf(feed.keyUsedMonth.toString()) }
        val spent = spentText.toIntOrNull()
        ConfirmDialog(
            title = "Сверить расход ключа",
            text = "Бот считает только свои запросы с ключом. Если ключ тратился раньше или где-то ещё, впишите расход за " +
                "этот месяц из кабинета CoinGecko (Developer Dashboard → Usage) — дневной лимит пересчитается от остатка.",
            confirmLabel = "Сохранить расход",
            confirmColor = Green,
            confirmEnabled = spent != null,
            onConfirm = { spent?.let(onCorrectKeyUsage); correcting = false },
            onDismiss = { correcting = false },
        ) {
            NumberField("Потрачено за месяц", spentText, onChange = { spentText = it }, suffix = "запросов", allowDecimal = false,
                error = if (spent == null) "Введите целое число" else null)
        }
    }
}

@Composable
private fun UsageMeter(label: String, used: Int, limit: Int, note: String?) {
    val fraction = if (limit > 0) used.toFloat() / limit else 0f
    val color = when {
        fraction >= 1f -> Red
        fraction >= 0.8f -> Amber
        else -> Green
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${fmtUsd(used.toDouble(), 0)} из ${fmtUsd(limit.toDouble(), 0)}", fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        ProgressMeter(fraction, color)
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(note, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

// ─── Обслуживание ─────────────────────────────────────────────────────────────

@Composable
private fun MaintenanceSettings(
    demoMode: Boolean,
    onNewRun: (Double?) -> String,
    onResetDemo: () -> Unit,
    onResetBot: () -> Unit,
) {
    var confirmDemo by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var demoResult by remember { mutableStateOf<String?>(null) }

    NewRunSection(demoMode, onNewRun)

    DrxCard {
        CardTitle("Демо-счёт", icon = Icons.Rounded.RestartAlt, subtitle = "виртуальный баланс DEMO и SHADOW")
        Spacer(Modifier.height(10.dp))
        HintText("Вернёт демо-счёт к 10 000 USD. Сделки и статистика остаются; чтобы считать итоги заново — «Новый старт» выше.")
        Spacer(Modifier.height(10.dp))
        ActionButton("Сбросить демо-счёт", Blue, Modifier.fillMaxWidth(), icon = Icons.Rounded.RestartAlt) { confirmDemo = true }
        demoResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = Green)
        }
    }

    DrxCard(borderColor = Red.copy(alpha = 0.4f)) {
        CardTitle("Опасная зона", icon = Icons.Rounded.DeleteForever, iconTint = Red, subtitle = "необратимо")
        Spacer(Modifier.height(10.dp))
        HintText("Полный сброс удаляет все сделки, забеги, журнал транзакций, кеш монет и балансов, сбрасывает демо-счёт. " +
            "Стратегии и настройки остаются.")
        Spacer(Modifier.height(10.dp))
        ActionButton("Стереть все данные…", Red, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteForever) { confirmWipe = true }
    }

    if (confirmDemo) {
        ConfirmDialog(
            title = "Сбросить демо-счёт?",
            text = "Виртуальный баланс снова станет 10 000 USD. Открытые DEMO-позиции ведутся дальше.",
            confirmLabel = "Сбросить",
            confirmColor = Blue,
            icon = Icons.Rounded.RestartAlt,
            onConfirm = { confirmDemo = false; onResetDemo(); demoResult = "Демо-счёт сброшен к 10 000 USD" },
            onDismiss = { confirmDemo = false },
        )
    }
    if (confirmWipe) {
        ConfirmDialog(
            title = "Стереть все данные?",
            text = "Удалятся все сделки (DEMO и REAL), забеги, журнал транзакций и кеши. Вернуть их будет нельзя. " +
                "Стратегии и настройки останутся.",
            confirmLabel = "Стереть всё",
            icon = Icons.Rounded.DeleteForever,
            onConfirm = { confirmWipe = false; onResetBot() },
            onDismiss = { confirmWipe = false },
        )
    }
}

// ─── Поля форм (общие с формой стратегии и бэктестом) ─────────────────────────

/** Цвета полей ввода: тёмная подложка внутри карточки, зелёная рамка в фокусе, читаемые подписи. */
@Composable
fun settingsFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Green,
    unfocusedBorderColor = BorderColor,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Green,
    focusedContainerColor = BgDark,
    unfocusedContainerColor = BgDark,
    focusedLabelColor = Green,
    unfocusedLabelColor = TextSecondary,
    focusedSupportingTextColor = TextSecondary,
    unfocusedSupportingTextColor = TextSecondary,
    focusedTrailingIconColor = TextSecondary,
    unfocusedTrailingIconColor = TextSecondary,
    focusedSuffixColor = TextSecondary,
    unfocusedSuffixColor = TextSecondary,
)
