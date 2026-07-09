package tj.khujand.solana.trading.bot.domain.dars

import tj.khujand.solana.trading.bot.exchange.dex.Candle

/**
 * Ядро методики (Урок 1 + Концепция + Урок 4) для входа в лонг.
 *
 * ВАЖНО (Урок 4): вход выполняется НЕ во время падающей коррекции, а ТОЛЬКО после её
 * завершения — на подтверждающем агрессивном бычьем баре с сильным закрытием.
 * Пока последняя свеча не подтвердила разворот вверх, сетап отклоняется («не ловим нож»).
 *
 * Структура: ...[имп.пред][корр.пред][импульс↑][коррекция↓ (слабая, завершается)] + бар-подтверждение↑
 *
 * Проверяет:
 *  - импульс вверх → слабая контролируемая коррекция вниз;
 *  - доминирование: импульс сильнее коррекции по скорости/размеру баров/объёму;
 *  - «отсутствие второй стороны»: коррекция не сильнее предыдущей, без аномального объёма против нас;
 *  - глубину коррекции (боковая/50%/100%; глубже 100% = тренд сломан → отказ);
 *  - длину коррекции ≥ minCorrectionLenPct% длины импульса (Урок 4);
 *  - ПОДТВЕРЖДЕНИЕ: последняя свеча — агрессивный бычий бар с закрытием у максимума (Урок 4).
 *
 * Бот только покупает (лонг), поэтому импульс = движение ВВЕРХ.
 */
object ImpulseCorrectionAnalyzer {

    /** Минимальная «сила закрытия» подтверждающего бара (доля диапазона: 1.0 — у максимума). */
    private const val CONFIRM_CLOSE_POS = 0.6

    /** Во сколько раз тело подтверждающего бара должно превышать среднее (агрессивность). */
    private const val CONFIRM_BODY_MULT = 1.2

