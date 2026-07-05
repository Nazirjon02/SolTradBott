package tj.khujand.solana.trading.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ─── Общие компоненты нового дизайна (Solana-стиль) ───────────────────────────
// Скругление 16dp, градиентные рамки, glow через полупрозрачные подложки —
// кроссплатформенно (Android + Desktop), без платформенных shadow-цветов.

/**
 * Базовая карточка. [glow] = градиентная рамка purple→green + мягкая градиентная
 * подложка; иначе обычная рамка [DarkBorder]. [accent] перекрашивает рамку.
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderMod = when {
        glow -> Modifier.border(1.dp, SolanaGradient, shape)
        accent != null -> Modifier.border(1.dp, accent.copy(alpha = 0.55f), shape)
        else -> Modifier.border(1.dp, DarkBorder, shape)
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(DarkSurface)
            .then(if (glow) Modifier.background(SolanaGradientSoft) else Modifier)
            .then(borderMod)
            .padding(contentPadding),
        content = content,
    )
}

/** Главная CTA-кнопка с градиентной заливкой purple→green. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    val bg: Modifier = when {
        !enabled -> Modifier.background(DarkSurfaceVar)
        danger -> Modifier.background(DangerRed)
        else -> Modifier.background(SolanaGradient)
    }
    Row(
        modifier = modifier
            .clip(shape)
            .then(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) Color.White else TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.White else TextMuted,
        )
    }
}

/** Компактный чип «метка: значение» для строк метрик. */
@Composable
fun StatChip(
    label: String,
    value: String,
    accent: Color = SolPurple,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
    }
}

/** Маленький статус-бейдж (TP / SL / DEMO / REAL / MONITORING …). */
@Composable
fun StatusBadge(text: String, fg: Color, bg: Color = fg.copy(alpha = 0.12f)) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Пустое состояние: круглая градиентная подложка с иконкой + заголовок + подпись. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(SolanaGradientSoft)
                .border(1.dp, SolanaGradient, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = SolPurple, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/** Заголовок секции с короткой градиентной чертой слева. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SolanaGradient)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * Сегментный переключатель в стиле pill: активный сегмент залит градиентом.
 * Используется в StatisticsScreen (История/Аналитика) и вкладках FilterScreen.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(DarkSurfaceVar)
            .border(1.dp, DarkBorder, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .then(if (selected) Modifier.background(SolanaGradient) else Modifier)
                    .clickable { onSelect(idx) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else TextSecondary,
                )
            }
        }
    }
}
