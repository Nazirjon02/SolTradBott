package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.engine.TradeLine
import tj.khujand.solana.trading.bot.core.engine.TradeLineKind
import tj.khujand.solana.trading.bot.core.engine.fmtNum
import tj.khujand.solana.trading.bot.core.engine.toPositionState
import tj.khujand.solana.trading.bot.core.engine.tradeLines
import tj.khujand.solana.trading.bot.core.strategy.Evaluation
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.createStrategy
import tj.khujand.solana.trading.bot.core.strategy.toStrategyConfig
import tj.khujand.solana.trading.bot.domain.backtest.timeframeMs
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalysis
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.toTokenCandidate

// График бота (порт BotChartScreen из MRX): монета глазами стратегии — свечи в реальном времени, любой
// ТФ, разбор стратегии и, если по монете есть сделка, как бот её ведёт.

/** Как часто подтягивать свечи и разбор: живой график, но без лишней нагрузки на лимит GeckoTerminal. */
private const val REFRESH_MS = 30_000L

/** Таймфреймы для переключателя (рабочий ТФ стратегии добавляется, если его здесь нет). */
private val CHART_TIMEFRAMES = listOf("1m", "5m", "15m", "1h", "4h")

/**
 * Что показать: монету (по пулу) глазами стратегии [strategyId] и/или открытую сделку [tradeId]
 * (стратегию тогда берём из сделки).
 */
data class ChartTarget(
    val mint: String,
    val symbol: String,
    val pairAddress: String,
    val strategyId: String? = null,
    val tradeId: String? = null,
)

/** Сводка сделки для панели под графиком. */
data class ChartTradeInfo(
    val entry: Double,
    val stopNow: Double,
    val initialStop: Double,
    val pnlPercent: Double?,
    val rNow: Double?,
    val lines: List<TradeLine>,
)

private fun TradeLine.color(): Color = when (kind) {
    TradeLineKind.ENTRY -> Blue
    TradeLineKind.STOP_NOW, TradeLineKind.STOP_INITIAL -> Red
    TradeLineKind.ONE_R -> TextSecondary
    TradeLineKind.BREAK_EVEN -> Purple
    TradeLineKind.TRAIL_START -> Amber
    TradeLineKind.PARTIAL, TradeLineKind.TAKE -> Green
}.let { if (reached || kind == TradeLineKind.STOP_INITIAL) it.copy(alpha = 0.5f) else it }

/** Линии сделки → линии графика: действующие (вход, стоп сейчас, цель) — сплошные, впереди — пунктиром. */
internal fun TradeLine.toPriceLine() = PriceLine(
    price = price,
    label = label,
    color = color(),
    dashed = kind !in setOf(TradeLineKind.ENTRY, TradeLineKind.STOP_NOW, TradeLineKind.TAKE),
)

/**
 * Экран с загрузкой: свечи выбранного ТФ раз в [REFRESH_MS], на рабочем ТФ стратегии — её разбор,
 * по открытой сделке — уровни ведения. Разбор и разметка — только с рабочего ТФ: рисовать «её»
 * условия на другом ТФ значило бы показывать решение, которого бот не принимал.
 */
