@file:OptIn(ExperimentalLayoutApi::class)

package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig

// ─── Стратегии ────────────────────────────────────────────────────────────────

/** Карточки стратегий сеткой: на телефоне одна колонка, на широком окне — сколько влезет по 420 dp. */
private val StrategyCardMinWidth = 420.dp

@Composable
fun StrategiesScreen(
    strategies: List<StrategyConfig>,
    onCreate: () -> Unit,
    onEdit: (StrategyConfig) -> Unit,
    onDelete: (StrategyConfig) -> Unit,
    onToggle: (StrategyConfig) -> Unit,
    onBacktest: (StrategyConfig) -> Unit = {},
    wide: Boolean = false,
) {
    var deleting by remember { mutableStateOf<StrategyConfig?>(null) }
    val active = strategies.count { it.isActive }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = if (wide) GridCells.Adaptive(StrategyCardMinWidth) else GridCells.Fixed(1),
                modifier = Modifier.widthIn(max = if (wide) 1480.dp else ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(start = if (wide) 24.dp else 16.dp, end = if (wide) 24.dp else 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    ScreenHeader(
                        "Стратегии",
                        if (strategies.isEmpty()) "ни одной стратегии" else "включено $active из ${strategies.size} · сканируют все включённые",
                    )
                }
                if (strategies.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        DrxCard {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("Стратегий нет", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Spacer(Modifier.height(6.dp))
                                HintText("Нажмите «Стратегия», чтобы создать первую.")
                            }
                        }
                    }
                } else {
                    items(strategies, key = { it.id }) { cfg ->
                        StrategyConfigCard(cfg, onEdit, onDelete = { deleting = it }, onToggle = onToggle, onBacktest = onBacktest)
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onCreate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = Green,
            contentColor = Color.Black,
            // Подпись — на иконке: текст кнопки Material от читалок экрана прячет.
            icon = { Icon(Icons.Rounded.Add, contentDescription = "Новая стратегия") },
            text = { Text("Стратегия", fontWeight = FontWeight.Bold) },
        )
    }

    deleting?.let { cfg ->
        ConfirmDialog(
            title = "Удалить «${cfg.name.ifEmpty { cfg.type.toTypeLabel() }}»?",
            text = "Стратегия перестанет сканировать и исчезнет из списка. Её закрытые сделки останутся в истории, " +
                "открытые позиции ведутся дальше. Вернуть настройки можно только создав стратегию заново.",
            confirmLabel = "Удалить",
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = { deleting = null; onDelete(cfg) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
fun StrategyConfigCard(
    cfg: StrategyConfig,
    onEdit: (StrategyConfig) -> Unit,
    onDelete: (StrategyConfig) -> Unit,
    onToggle: (StrategyConfig) -> Unit,
    onBacktest: (StrategyConfig) -> Unit = {},
) {
    val accent = if (cfg.isActive) Green else TextSecondary
    DrxCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cfg.name.ifEmpty { cfg.type.toTypeLabel() }, fontWeight = FontWeight.SemiBold,
                    color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(cfg.timeframe, Purple)
                    Pill(cfg.type.toTypeLabel(), Amber)
                }
            }
            Switch(
                checked = cfg.isActive,
                onCheckedChange = { onToggle(cfg) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black, checkedTrackColor = Green,
                    uncheckedThumbColor = TextSecondary, uncheckedTrackColor = BgDark, uncheckedBorderColor = BorderColor,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Монеты: ликвидность от ${fmtShortUsd(cfg.minLiquidityUsd)}, капитализация ${fmtShortUsd(cfg.minMarketCap)}–${fmtShortUsd(cfg.maxMarketCap)}",
            fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoChip("🛡", "стоп ${fmtPlain(cfg.stopLossPercent)}%")
                InfoChip("🎯", "цель ${fmtPlain(cfg.takeProfitPercent)}%")
                InfoChip("📦", "${fmtPlain(cfg.positionSize)}% баланса")
                if (cfg.trailingStopEnabled || cfg.atrTrailEnabled || cfg.tieredTrailEnabled) InfoChip("📈", "трейлинг")
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconSquareButton(Icons.Rounded.Science, "Бэктест", Blue) { onBacktest(cfg) }
                IconSquareButton(Icons.Rounded.Edit, "Изменить", TextPrimary) { onEdit(cfg) }
                IconSquareButton(Icons.Rounded.DeleteOutline, "Удалить", Red) { onDelete(cfg) }
            }
        }
    }
}

/** «$12K», «$1.5M» — короткие суммы для подписи фильтров. */
internal fun fmtShortUsd(v: Double): String = when {
    v >= 1_000_000 -> "$" + formatNumberRu(v / 1_000_000, 1).removeSuffix(",0") + "M"
    v >= 1_000 -> "$" + formatNumberRu(v / 1_000, 0) + "K"
    else -> "$" + formatNumberRu(v, 0)
}
