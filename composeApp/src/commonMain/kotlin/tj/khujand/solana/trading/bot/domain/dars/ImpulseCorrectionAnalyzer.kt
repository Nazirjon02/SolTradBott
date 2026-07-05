package tj.khujand.solana.trading.bot.domain.dars

/**
 * Ядро методики (Урок 1 + Концепция + Урок 4) для входа в лонг:
 * импульс вверх → слабая контролируемая коррекция вниз → ожидание продолжения.
 *
 * Проверяет:
 *  - структуру ...[имп.пред][корр.пред][имп.тек][корр.тек(формируется)];
 *  - доминирование: импульс сильнее коррекции по скорости/размеру баров/объёму;
 *  - «отсутствие второй стороны»: коррекция не сильнее предыдущей, без аномального объёма против нас;
 *  - глубину коррекции (боковая/50%/100%; глубже 100% = тренд сломан → отказ);
 *  - длину коррекции ≥ minCorrectionLenPct% длины импульса (Урок 4).
 *
 * Бот только покупает (лонг), поэтому импульс = движение ВВЕРХ.
 */
object ImpulseCorrectionAnalyzer {

    fun analyze(legs: List<Leg>, cfg: DarsConfig): DarsSignal {
        val n = legs.size
        if (n < cfg.minLegs) return DarsSignal.reject("мало ног (${n}<${cfg.minLegs})")

        val curCorr = legs[n - 1]
        if (curCorr.direction != TrendDirection.DOWN) {
            return DarsSignal.reject("нет коррекции — цена в импульсе, ждём откат")
        }
        val curImp = legs[n - 2]
        val prevCorr = legs.getOrNull(n - 3)
        val prevImp = legs.getOrNull(n - 4)
        if (curImp.direction != TrendDirection.UP) {
            return DarsSignal.reject("структура не импульс-вверх → коррекция-вниз")
        }
        if (curImp.sizeAbs <= 0.0) return DarsSignal.reject("нулевой импульс")

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
        if (prevCorr != null && prevCorr.direction == TrendDirection.DOWN) {
            if (curCorr.speed > prevCorr.speed * 1.3 || curCorr.sizeAbs > prevCorr.sizeAbs * 1.3) {
                return DarsSignal.reject("текущая коррекция сильнее предыдущей — вторая сторона усилилась")
            }
        }

        // 6) Импульс не должен резко ослабнуть относительно предыдущего (доминант держится).
        if (prevImp != null && prevImp.direction == TrendDirection.UP && prevImp.speed > 0) {
            if (curImp.speed < prevImp.speed * 0.5) {
                warnings += "импульс слабее предыдущего в 2×"
            }
        }

        val score = scoreOf(depth, curImp, curCorr, cfg)
        val allReasons = reasons + warnings.map { "⚠ $it" }
        return DarsSignal.pass(DarsSetup.IMPULSE_CORRECTION, TrendDirection.UP, score, *allReasons.toTypedArray())
    }

    private fun scoreOf(depth: CorrectionType, imp: Leg, corr: Leg, cfg: DarsConfig): Int {
        var s = 50
        s += when (depth) {
            CorrectionType.SIDEWAYS -> 25
            CorrectionType.HALF -> 20
            CorrectionType.FULL -> 5
            CorrectionType.DEEP -> -50
        }
        if (imp.closeStrength > 0.6) s += 10           // импульсные бары закрываются у максимумов
        if (corr.closeStrength < 0.5) s += 5           // коррекция вялая
        val speedRatio = if (corr.speed <= 0) 3.0 else imp.speed / corr.speed
        if (speedRatio >= cfg.dominanceRatio * 1.5) s += 10
        return s.coerceIn(0, 100)
    }
}
