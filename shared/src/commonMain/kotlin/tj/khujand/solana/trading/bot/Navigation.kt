package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Вкладки приложения. «История» и «Аналитика» — одна вкладка «Сделки»: обе про закрытые сделки, а шесть вкладок
 * на телефоне давали подписи 10 sp.
 */
enum class Screen(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    DASHBOARD("Главная", Icons.Outlined.SpaceDashboard, Icons.Rounded.SpaceDashboard),
    ANALYSIS("Анализ", Icons.Outlined.CandlestickChart, Icons.Rounded.CandlestickChart),
    TRADES("Сделки", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Rounded.ReceiptLong),
    STRATEGIES("Стратегии", Icons.Outlined.SmartToy, Icons.Rounded.SmartToy),
    SETTINGS("Настройки", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/** Метка вкладки в навигации — по ней вкладку находят тесты интерфейса. */
internal fun navTag(screen: Screen): String = "nav-${screen.name}"

/**
 * Нижняя панель навигации (телефон). [badges] — вкладки с точкой-напоминанием (например, несохранённые
 * настройки).
 */
@Composable
fun DrxBottomBar(current: Screen, badges: Set<Screen> = emptySet(), onSelect: (Screen) -> Unit) {
    Surface(color = SurfaceCard) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Screen.entries.forEach { s ->
                    NavItem(s, selected = s == current, badge = s in badges, modifier = Modifier.weight(1f)) { onSelect(s) }
                }
            }
        }
    }
}

/**
 * Боковая панель навигации (широкое окно): на десктопе и планшете вкладки слева, а не растянутой полосой
 * внизу экрана. Сверху — марка и статус бота.
 */
@Composable
fun DrxNavRail(
    current: Screen,
    statusColor: Color,
    statusLabel: String,
    badges: Set<Screen> = emptySet(),
    onSelect: (Screen) -> Unit,
) {
    Surface(color = SurfaceCard, modifier = Modifier.fillMaxHeight()) {
        Row {
            Column(
                // Прокрутка — для телефона в альбомной ориентации: по высоте пять вкладок там не помещаются.
                Modifier.fillMaxHeight().width(92.dp).statusBarsPadding().navigationBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("DRX", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor, 7.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(statusLabel, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Spacer(Modifier.height(14.dp))
                Screen.entries.forEach { s ->
                    NavItem(s, selected = s == current, badge = s in badges, modifier = Modifier.padding(horizontal = 8.dp)) { onSelect(s) }
                }
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(BorderColor))
        }
    }
}

@Composable
private fun NavItem(screen: Screen, selected: Boolean, badge: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (selected) Green else TextSecondary
    Column(
        modifier = modifier.testTag(navTag(screen)).clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Выбранная вкладка — «пилюля» под иконкой, как в Material 3, в цвете приложения.
        Box(
            Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                .background(if (selected) GreenDim else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) screen.selectedIcon else screen.icon,
                contentDescription = screen.label,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
            if (badge) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = 4.dp).size(8.dp).clip(CircleShape).background(Amber))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            screen.label,
            fontSize = 12.sp,
            color = if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
