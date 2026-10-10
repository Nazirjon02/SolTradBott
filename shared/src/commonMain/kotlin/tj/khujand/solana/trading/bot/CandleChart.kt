package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tj.khujand.solana.trading.bot.domain.dars.LevelKind
import tj.khujand.solana.trading.bot.domain.dars.MarkKind
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalysis
import tj.khujand.solana.trading.bot.domain.dars.SetupOverlay
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// График свечей с разметкой разбора (порт CandleChart из MRX). Рисуется на Compose Canvas без
// внешних библиотек: любая графическая библиотека сузила бы набор таргетов KMP, а экран один
// и тот же на desktop, Android и iOS.

/** Меньше этого числа баров в окне не показываем: дальше это уже не график, а пара столбиков. */
private const val MIN_VISIBLE_BARS = 10

/** Шаг масштаба: один щелчок колеса или одно нажатие кнопки. */
private const val ZOOM_STEP = 1.25f

/**
 * Свечи, объём и разметка разбора.
 *
 * Компонент чистый: на вход свечи и [SetupOverlay], внутри ни сети, ни базы. Свечи берутся
 * из тех, что анализатор уже загрузил, — открытие карточки не стоит ни одного запроса
 * к GeckoTerminal.
 *
 * Масштаб и сдвиг окна — щипком, колесом мыши или кнопками в углу. Зум меняет не картинку,
 * а само окно: в кадр попадает меньше баров, и они становятся шире. Диапазон цен считается
 * по видимому куску, иначе на длинной истории движение последних баров осталось бы полоской
 * в пару пикселей.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    overlay: SetupOverlay? = null,
    modifier: Modifier = Modifier,
    /** Высота области цены; гистограмма объёма добавляет ещё четверть сверху этого числа. */
    height: Dp = 180.dp,
    /** Сколько последних баров показывать, пока масштаб не трогали. */
    maxBars: Int = 120,
    /** Подпись проекции под графиком: «вход у … → цель …». Пусто — не рисуем. */
    caption: String? = null,
    /**
     * Последняя свеча ещё формируется. Рисуется контуром, а не заливкой: закрытия у неё
     * ещё нет, и выглядеть как готовый бар она не должна.
     */
    lastBarForming: Boolean = false,
    /** Смена ключа сбрасывает масштаб: другая монета — другое окно. */
    viewportKey: Any? = null,
) {
    if (candles.size < 2) return
    val measurer = rememberTextMeasurer()
    // Окно живёт при карточке, а не при данных: новый разбор не должен сбрасывать масштаб.
    val viewport = remember(viewportKey) { ChartViewport() }
    viewport.attach(total = candles.size, defaultBars = maxBars)
    val range = viewport.range
    val shown = remember(candles, range) { candles.subList(range.first, range.last + 1) }
    val dropped = range.first
    // Индексы разметки — в координатах полного списка; сдвигаем на обрезанную часть.
    val visibleOverlay = remember(overlay, dropped) { overlay?.shifted(-dropped) }
    // Проекция ожидания идёт от последней цены. Когда окно уехало в историю, последняя видимая
    // свеча — не «сейчас», и стрелка из неё врала бы.
    val atRightEdge = viewport.atRightEdge
    val forming = lastBarForming && atRightEdge

    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height + height / 4)) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .chartZoomAndPan(viewport)
                    .drawWithCache {
                        val geometry = ChartGeometry(
                            size = size,
                            candles = shown,
                            overlay = visibleOverlay,
                            projection = atRightEdge,
                            forming = forming,
                            measurer = measurer,
                        )
                        onDrawBehind { geometry.draw(this) }
                    }
            )
            // Кнопки нужны там, где щипка нет, — и заодно показывают, что график живой.
            if (candles.size > MIN_VISIBLE_BARS) {
                ChartZoomControls(viewport, Modifier.align(Alignment.TopStart))
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                caption,
                fontSize = 11.sp,
                color = if (overlay?.armed == true) Green else TextSecondary,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

/**
 * Подпись проекции словами: «Вход у 0.00001234 → цель 0.00001500 (2.0R), стоп 0.00001100».
 *
 * Текст обязателен независимо от цвета стрелки: цветом одним ничего не сообщаем — на маленьком
 * экране серый от зелёного отличается плохо, а «нужно: …» читается всегда.
 */
fun setupCaption(overlay: SetupOverlay): String {
    val entry = overlay.entryLevel
        ?: return overlay.missing?.let { "Нужно: ${it.replaceFirstChar { c -> c.lowercase() }}" } ?: ""
    val target = overlay.takeLevel?.let { take ->
        val rr = overlay.riskReward?.let { " (${fmtOne(it)}R)" } ?: ""
        " → цель ${MarketAnalysis.fmtPrice(take)}$rr"
    } ?: ""
    val stop = overlay.stopLevel?.let { ", стоп ${MarketAnalysis.fmtPrice(it)}" } ?: ""
    // Строчная только первая буква: lowercase() целиком испортил бы тикеры и ТФ в тексте.
    val missing = if (!overlay.armed && overlay.missing != null)
        " · нужно: ${overlay.missing.replaceFirstChar { it.lowercase() }}" else ""
    return "Вход у ${MarketAnalysis.fmtPrice(entry)}$target$stop$missing"
}

private fun fmtOne(v: Double): String {
    val r = kotlin.math.round(v * 10).toLong()
    return "${r / 10}.${abs(r % 10)}"
}

/** «бар / бара / баров» — русское склонение по числу. */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val n100 = abs(n) % 100
    val n10 = n100 % 10
    return when {
        n100 in 11..14 -> many
        n10 == 1 -> one
        n10 in 2..4 -> few
        else -> many
    }
}

