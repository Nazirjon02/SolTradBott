package tj.khujand.solana.trading.bot.core.engine

import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig

/**
 * Состояние открытой позиции, нужное для решения о выходе (подмножество строки trade).
 * Общая модель для живого [TradeMonitor] и бэктеста — чтобы оба считали выходы одинаково.
 *
 * @param peakAtMs когда цена последний раз обновила пик; 0 — неизвестно (старые сделки) — считаем от входа.
 */
data class PositionState(
    val entryPrice: Double,
    val initialStop: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val peakPrice: Double,
    val tp1Done: Boolean,
    val tp2Done: Boolean,
    val entryLiquidityUsd: Double,
    val entryAtrFrac: Double,
    val openedAtMs: Long,
    val peakAtMs: Long = 0L,
)

/** Что сделать с позицией на этой цене. */
sealed interface ExitAction {
    data object Hold : ExitAction

    /**
     * Закрыть весь остаток.
     * @param level уровень, пересечение которого вызвало выход (стоп/тейк); null — выход по рынку
     *   (ликвидность-стоп, тайм-стоп). Живой бот продаёт по рынку, бэктест — по уровню.
     */
    data class CloseAll(val reason: String, val level: Double?) : ExitAction

    /**
     * Частичная фиксация [portionOfOriginal]% исходного объёма.
     * @param stopAfter куда перенести стоп после успешной фиксации (безубыток после TP1), null — не трогать.
     */
    data class ClosePart(
        val portionOfOriginal: Double,
        val reason: String,
        val tp1: Boolean,
        val tp2: Boolean,
        val level: Double,
        val stopAfter: Double?,
    ) : ExitAction
}

/**
 * Решение по позиции: новый пик (и когда он обновлён), стоп (после безубытка/трейлингов) и действие.
 * @param breakEvenMoved стоп перенесён в безубыток по % прибыли (для записи в ленту активности).
 */
data class ExitDecision(
    val peak: Double,
    val stop: Double,
    val breakEvenMoved: Boolean,
    val action: ExitAction,
    val peakAtMs: Long = 0L,
)

/**
 * Правила выхода в порядке приоритета (см. [TradeMonitor]): ликвидность-стоп → пик → безубыток (по %
 * и после TP1) → трейлинг (%, ATR и ступенчатый) → Stop Loss → R-лестница → основной TP → % лестница →
 * тайм-стоп → «нет нового максимума».
 * Чистая функция: без БД, сети и часов — время передаётся в [nowMs].
 *
 * @param liquidity текущая ликвидность пула; null — неизвестна (ликвидность-стоп пропускается).
 */
