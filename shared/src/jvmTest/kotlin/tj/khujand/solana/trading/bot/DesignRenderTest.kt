package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import tj.khujand.solana.trading.bot.core.engine.ActivityEvent
import tj.khujand.solana.trading.bot.core.engine.ActivityLevel
import tj.khujand.solana.trading.bot.core.engine.ActivityStats
import tj.khujand.solana.trading.bot.core.strategy.DiagnosisTone
import tj.khujand.solana.trading.bot.core.strategy.EntryDiagnosis
import tj.khujand.solana.trading.bot.core.strategy.EntryLoss
import tj.khujand.solana.trading.bot.core.strategy.FunnelSnapshot
import tj.khujand.solana.trading.bot.core.strategy.ReversalFixtures
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.core.strategy.WatchItem
import tj.khujand.solana.trading.bot.core.strategy.WatchStatus
import tj.khujand.solana.trading.bot.data.AccountBalance
import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.data.OpenPosition
import tj.khujand.solana.trading.bot.data.ProtectionSettings

/**
 * Новый дизайн реально рисуется на телефоне и на широком окне (Skia без окна, как [AnalysisRenderTest]).
 * PNG — в `build/design-render/`, чтобы посмотреть вёрстку глазами.
 */
class DesignRenderTest {

    private fun render(name: String, width: Int, height: Int, density: Float = 2f, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            DrxTheme { Box(Modifier.fillMaxSize().background(BgDark)) { content() } }
        }
        val bytes = try {
            scene.render()
            scene.render(16_000_000L)
            // Диалоги появляются с анимацией — снимаем кадр, когда она закончилась.
            scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
        File("build/design-render").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
        return bytes
    }

    private val now = System.currentTimeMillis()

    private val strategies = listOf(
        StrategyConfig(id = "a", name = "Пробой + откат", type = StrategyType.BREAKOUT_PULLBACK.name, isActive = true, timeframe = "5m"),
        StrategyConfig(id = "b", name = "Мем-плейбук", type = StrategyType.MEME_PLAYBOOK.name, isActive = true, timeframe = "5m",
            tieredTrailEnabled = true),
        StrategyConfig(id = "c", name = "Dars", type = StrategyType.DARS.name, isActive = false, timeframe = "1m"),
    )

    private val trades = (1..6).map { i ->
        val win = i % 2 == 0
        TradeHistoryItem(
            symbol = "MEME$i", strategy = if (i % 3 == 0) "Мем-плейбук" else "Пробой + откат", entryPrice = 0.0012,
            exitPrice = 0.0012 * (if (win) 1.2 else 0.9), pnl = if (win) 12.5 else -6.0, pnlPercent = if (win) 20.0 else -10.0,
            sizeUsd = 60.0, isDemo = true, duration = "${i * 7} мин", isWin = win,
            openReason = "Пробой + откат: пробой 20 баров, откат на объёме ×0.6, триггер выше максимума",
            closeReason = if (win) "TP1" else "Стоп", closedAt = now - i * 3_600_000L, mint = "So1aNaMemeMint$i".padEnd(44, '1'),
            openedAt = now - i * 3_600_000L - 1_800_000L,
        )
    }

    private val positions = listOf(
        OpenPosition(
            tradeId = "t1", mint = "So1aNaMemeMint1111111111111111111111111111", symbol = "WIF2",
            strategyName = "Мем-плейбук", entryPrice = 0.001, currentPrice = 0.00112, qtyRemaining = 98_000.0,
            sizeUsd = 100.0, valueUsd = 109.8, pnlUsd = 9.8, pnlPercent = 12.0, stopLoss = 0.00085,
            takeProfit = 0.002, isDemo = true, openedAt = now - 2_400_000L, execMode = "SHADOW",
        ),
        OpenPosition(
            tradeId = "t2", mint = "So1aNaMemeMint2222222222222222222222222222", symbol = "CATZ",
            strategyName = "Пробой + откат", entryPrice = 0.0005, currentPrice = 0.00046, qtyRemaining = 120_000.0,
            sizeUsd = 60.0, valueUsd = 55.2, pnlUsd = -4.8, pnlPercent = -8.0, stopLoss = 0.00044,
            takeProfit = 0.0008, isDemo = true, openedAt = now - 600_000L, execMode = "DEMO",
            exitFailures = 1, exitNote = "нет маршрута продажи (COULD_NOT_FIND_ANY_ROUTE)",
        ),
    )

    private val watch = listOf(
        WatchItem("m1", "BONKX", "p1", "a", "Пробой + откат", "5m", 0.0021, 0.00214, 5, 6, "триггер: закрытие выше 0.00214", WatchStatus.NEAR, now),
        WatchItem("m2", "POPCAT", "p2", "b", "Мем-плейбук", "5m", 0.0005, null, 4, 6, "откат 0.31 — нужен 0.382–0.786", WatchStatus.WAITING, now),
    )

    private val events = listOf(
        ActivityEvent(now - 50_000, ActivityLevel.INFO, "🔍 Пробой + откат: сканирую кандидатов…"),
        ActivityEvent(now - 40_000, ActivityLevel.SUCCESS, "✓ Пробой + откат: кандидатов 14"),
        ActivityEvent(now - 30_000, ActivityLevel.WARN, "⚠️ Свечи: источник ответил «слишком много запросов»"),
        ActivityEvent(now - 20_000, ActivityLevel.INFO, "🛡 CATZ: вход отклонён — нет маршрута продажи"),
    )