// ─── Окно просмотра ───────────────────────────────────────────────────────────

/**
 * Сколько баров видно и насколько окно отодвинуто от правого края.
 *
 * Масштаб хранится в барах, а не матрицей преобразования: при смене окна заново считаются и
 * диапазон цен, и ширина свечи, иначе приближение давало бы мыльную растяжку вместо крупных
 * свечей. Оба поля дробные: щипок и колесо приходят долями бара, и округление на каждом
 * событии съедало бы жест целиком. Сдвиг считаем от ПРАВОГО края: список свечей — скользящее
 * окно, на каждом новом баре всё уезжает влево, и отсчёт слева ломался бы каждый бар.
 */
@Stable
internal class ChartViewport {
    var total: Int = 0
        private set

    var defaultBars: Int = 0
        private set

    private var barsState by mutableFloatStateOf(0f)
    private var offsetState by mutableFloatStateOf(0f)

    /** Масштаб трогали руками: до этого окно следует за [defaultBars], после — за жестами. */
    var touched by mutableStateOf(false)
        private set

    fun attach(total: Int, defaultBars: Int) {
        this.total = total
        this.defaultBars = defaultBars
    }

    private val minBars: Int get() = min(MIN_VISIBLE_BARS, total)

    val bars: Int
        get() = if (!touched) defaultWindow()
        else barsState.roundToInt().coerceIn(minBars, max(minBars, total))

    /** Сколько баров скрыто справа: 0 — окно прижато к последней свече. */
    val offset: Int
        get() = if (!touched) 0
        else offsetState.roundToInt().coerceIn(0, max(0, total - bars))

    val atRightEdge: Boolean get() = offset == 0
    val canZoomIn: Boolean get() = bars > minBars
    val canZoomOut: Boolean get() = bars < total

    /** Видимый кусок в координатах полного списка. */
    val range: IntRange
        get() {
            val b = bars.coerceIn(1, max(1, total))
            val end = (total - offset).coerceIn(b, max(b, total))
            return (end - b) until end
        }