fun decideExit(
    p: PositionState,
    config: StrategyConfig,
    price: Double,
    liquidity: Double?,
    nowMs: Long,
): ExitDecision {
    // Пик цены — база трейлинга; время пика — база выхода «нет нового максимума».
    val peak = maxOf(p.peakPrice, price)
    val peakAt = if (price > p.peakPrice) nowMs else maxOf(p.peakAtMs, p.openedAtMs)
    val sinceHighMs = nowMs - peakAt
    var stop = p.stopLoss
    var breakEvenMoved = false

    fun decision(action: ExitAction) = ExitDecision(peak, stop, breakEvenMoved, action, peakAt)

    // 1. Ликвидность-стоп (rug pull): пул осушён — выходим немедленно.
    if (liquidity != null && p.entryLiquidityUsd > 0) {
        val dropPct = (1 - liquidity / p.entryLiquidityUsd) * 100
        if (dropPct >= config.liquidityExitDropPercent) {
            return decision(ExitAction.CloseAll("Ликвидность-стоп (-${dropPct.toInt()}% пула)", level = null))
        }
    }

    val entry = p.entryPrice
    val gainPct = (price - entry) / entry * 100
    // Прибыль в R — база выходов в R (лестница, активация ATR-трейлинга).
    val r = riskPerUnit(entry, p.initialStop, config.stopLossPercent)
    val gainR = gainInR(price, entry, r)
    val breakEvenStop = entry * (1 + config.breakEvenOffsetPercent / 100)

    // 5. Break-even: после X% прибыли SL переносится в безубыток + offset.
    if (config.breakEvenEnabled && gainPct >= config.breakEvenTriggerPercent && breakEvenStop > stop) {
        stop = breakEvenStop
        breakEvenMoved = true
    }

    // 5b. Безубыток после TP1 (подстраховка: обычно переносится сразу при фиксации TP1).
    if (config.breakEvenAfterTp1 && p.tp1Done && breakEvenStop > stop) {
        stop = breakEvenStop
    }

    // 6. Trailing: после активации SL тянется за пиком.
    if (config.trailingStopEnabled && gainPct >= config.trailingActivationPercent) {
        stop = maxOf(stop, peak * (1 - config.trailingStopPercent / 100))
    }

    // 6b. ATR-трейлинг (Chandelier): дистанция от пика по волатильности входа, после N×R прибыли.
    if (config.atrTrailEnabled && gainR >= config.atrTrailActivationR) {
        stop = maxOf(stop, atrTrailStop(peak, p.entryAtrFrac, config.atrTrailMult, config.trailingStopPercent / 100))
    }

    // 6c. Ступенчатый трейлинг «по иксам»: отступ от пика тем уже, чем больше иксов взято, и ещё
    // уже, когда новый максимум давно не обновлялся (сужение — только при включённом stale-выходе).
    if (config.tieredTrailEnabled && entry > 0) {
        val basePct = tieredTrailPercent(
            peak / entry, config.trailTier1Pct, config.trailTier2Pct, config.trailTier3Pct, config.trailTier4Pct,
        )
        val steps = if (config.staleExitEnabled) staleSteps(sinceHighMs, config.staleExitMinutes) else 0
        stop = maxOf(stop, peak * (1 - tightenedTrailPercent(basePct, steps) / 100))
    }

    // 2. Stop Loss (обычный / трейлинг / безубыток — какой выше).
    if (price <= stop) {
        val reason = if (stop > entry) "Trailing Stop" else "Stop Loss"
        return decision(ExitAction.CloseAll(reason, level = stop))
    }

    // 4a. R-лестница проверяется ДО основного TP: иначе TP у близкого сопротивления
    // закрывал бы всю позицию раньше, чем сработают ступени.
    if (config.rLadderEnabled) {
        when (rLadderStep(gainR, p.tp1Done, p.tp2Done, config.tp1R, config.tp2R)) {
            1 -> return decision(
                ExitAction.ClosePart(
                    portionOfOriginal = config.tp1ClosePercent,
                    reason = "TP1 +${fmtNum(config.tp1R)}R",
                    tp1 = true, tp2 = false,
                    level = entry + config.tp1R * r,
                    stopAfter = if (config.breakEvenAfterTp1) breakEvenStop else null,
                ),
            )
            2 -> return decision(
                ExitAction.ClosePart(
                    portionOfOriginal = config.tp2ClosePercent,
                    reason = "TP2 +${fmtNum(config.tp2R)}R",
                    tp1 = true, tp2 = true,
                    level = entry + config.tp2R * r,
                    stopAfter = null,
                ),
            )
        }
    }

    // 3. Основной Take Profit. В режиме «раннер» не закрывает позицию — остаток ведёт стоп/трейлинг.
    if (!config.runnerEnabled && price >= p.takeProfit) {
        return decision(ExitAction.CloseAll("Take Profit", level = p.takeProfit))
    }

    // 4. Partial TP: фиксируем доли на промежуточных целях (в % — если не включена R-лестница).
    if (!config.rLadderEnabled && config.partialTpEnabled) {
        if (!p.tp1Done && gainPct >= config.tp1Percent) {
            return decision(
                ExitAction.ClosePart(
                    config.tp1ClosePercent, "TP1 +${config.tp1Percent}%", tp1 = true, tp2 = false,
                    level = entry * (1 + config.tp1Percent / 100), stopAfter = null,
                ),
            )
        }
        if (p.tp1Done && !p.tp2Done && gainPct >= config.tp2Percent) {
            return decision(
                ExitAction.ClosePart(
                    config.tp2ClosePercent, "TP2 +${config.tp2Percent}%", tp1 = true, tp2 = true,
                    level = entry * (1 + config.tp2Percent / 100), stopAfter = null,
                ),
            )
        }
    }

    // 7. Time-stop: позиция висит слишком долго. С R-лестницей — только пока TP1 не взят:
    // взятый TP1 означает «сделка пошла», и тайм-стоп не должен срезать раннер.
    val tradeWent = config.rLadderEnabled && p.tp1Done
    if (config.timeStopMinutes > 0 && !tradeWent) {
        val ageMinutes = (nowMs - p.openedAtMs) / 60_000
        if (ageMinutes >= config.timeStopMinutes) {
            return decision(ExitAction.CloseAll("Time-stop (${ageMinutes}м)", level = null))
        }
    }

    // 7b. «Нет нового максимума» весь срок — продаём остаток: пампы мемкоинов обычно отыгрываются
    // за 24–48 ч, дальше позиция только отдаёт прибыль. До первого нового пика отсчёт идёт от входа.
    if (config.staleExitEnabled && config.staleExitMinutes > 0 && sinceHighMs >= config.staleExitMinutes * 60_000L) {
        return decision(ExitAction.CloseAll("Нет нового максимума ${sinceHighMs / 60_000}м", level = null))
    }

    return decision(ExitAction.Hold)
}
