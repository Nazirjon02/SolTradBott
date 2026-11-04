package tj.khujand.solana.trading.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.core.engine.fmtNum
import tj.khujand.solana.trading.bot.domain.dars.CoinAnalysis
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalysis
import tj.khujand.solana.trading.bot.util.formatLargeNumber

/**
 * Ручная покупка монеты из «Анализа» (порт ручной сделки MRX). Сумма, стоп и тейк задаёт человек; дальше —
 * те же лимиты портфеля, проверка монеты и исполнение, что у бота. В REAL — вторым нажатием: деньги настоящие.
 *
 * @param onBuy покупка; возвращает строку итога («✅ …» или «⛔ причина»).
 */
@Composable
fun ManualBuyDialog(
    coin: CoinAnalysis,
    modeLabel: String,
    isReal: Boolean,
    defaultSizeUsd: Double,
    onBuy: suspend (sizeUsd: Double, stopLossPercent: Double, takeProfitPercent: Double) -> String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sizeText by remember { mutableStateOf(fmtNum(defaultSizeUsd)) }
    var slText by remember { mutableStateOf("15") }
    var tpText by remember { mutableStateOf("30") }
    var confirmReal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val size = sizeText.toDoubleOrNull()
    val sl = slText.toDoubleOrNull()
    val tp = tpText.toDoubleOrNull()
    val valid = size != null && size > 0 && sl != null && sl > 0 && sl < 90 && tp != null && tp > 0

    fun buy() {
        if (!valid || busy) return
        busy = true
        scope.launch {
            result = onBuy(size!!, sl!!, tp!!)
            busy = false
            confirmReal = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = SurfaceCard,
        title = { Text("🛒 Купить ${coin.symbol}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Цена ${MarketAnalysis.fmtPrice(coin.price)} · ликвидность $${formatLargeNumber(coin.market.liquidityUsd)} · " +
                        "MC $${formatLargeNumber(coin.market.marketCap)}",
                    fontSize = 12.sp, color = TextSecondary
                )
                Surface(
                    color = (if (isReal) Red else Purple).copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isReal) "💸 REAL — своп через Jupiter с вашего кошелька, деньги настоящие"
                        else "🎮 $modeLabel — сделка виртуальная",
                        color = if (isReal) Red else Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                if (result == null) {
                    FormTextField("Сумма, USD", sizeText, isNumber = true) { sizeText = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { FormTextField("Стоп, %", slText, isNumber = true) { slText = it } }
                        Column(Modifier.weight(1f)) { FormTextField("Тейк, %", tpText, isNumber = true) { tpText = it } }
                    }
                    Text(
                        "Перед покупкой — лимиты портфеля и проверка монеты (authority, маршрут продажи, влияние на цену), " +
                            "как у бота. Дальше сделку ведёт монитор: стоп, тейк и ликвидность-стоп.",
                        fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        result!!, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = if (result!!.startsWith("✅")) Green else Red
                    )
                }
            }
        },
        confirmButton = {
            when {
                result != null -> DialogButton("Закрыть", Green) { onDismiss() }
                busy -> Text("Покупаю…", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                isReal && confirmReal -> DialogButton("Точно купить на $${fmtNum(size ?: 0.0)}", Red, enabled = valid) { buy() }
                isReal -> DialogButton("Купить на $${fmtNum(size ?: 0.0)}", Red, enabled = valid) { confirmReal = true }
                else -> DialogButton("Купить на $${fmtNum(size ?: 0.0)}", Green, enabled = valid) { buy() }
            }
        },
        dismissButton = {
            if (result == null && !busy) DialogButton("Отмена", TextSecondary) { onDismiss() }
        }
    )
}

@Composable
private fun DialogButton(label: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) color else TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.clickable(enabled = enabled) { onClick() }.padding(8.dp)
    )
}