    /** Масштаб вокруг точки: бар под пальцем остаётся на месте. [anchor] — доля ширины, 0..1. */
    fun zoomBy(factor: Float, anchor: Float) {
        if (total <= 0 || !factor.isFinite() || factor <= 0f) return
        val a = if (anchor.isFinite()) anchor.coerceIn(0f, 1f) else 0.5f
        ensureTouched()
        val lo = minBars.toFloat()
        val hi = max(minBars, total).toFloat()
        val oldBars = barsState.coerceIn(lo, hi)
        val anchorBar = total - offsetState - oldBars + a * oldBars
        val newBars = (oldBars / factor).coerceIn(lo, hi)
        barsState = newBars
        offsetState = (total - anchorBar - (1f - a) * newBars).coerceIn(0f, max(0f, total - newBars))
    }

    /** Сдвиг окна: [deltaBars] > 0 — уходим в историю (график тянут вправо). */
    fun panBy(deltaBars: Float) {
        if (total <= 0 || !deltaBars.isFinite()) return
        ensureTouched()
        offsetState = (offsetState + deltaBars).coerceIn(0f, max(0f, total - barsState))
    }

    fun reset() {
        barsState = defaultWindow().toFloat()
        offsetState = 0f
        touched = false
    }

    private fun defaultWindow(): Int = defaultBars.coerceIn(max(1, minBars), max(1, total))

    private fun ensureTouched() {
        if (!touched) {
            barsState = defaultWindow().toFloat()
            offsetState = 0f
            touched = true
        }
    }
}

// ─── Жесты и кнопки ───────────────────────────────────────────────────────────

private fun Modifier.chartZoomAndPan(viewport: ChartViewport): Modifier = this
    .pointerInput(viewport) {
        chartPanZoomGestures(
            onZoom = { x, factor -> viewport.zoomBy(factor, x / size.width.toFloat()) },
            // dx в пикселях → в барах: ширина одного бара это ширина графика на их число.
            onPan = { dx -> viewport.panBy(dx * viewport.bars / size.width.toFloat()) }
        )
    }
    .pointerInput(viewport) {
        chartWheelZoom { x, factor -> viewport.zoomBy(factor, x / size.width.toFloat()) }
    }

/**
 * Щипок — масштаб, горизонтальная протяжка — сдвиг окна. Вертикальную протяжку не трогаем:
 * иначе палец, попавший на график, переставал бы прокручивать сам разбор.
 */
private suspend fun PointerInputScope.chartPanZoomGestures(
    onZoom: (anchorX: Float, factor: Float) -> Unit,
    onPan: (dx: Float) -> Unit,
) = awaitEachGesture {
    var zoomAccum = 1f
    var panAccum = Offset.Zero
    var active = false
    val slop = viewConfiguration.touchSlop

    awaitFirstDown(requireUnconsumed = false)
    do {
        val event = awaitPointerEvent()
        // Жест забрал кто-то другой (список прокрутился) — молча уходим.
        if (event.changes.any { it.isConsumed }) break
        val zoomChange = event.calculateZoom()
        val panChange = event.calculatePan()

        if (!active) {
            zoomAccum *= zoomChange
            panAccum += panChange
            active = if (event.changes.count { it.pressed } > 1) {
                abs(1f - zoomAccum) * event.calculateCentroidSize(useCurrent = false) > slop
            } else {
                // Одним пальцем берёмся за жест, только когда он явно горизонтальный.
                abs(panAccum.x) > slop && abs(panAccum.x) > abs(panAccum.y)
            }
        }
        if (active) {
            val centroid = event.calculateCentroid(useCurrent = false)
            if (zoomChange != 1f && centroid != Offset.Unspecified) onZoom(centroid.x, zoomChange)
            if (panChange.x != 0f) onPan(panChange.x)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    } while (event.changes.any { it.pressed })
}

/** Колесо мыши — масштаб под курсором: на desktop это главный способ, щипка там нет. */
private suspend fun PointerInputScope.chartWheelZoom(onZoom: (anchorX: Float, factor: Float) -> Unit) =
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val dy = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
            if (dy == 0f) continue
            val x = event.changes.firstOrNull()?.position?.x ?: continue
            onZoom(x, if (dy < 0f) ZOOM_STEP else 1f / ZOOM_STEP)
            event.changes.forEach { it.consume() }
        }
    }

