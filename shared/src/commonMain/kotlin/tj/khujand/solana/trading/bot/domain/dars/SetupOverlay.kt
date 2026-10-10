package tj.khujand.solana.trading.bot.domain.dars

/**
 * Разметка разбора поверх свечей (порт SetupOverlay из MRX): что анализатор увидел на графике
 * и чего ждёт. Бот спотовый и только лонговый, поэтому стороны сделки здесь нет.
 *
 * Модель — только числа и факты, без текстов подписей к уровням входа и без цветов: график —
 * чистый компонент, а формулировки («вход у … → цель …») собирает UI, у которого есть
 * форматирование цен под масштаб монеты.
 *
 * Индексы баров — позиции в том же списке свечей, по которому считался разбор
 * ([MarketAnalyzerService.cachedCandles] отдаёт именно его).
 */

/** Роль ноги на графике — от неё зависит цвет линии. */
enum class MarkKind {
    /** Нога вверх — по тренду лонгового бота. */
    IMPULSE,
    /** Нога вниз — коррекция. */
    CORRECTION,
}

/** Одна нога структуры: откуда, куда и подпись. Пустая подпись — фоновая нога без текста. */
data class WaveMark(
    val label: String,
    val fromIndex: Int,
    val toIndex: Int,
    val fromPrice: Double,
    val toPrice: Double,
    val kind: MarkKind,
)

/** Чем горизонтальный уровень является для разбора. */
enum class LevelKind { SUPPORT, RESISTANCE }

/**
 * Уровень, от которого анализатор считает вход и цель. Уровни берутся со старшего ТФ, поэтому
 * привязки к бару у них нет — линия идёт через весь график.
 */
data class ChartLevel(
    val price: Double,
    /** «поддержка · swing» — короткая подпись у линии. */
    val label: String,
    val kind: LevelKind,
)

/**
 * Бар, на который разбор смотрит особо: сигнальный агрессивный бар (good = true) или бар
 * с аномальным объёмом против нас внутри коррекции (good = false).
 */
data class BarMark(
    val index: Int,
    val label: String,
    val good: Boolean? = null,
)

/**
 * Окно входа: в каком коридоре цен и до какого бара разбор считает вход свежим.
 * [toIndex] может быть больше последнего бара — окно ещё открыто и уходит в будущее.
 */
data class EntryWindow(
    val fromIndex: Int,
    val toIndex: Int,
    val low: Double,
    val high: Double,
)

data class SetupOverlay(
    /** Ноги структуры: текущие импульс и коррекция подписаны, прошлые — фон. */
    val waves: List<WaveMark> = emptyList(),
    val entryLevel: Double? = null,
    val stopLevel: Double? = null,
    val takeLevel: Double? = null,
    /** Сетап готов (READY): запретов нет и триггер сформирован. */
    val armed: Boolean = false,
    /** Первый запрет Урока 4 — «чего не хватает». null — запретов нет. */
    val missing: String? = null,
    val levels: List<ChartLevel> = emptyList(),
    val bars: List<BarMark> = emptyList(),
    val entryWindow: EntryWindow? = null,
) {
    /** Отношение хода до цели к ходу до стопа. null — уровней не хватает. */
    val riskReward: Double?
        get() {
            val entry = entryLevel ?: return null
            val stop = stopLevel ?: return null
            val take = takeLevel ?: return null
            val risk = entry - stop
            if (risk <= 0.0) return null
            return (take - entry) / risk
        }

    /**
     * Та же разметка с индексами баров, сдвинутыми на [by]. Нужна, когда график рисует
     * обрезанное окно свечей, а не весь список разбора. Цены при сдвиге не меняются.
     */
    fun shifted(by: Int): SetupOverlay {
        if (by == 0) return this
        return copy(
            waves = waves.map { it.copy(fromIndex = it.fromIndex + by, toIndex = it.toIndex + by) },
            bars = bars.map { it.copy(index = it.index + by) },
            entryWindow = entryWindow?.let { it.copy(fromIndex = it.fromIndex + by, toIndex = it.toIndex + by) },
        )
    }
}
