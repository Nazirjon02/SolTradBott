package tj.khujand.solana.trading.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tj.khujand.solana.trading.bot.core.strategy.DiagnosisTone
import tj.khujand.solana.trading.bot.core.strategy.EntryDiagnosis
import tj.khujand.solana.trading.bot.core.strategy.FunnelSnapshot

internal fun DiagnosisTone.toTone(): Tone = when (this) {
    DiagnosisTone.OK -> Tone.OK
    DiagnosisTone.INFO -> Tone.INFO
    DiagnosisTone.WARN -> Tone.WARN
    DiagnosisTone.BAD -> Tone.BAD
}

/**
 * «Почему нет сделок»: воронка входа за час (монеты → сетапы → отклонено → сделки) и главная причина одной
 * строкой. Отвечает на вопрос, с которым чаще всего открывают бота, — без чтения ленты.
 *
 * @param signalOnly режим «только сигнал»: последний шаг воронки — сигналы, а не сделки.
 */
@Composable
fun WhyNoTradesCard(
    diagnosis: EntryDiagnosis,
    funnel: FunnelSnapshot,
    signalOnly: Boolean,
    lastCycleAgo: String?,
    modifier: Modifier = Modifier,
) {
    val tone = diagnosis.tone.toTone()
    DrxCard(modifier) {
        CardTitle(
            title = if (funnel.opened > 0) "Воронка входа" else "Почему нет сделок",
            icon = Icons.Rounded.FilterAlt,
            subtitle = "за ${funnel.windowMinutes} мин" + (lastCycleAgo?.let { " · последний цикл $it" } ?: ""),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                when (diagnosis.tone) {
                    DiagnosisTone.OK -> Icons.Rounded.CheckCircle
                    DiagnosisTone.INFO -> Icons.Rounded.Info
                    DiagnosisTone.WARN -> Icons.Rounded.WarningAmber
                    DiagnosisTone.BAD -> Icons.Rounded.ErrorOutline
                },
                contentDescription = null, tint = tone.color, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(diagnosis.headline, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                lineHeight = 19.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FunnelStep("Монет", funnel.coins, Blue)
            StepArrow()
            FunnelStep("Сетапов", funnel.setups, Purple)
            StepArrow()
            FunnelStep("Отклонено", funnel.rejectedCoins, Amber)
            StepArrow()
            if (signalOnly) FunnelStep("Сигналов", funnel.signals, Green) else FunnelStep("Сделок", funnel.opened, Green)
        }
        if (diagnosis.hints.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                diagnosis.hints.forEach { hint ->
                    Row {
                        Text("•", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(12.dp))
                        HintText(hint, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.FunnelStep(label: String, value: Int, color: Color) {
    Surface(color = color.copy(alpha = if (value > 0) 0.12f else 0.05f), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (value > 0) color else TextSecondary)
            Text(label, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun StepArrow() {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f),
        modifier = Modifier.size(16.dp))
}
