package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Общие элементы экранов: одна сетка, одни отступы и одни цвета состояний на всех вкладках.

/** Окраска сообщения: что оно значит для пользователя. */
enum class Tone(val color: Color) {
    OK(Green),
    INFO(Blue),
    WARN(Amber),
    BAD(Red),
    NEUTRAL(TextSecondary),
}

private fun Tone.icon(): ImageVector = when (this) {
    Tone.OK -> Icons.Rounded.CheckCircle
    Tone.WARN -> Icons.Rounded.WarningAmber
    Tone.BAD -> Icons.Rounded.ErrorOutline
    Tone.INFO, Tone.NEUTRAL -> Icons.Rounded.Info
}

/**
 * Колонка страницы: на телефоне — во всю ширину с полями 16 dp, на широком окне — не шире [maxWidth] и по центру.
 * Все блоки экрана стоят по одной сетке.
 */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ContentMaxWidth,
    horizontalPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = horizontalPadding),
            content = content
        )
    }
}

/**
 * Шапка экрана: крупный заголовок, строка пояснения и действия справа. Отступ под статус-бар — здесь, чтобы
 * экраны не повторяли его каждый по-своему.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = TextSecondary,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
    }
}

/** Карточка: фон, скругление и внутренние поля одинаковые на всех экранах. */
@Composable
fun DrxCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = SurfaceCard,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .let { m -> borderColor?.let { m.border(1.dp, it, shape) } ?: m }
            .let { m -> onClick?.let { m.clip(shape).clickable { it() } } ?: m },
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Заголовок карточки: иконка, название, необязательное пояснение и элемент справа. */
@Composable
fun CardTitle(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = TextSecondary,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
    }
}

/** Сообщение на подложке своего цвета: состояние, предупреждение, подсказка. */
@Composable
fun Banner(text: String, tone: Tone = Tone.INFO, modifier: Modifier = Modifier, icon: ImageVector? = tone.icon()) {
    Surface(color = tone.color.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tone.color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 12.sp, color = tone.color, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        }
    }
}

/** Пояснение под настройкой или блоком — мелким серым, с нормальным межстрочием. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = TextSecondary) {
    Text(text, fontSize = 12.sp, color = color, lineHeight = 17.sp, modifier = modifier)
}

/** Плашка-метка: «DEMO», «5m», «ГОТОВ». */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Кнопка с иконкой. [filled] — главное действие экрана (залита цветом), иначе — подложка 15% и текст цвета.
 * Выключенная кнопка остаётся читаемой: серый текст на карточке, а не тёмный на тёмном.
 */
@Composable
fun ActionButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> SurfaceRaised.copy(alpha = 0.6f)
        filled -> color
        else -> color.copy(alpha = 0.15f)
    }
    val fg = when {
        !enabled -> TextSecondary.copy(alpha = 0.7f)
        filled -> Color.Black
        else -> color
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Квадратная кнопка-иконка с рамкой (правка, удаление, бэктест). [label] — для читалки экрана. */
@Composable
fun IconSquareButton(icon: ImageVector, label: String, tint: Color = TextSecondary, onClick: () -> Unit) {
    Surface(
        color = BgDark,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.size(38.dp).border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)).clickable { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

/** Сегменты-переключатель («История / Аналитика», режим счёта). Выбранный сегмент приподнят. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceCard).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            val accent = colors?.getOrNull(i) ?: TextPrimary
            Surface(
                color = if (isSelected) SurfaceRaised else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(i) },
            ) {
                Text(
                    label,
                    color = if (isSelected) accent else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                )
            }
        }
    }
}

/** Переключатель с названием и пояснением — строка настроек. */
@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = if (enabled) TextPrimary else TextSecondary, fontWeight = FontWeight.Medium)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Green,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BgDark,
                uncheckedBorderColor = BorderColor,
            ),
        )
    }
}

/** Тонкая полоска заполнения: доля дневного лимита, путь цены от стопа к цели. */
@Composable
fun ProgressMeter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(BorderColor)) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/** Цветная точка состояния. */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * Поле для секрета (seed-фраза, токены, ключи): скрыто точками, глаз показывает. [hint] — строка под полем
 * (подсказка или ошибка при [isError]).
 */
@Composable
fun SecretField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    hint: String? = null,
    isError: Boolean = false,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = TextSecondary.copy(alpha = 0.8f), fontSize = 13.sp) } },
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Icon(
                if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = if (shown) "Скрыть" else "Показать",
                tint = TextSecondary,
                modifier = Modifier.clip(CircleShape).clickable { shown = !shown }.padding(8.dp),
            )
        },
        supportingText = hint?.let { { Text(it, fontSize = 12.sp, lineHeight = 16.sp) } },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp),
    )
}

/**
 * Числовое поле: цифровая клавиатура, единица справа («%», «мин»), ошибка под полем. Текст хранит вызывающий —
 * чтобы «1.» не превращалось в «1» на середине ввода.
 */
@Composable
fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    hint: String? = null,
    error: String? = null,
    allowDecimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.replace(',', '.').filter { it.isDigit() || (allowDecimal && it == '.') }) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = suffix?.let { { Text(it, color = TextSecondary, fontSize = 13.sp) } },
        supportingText = (error ?: hint)?.let { { Text(it, fontSize = 12.sp, lineHeight = 16.sp) } },
        isError = error != null,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp),
    )
}

/**
 * Подтверждение необратимого или рискованного действия. [confirmEnabled] = false — кнопка видна, но не жмётся
 * (например, REAL без seed-фразы): пользователь видит, чего не хватает.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    confirmColor: Color = Red,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Отмена",
    icon: ImageVector? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised,
        icon = icon?.let { { Icon(it, contentDescription = null, tint = confirmColor) } },
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
                extra()
            }
        },
        confirmButton = {
            ActionButton(confirmLabel, confirmColor, filled = true, enabled = confirmEnabled, onClick = onConfirm)
        },
        dismissButton = {
            ActionButton(dismissLabel, TextSecondary, onClick = onDismiss)
        },
    )
}
