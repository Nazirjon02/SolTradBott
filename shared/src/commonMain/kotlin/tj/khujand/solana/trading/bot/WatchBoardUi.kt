package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tj.khujand.solana.trading.bot.core.engine.fmtNum
import tj.khujand.solana.trading.bot.core.strategy.WatchItem
import tj.khujand.solana.trading.bot.core.strategy.WatchStatus

// Доска наблюдения на главной (порт из MRX): лента отвечает «что бот делал», доска — «чего он ждёт».

/** Сколько строк доски показывать без «ещё». */
private const val BOARD_ROWS = 8

private fun WatchStatus.color(): Color = when (this) {
    WatchStatus.READY -> Green
    WatchStatus.NEAR -> Amber
    WatchStatus.WAITING -> Blue
    WatchStatus.NONE -> TextSecondary
}

/**
 * Карточка доски: строки ГОТОВ / БЛИЗКО / ЖДЁМ, «показать все» добавляет монеты, где собрано меньше
 * порога. [running] — сканируют ли стратегии: у остановленного бота доска пустая не потому, что сетапов нет.
 */
@Composable
fun WatchBoardCard(items: List<WatchItem>, running: Boolean, onClick: (WatchItem) -> Unit = {}) {
    var showAll by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val relevant = items.filter { it.status != WatchStatus.NONE }
    val shown = if (showAll) items else relevant
    val visible = if (expanded) shown else shown.take(BOARD_ROWS)

    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = SurfaceCard, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("👀 Наблюдение", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    if (showAll) "только важные" else "показать все (${items.size})",
                    fontSize = 11.sp, color = Blue, modifier = Modifier.clickable { showAll = !showAll }
                )
            }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    when {
                        !running -> "Бот не запущен — стратегии не сканируют."
                        items.isEmpty() -> "Стратегии ещё не разобрали ни одной монеты."
                        else -> "Сетапы пока не складываются: у всех монет собрано меньше порога. «Показать все» — увидеть, чего не хватает."
                    },
                    fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp
                )
            }
            visible.forEachIndexed { i, w ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                WatchRow(w) { onClick(w) }
            }
            if (shown.size > BOARD_ROWS) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (expanded) "свернуть" else "ещё ${shown.size - BOARD_ROWS}",
                    fontSize = 12.sp, color = Blue, modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
    }
}

@Composable
private fun WatchRow(w: WatchItem, onClick: () -> Unit) {
    val color = w.status.color()
    Row(Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.Top) {
        Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp), modifier = Modifier.widthIn(min = 64.dp)) {
            Text(
                w.status.label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.symbol, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${w.strategyName} · ${w.timeframe}", fontSize = 11.sp, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                w.distancePct?.let { d ->
                    Text(
                        "до уровня ${if (d > 0) "+" else ""}${fmtNum(d)}%", fontSize = 11.sp,
                        color = if (kotlin.math.abs(d) <= 2.0) Amber else TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            ProgressBar(w.passed, w.total, color)
            Spacer(Modifier.height(3.dp))
            Text(
                "${w.passed}/${w.total} · " + (w.missing ?: "всё сошлось — вход по общим фильтрам и проверке монеты"),
                fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Доля пройденных условий полоской: «дошли до шага N из M». */
@Composable
private fun ProgressBar(passed: Int, total: Int, color: Color) {
    val fraction = if (total > 0) (passed.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(BorderColor)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(color))
    }
}
