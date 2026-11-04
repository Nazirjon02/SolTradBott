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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tj.khujand.solana.trading.bot.core.engine.RunSummary

// «Новый старт» на экранах: итоги забегов на «Аналитике» и кнопка в настройках.

/**
 * Забеги режима (новые сверху): ради этого «Новый старт» и не удаляет сделки — прошлые итоги рядом,
 * для сравнения.
 */
@Composable
fun RunsCard(runs: List<RunSummary>, modeLabel: String) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = SurfaceCard, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("🏁 Забеги ($modeLabel)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            runs.asReversed().forEachIndexed { i, r ->
                if (i > 0) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "${if (r.current) "▶️" else "🏁"} ${r.periodLabel()}", fontSize = 12.sp,
                    color = if (r.current) TextPrimary else TextSecondary, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    r.resultLabel(), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = when { r.trades == 0 -> TextSecondary; r.pnl >= 0 -> Green; else -> Red }
                )
                if (r.strategies.isNotEmpty()) {
                    Text("🧠 ${r.strategies.take(3).joinToString(", ")}", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }
    }
}

/** Переключатель «Текущий забег / Вся история» над итогами. */
@Composable
fun RunScopeChips(currentRun: Boolean, runNumber: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryFilterChip("Забег №$runNumber", currentRun) { onChange(true) }
        HistoryFilterChip("Вся история", !currentRun) { onChange(false) }
    }
}

/**
 * «🏁 Новый старт» в настройках: статистика и лимиты убытка — с этого момента, сделки остаются. В DEMO
 * можно сразу выставить новый демо-счёт. [onNewRun] возвращает строку-итог для показа.
 */
@Composable
fun NewRunSection(demoMode: Boolean, onNewRun: (Double?) -> String) {
    var balanceText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    FormSection("🏁  Новый старт") {
        Text(
            "Статистика, отчёты и лимиты убытка (день, неделя, просадка, серия убытков) начнут считаться с этого " +
                "момента. Сделки не удаляются — итоги прошлых забегов остаются на «Аналитике». Открытые позиции " +
                "ведутся дальше.",
            fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
        )
        if (demoMode) {
            FormTextField("Новый демо-счёт, USD (пусто — оставить как есть)", balanceText, isNumber = true) { balanceText = it }
        }
        Spacer(Modifier.height(6.dp))
        if (!confirm) {
            ActionButton("🏁  Начать новый забег", Blue) { confirm = true; result = null }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    ActionButton("✅  Да, новый старт", Green) {
                        result = onNewRun(balanceText.toDoubleOrNull()?.takeIf { demoMode && it > 0.0 })
                        confirm = false
                        balanceText = ""
                    }
                }
                Box(Modifier.weight(1f)) { ActionButton("Отмена", SurfaceCard) { confirm = false } }
            }
        }
        result?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = Green, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    Surface(color = color.copy(alpha = if (color == SurfaceCard) 1f else 0.18f), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Text(
            label, color = if (color == SurfaceCard) TextSecondary else color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp)
        )
    }
}