@Composable
fun BotChartScreen(runtime: DrxRuntime?, target: ChartTarget, onClose: () -> Unit) {
    val db = runtime?.db
    var tick by remember { mutableStateOf(0) }
    val trade = remember(target, tick) { target.tradeId?.let { id -> db?.tradeQueries?.getById(id)?.executeAsOneOrNull() } }
    val strategyId = target.strategyId ?: trade?.strategy_id
    val config: StrategyConfig? = remember(strategyId) {
        strategyId?.let { id -> db?.strategyQueries?.getById(id)?.executeAsOneOrNull()?.toStrategyConfig() }
    }
    val workTf = config?.timeframe ?: "5m"
    var tf by remember(target) { mutableStateOf(workTf) }
    var candles by remember(target) { mutableStateOf<List<Candle>>(emptyList()) }
    var evaluation by remember(target) { mutableStateOf<Evaluation?>(null) }
    var status by remember(target) { mutableStateOf("Загрузка свечей…") }

    LaunchedEffect(target, tf) {
        val client = runtime?.client ?: return@LaunchedEffect
        while (true) {
            try {
                val c = client.getCandles(target.pairAddress, tf, 200)
                if (c.isNotEmpty()) {
                    candles = c
                    status = ""
                } else if (candles.isEmpty()) {
                    status = "GeckoTerminal не вернул свечи — повтор через 30 с"
                }
                if (tf == workTf && config != null && c.isNotEmpty()) {
                    val now = Clock.System.now().toEpochMilliseconds()
                    val candidate = runtime.tokenCache.all().firstOrNull { it.mint == target.mint }
                        ?: client.getPairDetails(target.pairAddress)?.toTokenCandidate(now)
                    evaluation = candidate?.let { createStrategy(config, client).evaluate(it, c, emptyList()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Ошибка загрузки: ${e.message ?: "нет сети"}"
            }
            tick++
            delay(REFRESH_MS)
        }
    }

    val lastPrice = candles.lastOrNull()?.close
    val tradeInfo = trade?.takeIf { it.status == "OPEN" }?.let { t ->
        val position = t.toPositionState()
        val cfg = config ?: StrategyConfig(id = t.strategy_id, name = t.strategy_name)
        val r = (t.entry_price - (t.initial_stop.takeIf { it > 0 } ?: t.stop_loss)).takeIf { it > 0 }
        ChartTradeInfo(
            entry = t.entry_price,
            stopNow = t.stop_loss,
            initialStop = t.initial_stop,
            pnlPercent = lastPrice?.let { (it / t.entry_price - 1) * 100 },
            rNow = if (lastPrice != null && r != null) (lastPrice - t.entry_price) / r else null,
            lines = tradeLines(position, cfg),
        )
    }
    val forming = candles.lastOrNull()?.let { it.openTimeMs + timeframeMs(tf) > Clock.System.now().toEpochMilliseconds() } ?: false

    BotChartContent(
        symbol = target.symbol,
        strategyName = config?.name,
        workTf = workTf,
        tf = tf,
        candles = candles,
        lastBarForming = forming,
        evaluation = if (tf == workTf) evaluation else null,
        trade = tradeInfo,
        status = status,
        onTimeframe = { tf = it },
        onClose = onClose,
    )
}

/** Сам экран без сети — для рендер-теста: свечи, линии, разбор и сделка уже посчитаны. */
@Composable
fun BotChartContent(
    symbol: String,
    strategyName: String?,
    workTf: String,
    tf: String,
    candles: List<Candle>,
    lastBarForming: Boolean,
    evaluation: Evaluation?,
    trade: ChartTradeInfo?,
    status: String,
    onTimeframe: (String) -> Unit,
    onClose: () -> Unit,
) {
    val lines = buildList {
        trade?.lines?.forEach { add(it.toPriceLine()) }
        evaluation?.entryLevel?.let { add(PriceLine(it, "уровень входа", Amber, dashed = true)) }
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("📈 $symbol", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text(
                    strategyName?.let { "глазами «$it» · бот торгует на $workTf" } ?: "стратегия не определена",
                    fontSize = 12.sp, color = TextSecondary
                )
            }
            Surface(color = SurfaceCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onClose() }) {
                Text("✕ Закрыть", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (CHART_TIMEFRAMES + workTf).distinct().forEach { option ->
                        HistoryFilterChip(if (option == workTf) "$option ★" else option, option == tf) { onTimeframe(option) }
                    }
                }
                Surface(color = SurfaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        if (candles.size >= 2) {
                            CandleChart(
                                candles = candles, height = 260.dp, maxBars = 120, lastBarForming = lastBarForming,
                                viewportKey = symbol to tf, priceLines = lines,
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                                Text(status.ifEmpty { "Нет свечей" }, color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                        if (tf != workTf) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Разбор стратегии — только на рабочем ТФ $workTf: здесь — свечи и уровни сделки.",
                                fontSize = 11.sp, color = Amber, lineHeight = 15.sp
                            )
                        }
                    }
                }
                if (lines.isNotEmpty()) LinesLegend(lines)
                trade?.let { TradePanel(it, candles.lastOrNull()?.close) }
                evaluation?.let { EvaluationPanel(it) }
                if (status.isNotEmpty() && candles.size >= 2) Text(status, fontSize = 11.sp, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun LinesLegend(lines: List<PriceLine>) {
    Surface(color = SurfaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.sortedByDescending { it.price }.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(line.color))
                    Spacer(Modifier.width(8.dp))
                    Text(line.label, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                    Text(MarketAnalysis.fmtPrice(line.price), fontSize = 12.sp, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun TradePanel(t: ChartTradeInfo, price: Double?) {
    Surface(color = SurfaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Сделка", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            val pnl = t.pnlPercent
            Text(
                "Вход ${MarketAnalysis.fmtPrice(t.entry)} → сейчас ${price?.let { MarketAnalysis.fmtPrice(it) } ?: "—"}" +
                    (pnl?.let { "  (${if (it >= 0) "+" else ""}${fmtNum(it)}%)" } ?: "") +
                    (t.rNow?.let { " · ${if (it >= 0) "+" else ""}${fmtNum(it)}R" } ?: ""),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = when { pnl == null -> TextPrimary; pnl >= 0 -> Green; else -> Red }
            )
            val moved = t.initialStop > 0 && t.stopNow > t.initialStop
            Text(
                "Стоп ${MarketAnalysis.fmtPrice(t.stopNow)}" +
                    (if (moved) " — подтянут (при входе ${MarketAnalysis.fmtPrice(t.initialStop)})" else ""),
                fontSize = 12.sp, color = TextSecondary
            )
        }
    }
}

@Composable
private fun EvaluationPanel(e: Evaluation) {
    Surface(color = SurfaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Разбор стратегии", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "${e.passed}/${e.total}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (e.signal != null) Green else Amber
                )
            }
            Text(
                if (e.signal != null) "✅ Сетап сложился — вход по общим фильтрам и проверке монеты"
                else "Не хватает: ${e.missing ?: "—"}",
                fontSize = 12.sp, color = if (e.signal != null) Green else TextPrimary, lineHeight = 16.sp
            )
            e.checks.forEach { c ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(if (c.passed) "✓" else "✗", fontSize = 12.sp, color = if (c.passed) Green else Red, modifier = Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, fontSize = 12.sp, color = TextPrimary)
                        if (c.detail.isNotBlank()) Text(c.detail, fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}