    fun analyze(candles: List<Candle>, legs: List<Leg>, cfg: DarsConfig): DarsSignal {
        val n = legs.size
        if (n < cfg.minLegs) return DarsSignal.reject("мало ног (${n}<${cfg.minLegs})")
        if (candles.size < 3) return DarsSignal.reject("мало свечей для подтверждения")

        // Последняя завершённая коррекция (нога вниз). Допускаем максимум одну зарождающуюся
        // ногу вверх после неё — это и есть начавшийся разворот, на котором мы входим.
        val corrIdx = legs.indexOfLast { it.direction == TrendDirection.DOWN }
        if (corrIdx < 1) return DarsSignal.reject("нет завершённой коррекции для входа")
        if (corrIdx < n - 2) return DarsSignal.reject("коррекция устарела — после неё уже развернулись")

        val curCorr = legs[corrIdx]
        val curImp = legs[corrIdx - 1]
        if (curImp.direction != TrendDirection.UP) {
            return DarsSignal.reject("перед коррекцией нет импульса вверх")
        }
        if (curImp.sizeAbs <= 0.0) return DarsSignal.reject("нулевой импульс")

        // Предыдущие импульс/коррекция для сравнения характера (Ключевая фраза Урока 1).
        val prevCorrIdx = (0 until corrIdx).lastOrNull { legs[it].direction == TrendDirection.DOWN }
        val prevCorr = prevCorrIdx?.let { legs[it] }
        val prevImp = prevCorrIdx?.let { idx ->
            if (idx >= 1 && legs[idx - 1].direction == TrendDirection.UP) legs[idx - 1] else null
        }

        val reasons = ArrayList<String>()
        val warnings = ArrayList<String>()

        // 1) Длина коррекции по времени ≥ порога от длины импульса (Урок 4).
        val lenRatio = if (curImp.bars <= 0) 0.0 else curCorr.bars.toDouble() / curImp.bars * 100.0
        if (lenRatio < cfg.minCorrectionLenPct) {
            return DarsSignal.reject("коррекция коротка: ${lenRatio.toInt()}%<${cfg.minCorrectionLenPct.toInt()}% длины импульса")
        }
        reasons += "длина коррекции ${lenRatio.toInt()}%"

        // 2) Глубина коррекции (ретрейс). Глубже 100% = импульс перекрыт, тренд сломан.
        val retrace = curCorr.sizeAbs / curImp.sizeAbs
        val depth = when {
            retrace <= 0.35 -> CorrectionType.SIDEWAYS
            retrace <= 0.66 -> CorrectionType.HALF
            retrace <= 1.0 -> CorrectionType.FULL
            else -> CorrectionType.DEEP
        }
        if (depth == CorrectionType.DEEP) {
            return DarsSignal.reject("коррекция глубже импульса (${(retrace * 100).toInt()}%) — тренд сломан")
        }
        reasons += "глубина $depth (${(retrace * 100).toInt()}%)"

        // 3) Доминирование: импульс агрессивнее коррекции (скорость и размер баров) — Концепция.
        val speedDom = curImp.speed >= curCorr.speed * cfg.dominanceRatio
        val barDom = curImp.avgBarSize >= curCorr.avgBarSize
        if (!speedDom) {
            return DarsSignal.reject("нет доминирования: импульс не быстрее коррекции в ${cfg.dominanceRatio}×")
        }
        if (!barDom) warnings += "бары коррекции крупнее ожидаемого"

        // 4) Отсутствие второй сильной стороны: нет аномального объёма против нас (Урок 4).
        if (curCorr.maxVolume > curImp.maxVolume * 1.2) {
            return DarsSignal.reject("аномальный объём в коррекции против нас")
        }

        // 5) Коррекция не сильнее предыдущей коррекции (характер не изменился) — Ключевая фраза.
        if (prevCorr != null) {
            if (curCorr.speed > prevCorr.speed * 1.3 || curCorr.sizeAbs > prevCorr.sizeAbs * 1.3) {
                return DarsSignal.reject("текущая коррекция сильнее предыдущей — вторая сторона усилилась")
            }
        }

        // 6) Импульс не должен резко ослабнуть относительно предыдущего (доминант держится).
        if (prevImp != null && prevImp.speed > 0) {
            if (curImp.speed < prevImp.speed * 0.5) {
                warnings += "импульс слабее предыдущего в 2×"
            }
        }

        // 7) ПОДТВЕРЖДЕНИЕ РАЗВОРОТА (Урок 4): последняя свеча — агрессивный бычий бар с сильным
        // закрытием, закрывшийся выше предыдущей. Пока его нет — не входим («не ловим нож»).
        val last = candles.last()
        val prevClose = candles[candles.size - 2].close
        val avgBody = candles.map { it.body }.average().coerceAtLeast(1e-12)
        val confirmed = last.isBullish &&
            last.close > prevClose &&
            last.closePosition >= CONFIRM_CLOSE_POS &&
            last.body >= avgBody * CONFIRM_BODY_MULT
        if (!confirmed) {
            return DarsSignal.reject("нет подтверждающего бара разворота — вход только после коррекции (Урок 4)")
        }
        reasons += "подтверждение: агрессивный бар, закрытие ${(last.closePosition * 100).toInt()}%"

        val score = scoreOf(depth, curImp, curCorr, last, cfg)
        val allReasons = reasons + warnings.map { "⚠ $it" }
        return DarsSignal.pass(DarsSetup.IMPULSE_CORRECTION, TrendDirection.UP, score, *allReasons.toTypedArray())
    }

    private fun scoreOf(depth: CorrectionType, imp: Leg, corr: Leg, confirm: Candle, cfg: DarsConfig): Int {
        var s = 50
        s += when (depth) {
            CorrectionType.SIDEWAYS -> 25
            CorrectionType.HALF -> 20
            CorrectionType.FULL -> 5
            CorrectionType.DEEP -> -50
        }
        if (imp.closeStrength > 0.6) s += 10           // импульсные бары закрываются у максимумов
        if (corr.closeStrength < 0.5) s += 5           // коррекция вялая
        if (confirm.closePosition > 0.7) s += 5        // сильный бар-подтверждение
        val speedRatio = if (corr.speed <= 0) 3.0 else imp.speed / corr.speed
        if (speedRatio >= cfg.dominanceRatio * 1.5) s += 10
        return s.coerceIn(0, 100)
    }
}