@Composable
private fun ChartZoomControls(viewport: ChartViewport, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Масштабируем от правого края: там последняя цена, ради неё график и открывают.
        ChartZoomButton("−", enabled = viewport.canZoomOut) { viewport.zoomBy(1f / ZOOM_STEP, 1f) }
        ChartZoomButton("+", enabled = viewport.canZoomIn) { viewport.zoomBy(ZOOM_STEP, 1f) }
        if (viewport.touched) {
            ChartZoomButton("сброс") { viewport.reset() }
            Text("${viewport.bars} из ${viewport.total}", fontSize = 9.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun ChartZoomButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(SurfaceCard.copy(alpha = 0.88f), shape)
            .border(1.dp, BorderColor, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (enabled) TextPrimary else TextSecondary,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─── Геометрия ────────────────────────────────────────────────────────────────

/**
 * Все вычисления графика в одном месте: масштабы, координаты, готовые тексты. Создаётся в
 * блоке кэша `drawWithCache` — при смене данных или размера, а не на каждый кадр.
 */
private class ChartGeometry(
    private val size: Size,
    private val candles: List<Candle>,
    private val overlay: SetupOverlay?,
    /** Рисовать стрелку ожидания. false — окно уехало в историю, и «отсюда к цели» уже неправда. */
    private val projection: Boolean,
    /** Последняя видимая свеча — формирующаяся. */
    private val forming: Boolean,
    private val measurer: TextMeasurer,
) {
    private val labelStyle = TextStyle(fontSize = 9.sp, color = TextSecondary)
    private val levelStyle = TextStyle(fontSize = 9.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
    private val waveStyle = TextStyle(fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Bold)

    private val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))

    /** Область цены: верхние ~4/5, остальное — объём. */
    private val priceHeight = size.height * 0.78f
    private val volumeTop = priceHeight + 6f
    private val volumeHeight = (size.height - volumeTop).coerceAtLeast(1f)

    private val minPrice: Float
    private val maxPrice: Float

    init {
        val range = chartPriceRange(candles, overlay)
        minPrice = range.first
        maxPrice = range.second
    }

    private val priceSpan = (maxPrice - minPrice).takeIf { it > 0f } ?: 1f
    private val maxVolume = candles.maxOf { it.volume }.takeIf { it > 0.0 } ?: 1.0

    /** Правая полоса под проекцию: туда уходит стрелка ожидания и окно входа в будущее. */
    private val projectionWidth = if (overlay?.entryLevel != null) size.width * 0.22f else 0f
    private val plotWidth = (size.width - projectionWidth).coerceAtLeast(1f)
    private val drawRight = size.width

    private val stepX = plotWidth / candles.size
    private val bodyWidth = (stepX * 0.62f).coerceAtLeast(1f)

    /**
     * Бар, от которого считается окно входа. Последний — даже формирующийся: разбор DRX берёт
     * свечи GeckoTerminal вместе с текущим баром, и правило свежести («не больше N баров с конца
     * коррекции») считает и его. Иначе график обещал бы на бар больше, чем разрешит разбор.
     */
    private val lastClosed = candles.lastIndex

    /*
     * Цены мемкоинов — 1e-5…1e-8, а свечи друг от друга отличаются в шестом-седьмом знаке:
     * разность таких чисел во Float теряет точность. Поэтому y считаем в Double относительно
     * минимума и во Float переводим уже готовую долю.
     */
    private val minD = minPrice.toDouble()
    private val spanD = priceSpan.toDouble()

    private fun y(price: Double): Float =
        priceHeight - (((price - minD) / spanD).toFloat()) * priceHeight

    private fun x(index: Int): Float = index * stepX + stepX / 2f

    private fun inPrice(yy: Float) = yy in 0f..priceHeight

    /** Подписи ног, только если бары достаточно широкие: на 120 барах телефона буквы налезут. */
    private val showWaveLabels = stepX >= 4f && priceHeight >= 110f

    private class Tag(val text: TextLayoutResult, val y: Float, val color: Color)

    /** Ценники у правого края: вход, стоп, цель и живая цена формирующегося бара. */
    private val tags: List<Tag> = buildList {
        fun tag(price: Double?, color: Color) {
            val p = price ?: return
            val yy = y(p)
            if (!inPrice(yy)) return
            add(Tag(measurer.measure(MarketAnalysis.fmtPrice(p), levelStyle), yy, color))
        }
        overlay?.let { o ->
            tag(o.entryLevel, Amber)
            tag(o.stopLevel, Red)
            tag(o.takeLevel, Green)
        }
        if (forming) {
            val last = candles.last()
            tag(last.close, if (last.close >= last.open) Green else Red)
        }
    }

    private class Caption(val text: TextLayoutResult, val topLeft: Offset)

    /**
     * Подписи разметки без наложений: каждая пробует несколько мест и занимает первое свободное,
     * не нашлось — пропускается. Порядок по важности: отмеченные бары и окно входа говорят
     * «что сейчас», ноги — «почему», уровни — фон.
     */
    private val captions: List<Caption> = buildList {
        if (priceHeight < 80f) return@buildList
        val taken = mutableListOf<Rect>()
        fun place(layout: TextLayoutResult, candidates: List<Offset>) {
            val w = layout.size.width.toFloat()
            val h = layout.size.height.toFloat()
            for (c in candidates) {
                val left = c.x.coerceIn(0f, max(0f, drawRight - w))
                val top = c.y.coerceIn(0f, max(0f, priceHeight - h))
                val rect = Rect(left - 2f, top - 1f, left + w + 2f, top + h + 1f)
                if (taken.none { it.overlaps(rect) }) {
                    taken += rect
                    add(Caption(layout, Offset(left, top)))
                    return
                }
            }
        }

        // 1. Отмеченные бары — над баром, иначе под ним.
        overlay?.bars.orEmpty().forEach { mark ->
            if (mark.index !in candles.indices) return@forEach
            val c = candles[mark.index]
            val layout = measurer.measure(
                mark.label, labelStyle.copy(color = barColor(mark.good), fontWeight = FontWeight.Bold)
            )
            val cx = x(mark.index) - layout.size.width / 2f
            place(layout, listOf(
                Offset(cx, y(c.high) - layout.size.height - 5f),
                Offset(cx, y(c.low) + 5f),
                Offset(cx - layout.size.width / 2f, y(c.high) - layout.size.height * 2f - 6f)
            ))
        }

        // 2. Окно входа: «окно входа · ещё 3 бара» либо «окно входа закрыто».
        overlay?.entryWindow?.let { w ->
            if (w.fromIndex > lastClosed) return@let
            val left = w.toIndex - lastClosed
            val text = when {
                left < 0 -> "окно входа закрыто"
                left == 0 -> "окно входа · последний бар"
                else -> "окно входа · ещё $left ${plural(left, "бар", "бара", "баров")}"
            }
            val layout = measurer.measure(text, labelStyle.copy(color = Amber, fontWeight = FontWeight.Bold))
            val xLeft = x(w.fromIndex.coerceAtLeast(0)) - stepX / 2f
            place(layout, listOf(
                Offset(xLeft, y(w.high) - layout.size.height - 2f),
                Offset(xLeft, y(w.low) + 2f),
                Offset(xLeft - layout.size.width, y(w.high) - layout.size.height - 2f)
            ))
        }

        // 3. Ноги структуры — с конца: текущие импульс и коррекция важнее прошлых.
        if (showWaveLabels) overlay?.waves.orEmpty().asReversed().forEach { mark ->
            if (mark.label.isBlank()) return@forEach
            if (mark.toIndex !in candles.indices || mark.fromIndex !in candles.indices) return@forEach
            val falling = mark.toPrice < mark.fromPrice
            val layout = measurer.measure(mark.label, waveStyle.copy(color = markColor(mark.kind)))
            val cx = (x(mark.fromIndex) + x(mark.toIndex)) / 2f - layout.size.width / 2f
            val mid = y((mark.fromPrice + mark.toPrice) / 2)
            val below = mid + 4f
            val above = mid - layout.size.height - 4f
            val (first, second) = if (falling) below to above else above to below
            val shift = layout.size.width * 0.5f
            place(layout, listOf(
                Offset(cx, first), Offset(cx - shift, first), Offset(cx + shift, first),
                Offset(cx, second), Offset(cx - shift, second), Offset(cx + shift, second)
            ))
        }

        // 4. Уровни — у левого края, над линией или под ней.
        overlay?.levels.orEmpty().forEach { level ->
            val ly = y(level.price)
            if (!inPrice(ly)) return@forEach
            val layout = measurer.measure(level.label, labelStyle.copy(color = levelColor(level.kind)))
            place(layout, listOf(
                Offset(2f, ly - layout.size.height - 1f),
                Offset(2f, ly + 1f),
                Offset(plotWidth - layout.size.width, ly - layout.size.height - 1f)
            ))
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        drawZones()
        drawEntryWindow()
        drawStrategyLevels()
        drawCandles()
        drawVolume()
        drawBarMarks()
        drawWaves()
        drawLevels()
        drawProjection()
        drawLivePrice()
        drawCaptions()
        drawTags()
    }

    private fun markColor(kind: MarkKind): Color = when (kind) {
        MarkKind.IMPULSE -> Blue
        MarkKind.CORRECTION -> Purple
    }

    private fun levelColor(kind: LevelKind): Color = when (kind) {
        LevelKind.SUPPORT -> Green
        LevelKind.RESISTANCE -> Red
    }

    private fun barColor(good: Boolean?): Color = when (good) {
        true -> Green
        false -> Red
        null -> Blue
    }

    /** Зона риска красным от стопа вниз и зона прибыли зелёным от входа до цели. */
    private fun DrawScope.drawZones() {
        val o = overlay ?: return
        o.stopLevel?.let { stop ->
            val top = y(stop).coerceIn(0f, priceHeight)
            drawRect(
                color = Red.copy(alpha = 0.09f),
                topLeft = Offset(0f, top),
                size = Size(drawRight, (priceHeight - top).coerceAtLeast(0f))
            )
        }
        val entry = o.entryLevel ?: return
        o.takeLevel?.let { take ->
            val yEntry = y(entry)
            val yTake = y(take)
            drawRect(
                color = Green.copy(alpha = 0.07f),
                topLeft = Offset(0f, min(yEntry, yTake)),
                size = Size(drawRight, abs(yTake - yEntry))
            )
        }
    }

    /**
     * Окно входа: прямоугольник «где и до какого бара» вход ещё свежий. Уходит в полосу
     * проекции, если ещё открыто, и сереет, если уже закрылось.
     */
    private fun DrawScope.drawEntryWindow() {
        val w = overlay?.entryWindow ?: return
        val open = w.toIndex >= lastClosed
        val color = if (open) Amber else TextSecondary
        val left = (x(w.fromIndex.coerceAtLeast(0)) - stepX / 2f).coerceIn(0f, drawRight)
        // Окно в будущем рисуем, только когда видим «сейчас»: из истории будущего не видно.
        val rightRaw = x(w.toIndex) + stepX / 2f
        val right = (if (projection) rightRaw else min(rightRaw, plotWidth)).coerceIn(left, drawRight)
        val top = y(w.high).coerceIn(0f, priceHeight)
        val bottom = y(w.low).coerceIn(0f, priceHeight)
        if (right <= left || bottom <= top) return
        drawRect(color.copy(alpha = if (open) 0.12f else 0.06f), Offset(left, top), Size(right - left, bottom - top))
        drawRect(
            color.copy(alpha = if (open) 0.8f else 0.4f), Offset(left, top), Size(right - left, bottom - top),
            style = Stroke(width = 1.2f, pathEffect = dashed)
        )
    }

    /** Уровни поддержки и сопротивления — тонкими линиями через весь график. */
    private fun DrawScope.drawStrategyLevels() {
        overlay?.levels.orEmpty().forEach { level ->
            val ly = y(level.price)
            if (!inPrice(ly)) return@forEach
            drawLine(
                levelColor(level.kind).copy(alpha = 0.5f),
                Offset(0f, ly), Offset(drawRight, ly),
                strokeWidth = 1.1f
            )
        }
    }

    private fun DrawScope.drawCandles() {
        candles.forEachIndexed { i, candle ->
            val cx = x(i)
            val color = if (candle.isBullish) Green else Red
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(cx, y(candle.high)),
                end = Offset(cx, y(candle.low)),
                strokeWidth = 1f
            )
            val top = y(max(candle.open, candle.close))
            val bottom = y(min(candle.open, candle.close))
            // Дожи без высоты не рисуется вовсе — даём минимальную толщину.
            val bodySize = Size(bodyWidth, (bottom - top).coerceAtLeast(1f))
            val topLeft = Offset(cx - bodyWidth / 2f, top)
            if (forming && i == candles.lastIndex) {
                drawRect(color.copy(alpha = 0.18f), topLeft, bodySize)
                drawRect(color, topLeft, bodySize, style = Stroke(width = 1.2f))
            } else {
                drawRect(color = color, topLeft = topLeft, size = bodySize)
            }
        }
    }

    private fun DrawScope.drawVolume() {
        candles.forEachIndexed { i, candle ->
            val h = (candle.volume / maxVolume * volumeHeight).toFloat()
            val alpha = if (forming && i == candles.lastIndex) 0.18f else 0.35f
            drawRect(
                color = (if (candle.isBullish) Green else Red).copy(alpha = alpha),
                topLeft = Offset(x(i) - bodyWidth / 2f, volumeTop + volumeHeight - h),
                size = Size(bodyWidth, h.coerceAtLeast(1f))
            )
        }
    }

    /** Отмеченные бары — рамкой вокруг всего диапазона бара. */
    private fun DrawScope.drawBarMarks() {
        overlay?.bars.orEmpty().forEach { mark ->
            if (mark.index !in candles.indices) return@forEach
            val c = candles[mark.index]
            val half = max(bodyWidth / 2f + 2f, 3f)
            val top = y(c.high) - 3f
            val bottom = y(c.low) + 3f
            drawRoundRect(
                barColor(mark.good),
                topLeft = Offset(x(mark.index) - half, top),
                size = Size(half * 2f, bottom - top),
                cornerRadius = CornerRadius(3f, 3f),
                style = Stroke(width = 1.5f)
            )
        }
    }

    private fun DrawScope.drawWaves() {
        overlay?.waves.orEmpty().forEach { mark ->
            if (mark.fromIndex !in candles.indices || mark.toIndex !in candles.indices) return@forEach
            // Подписанные ноги — те, по которым идёт сравнение; остальные — фон структуры.
            val main = mark.label.isNotBlank()
            drawLine(
                color = markColor(mark.kind).copy(alpha = if (main) 0.95f else 0.45f),
                start = Offset(x(mark.fromIndex), y(mark.fromPrice)),
                end = Offset(x(mark.toIndex), y(mark.toPrice)),
                strokeWidth = if (main) 2f else 1.2f
            )
            if (main) {
                // Точки свингов: по ним анализатор и режет структуру на ноги.
                drawCircle(markColor(mark.kind), 2.5f, Offset(x(mark.fromIndex), y(mark.fromPrice)))
                drawCircle(markColor(mark.kind), 2.5f, Offset(x(mark.toIndex), y(mark.toPrice)))
            }
        }
    }

    private fun DrawScope.drawLevels() {
        val o = overlay ?: return
        fun level(price: Double?, color: Color) {
            val p = price ?: return
            val ly = y(p)
            drawLine(color, Offset(0f, ly), Offset(drawRight, ly), strokeWidth = 1f, pathEffect = dashed)
        }
        level(o.entryLevel, Amber)
        level(o.stopLevel, Red)
        level(o.takeLevel, Green)
    }

    /**
     * Проекция ожидания: пунктир от последней цены через уровень входа к цели. Зелёная, когда
     * сетап готов, и серая, пока чего-то не хватает, — плюс подпись под графиком словами.
     */
    private fun DrawScope.drawProjection() {
        if (!projection) return
        val o = overlay ?: return
        val entry = o.entryLevel ?: return
        if (projectionWidth <= 0f) return

        val color = if (o.armed) Green else TextSecondary
        val startX = plotWidth
        val endX = drawRight - 4f
        val yNow = y(candles.last().close)
        val yEntry = y(entry)
        val yTarget = o.takeLevel?.let { y(it) } ?: yEntry

        val path = Path().apply {
            moveTo(startX, yNow)
            quadraticTo((startX + endX) / 2f, yEntry, endX, yTarget)
        }
        drawPath(path, color, style = Stroke(width = 1.5f, pathEffect = dashed))

        val dir = if (yTarget <= yNow) 1f else -1f
        drawLine(color, Offset(endX, yTarget), Offset(endX - 7f, yTarget + 7f * dir), strokeWidth = 2f)
        drawLine(color, Offset(endX, yTarget), Offset(endX - 7f, yTarget - 3f * dir), strokeWidth = 2f)
    }

    private fun DrawScope.drawCaptions() {
        captions.forEach { c -> drawText(c.text, topLeft = c.topLeft) }
    }

    /** Живая цена формирующегося бара: пунктир через весь график на уровне последнего закрытия. */
    private fun DrawScope.drawLivePrice() {
        if (!forming) return
        val last = candles.last()
        val ly = y(last.close)
        if (!inPrice(ly)) return
        val color = if (last.isBullish) Green else Red
        drawLine(
            color.copy(alpha = 0.7f), Offset(0f, ly), Offset(drawRight, ly), strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f))
        )
    }

    /** Ценники у правого края. Налезающие сдвигаем: читаться должны оба числа. */
    private fun DrawScope.drawTags() {
        var lastBottom = -100f
        tags.sortedBy { it.y }.forEach { tag ->
            val h = tag.text.size.height.toFloat()
            val top = max(tag.y - h / 2f, lastBottom + 2f).coerceIn(0f, max(0f, priceHeight - h))
            val w = tag.text.size.width.toFloat()
            // Подложка: ценник стоит поверх свечей и проекции.
            drawRoundRect(
                BgDark.copy(alpha = 0.72f), Offset(size.width - w - 4f, top), Size(w + 4f, h),
                CornerRadius(3f, 3f)
            )
            drawText(tag.text, topLeft = Offset(size.width - w - 2f, top), color = tag.color)
            lastBottom = top + h
        }
    }
}

/**
 * Видимый диапазон цен: свечи плюс уровни сетапа и запас по краям. Стоп, уехавший за нижний
 * край, читается как «стопа нет», а цель за верхним — как «цели нет».
 */
internal fun chartPriceRange(candles: List<Candle>, overlay: SetupOverlay?): Pair<Float, Float> {
    var lo = candles.minOf { it.low }
    var hi = candles.maxOf { it.high }
    val extra = listOfNotNull(overlay?.entryLevel, overlay?.stopLevel, overlay?.takeLevel) +
        listOfNotNull(overlay?.entryWindow?.low, overlay?.entryWindow?.high)
    extra.forEach {
        if (it > 0.0 && it.isFinite()) { lo = min(lo, it); hi = max(hi, it) }
    }
    val pad = ((hi - lo) * 0.06).takeIf { it > 0.0 } ?: (hi * 0.001 + 1e-12)
    return (lo - pad).toFloat() to (hi + pad).toFloat()
}