    private val funnel = FunnelSnapshot(
        windowMinutes = 60, cycles = 112, coins = 38, setups = 3,
        losses = mapOf(EntryLoss.COIN_CHECK to 2, EntryLoss.PRICE_RAN to 1), rejectedCoins = 3, opened = 0,
        lastCycleAt = now - 12_000,
    )
    private val diagnosis = EntryDiagnosis(
        "Сетапов: 3, сделок нет — чаще всего мешает: проверка монеты", DiagnosisTone.WARN,
        listOf(
            "Проверка монеты отклоняет вход: authority, Token-2022, маршрут продажи или влияние на цену — подробности в ленте (🛡).",
            "Ещё: цена убежала от сигнала — 1",
        ),
    )

    @Composable
    private fun Dashboard(wide: Boolean, sidePane: (@Composable () -> Unit)? = null) = DashboardScreen(
        botStatus = BotStatus.RUNNING, strategies = strategies,
        balance = AccountBalance(totalUsd = 10_234.56, demoUsd = 10_124.0, isDemo = true), connected = true,
        activity = events, stats = ActivityStats(okCount = 312, errorCount = 3, lastOkAt = now - 5_000),
        trades = trades, openPositions = positions, watch = watch, diagnosis = diagnosis, funnel = funnel,
        lastCycleAgo = "12 с назад", modeLabel = "SHADOW", wide = wide, sidePane = sidePane, onStatusChange = {},
    )

    @Test fun dashboardPhone() {
        assertTrue(render("dashboard-phone", 780, 5200) { Dashboard(wide = false) }.size > 50_000)
    }

    @Test fun dashboardDesktop() {
        assertTrue(render("dashboard-desktop", 2160, 1350, density = 1.5f) {
            Row(Modifier.fillMaxSize()) {
                DrxNavRail(Screen.DASHBOARD, Green, "работает") {}
                Box(Modifier.weight(1f).fillMaxHeight()) { Dashboard(wide = true) }
            }
        }.size > 50_000)
    }

    @Test fun dashboardDesktopWithChart() {
        val candles = ReversalFixtures.candles(ReversalFixtures.uptrend())
        assertTrue(render("dashboard-desktop-chart", 2160, 1350, density = 1.5f) {
            Row(Modifier.fillMaxSize()) {
                DrxNavRail(Screen.DASHBOARD, Green, "работает") {}
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Dashboard(wide = true) {
                        BotChartContent(
                            symbol = "WIF2", strategyName = "Мем-плейбук", workTf = "5m", tf = "5m", candles = candles,
                            lastBarForming = false, evaluation = null, trade = null, status = "", onTimeframe = {}, onClose = {},
                        )
                    }
                }
            }
        }.size > 50_000)
    }

    @Test fun appShellPhoneAndDesktop() {
        render("shell-phone", 780, 1700) { DrxApp(null, forceWide = false) }
        render("shell-desktop", 2160, 1350, density = 1.5f) { DrxApp(null, forceWide = true) }
    }

    @Test fun tradesPhoneAndDesktop() {
        render("trades-phone", 780, 2600) { TradesScreen(trades) }
        render("trades-desktop", 2160, 1350, density = 1.5f) { TradesScreen(trades, wide = true) }
    }

    @Test fun strategiesPhoneAndDesktop() {
        render("strategies-phone", 780, 1700) { StrategiesScreen(strategies, onCreate = {}, onEdit = {}, onDelete = {}, onToggle = {}) }
        render("strategies-desktop", 2160, 1350, density = 1.5f) {
            StrategiesScreen(strategies, onCreate = {}, onEdit = {}, onDelete = {}, onToggle = {}, wide = true)
        }
    }

    @Test fun settingsCategoriesPhone() {
        val saved = DrxSettings(
            rpcUrl = "https://mainnet.helius-rpc.com/?api-key=0000-1111-2222", walletSeed = "",
            telegramToken = "123:ABC", telegramChatId = "42", coinGeckoKey = "CG-key",
        )
        SettingsCategory.entries.forEach { c ->
            render("settings-${c.name.lowercase()}-phone", 780, 3000) {
                SettingsScreen(settings = saved, connected = true, demoMode = true, shadowMode = true, initialCategory = c, onSave = {})
            }
        }
        // Черновик с правкой и ошибкой: внизу панель «Сохранить», поле подсвечено.
        val draft = saved.copy(protection = ProtectionSettings(maxOpenPositions = 5), walletSeed = "one two three")
        render("settings-dirty-phone", 780, 3000) {
            SettingsScreen(settings = saved, draft = draft, connected = true, initialCategory = SettingsCategory.CONNECTIONS, onSave = {})
        }
        render("settings-desktop", 2160, 1350, density = 1.5f) {
            SettingsScreen(settings = saved, draft = draft, connected = true, wide = true, initialCategory = SettingsCategory.PROTECTION, onSave = {})
        }
    }

    @Test fun confirmDialogsRender() {
        render("dialog-real-mode", 780, 1500) {
            ConfirmDialog(
                title = "Включить реальную торговлю?",
                text = "Бот начнёт покупать монеты за настоящие деньги с вашего кошелька через Jupiter.",
                confirmLabel = "Включить REAL", confirmColor = Amber, confirmEnabled = false,
                icon = Icons.Rounded.WarningAmber,
                onConfirm = {}, onDismiss = {},
            ) {
                Banner("Seed-фраза не сохранена — без неё бот не сможет подписать сделку.", Tone.BAD)
            }
        }
    }
}
