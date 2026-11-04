package tj.khujand.solana.trading.bot.core.strategy

/**
 * Одна проверка стратегии: что проверяли, прошло ли и с какими числами (порт CheckResult из MRX).
 * [detail] обязан содержать ЦИФРЫ («объём ×1.4 < ×2.5»), а не оценку: по цифрам видно, насколько
 * далеко до порога.
 */
data class CheckResult(val label: String, val passed: Boolean, val detail: String)

/**
 * План взведённого входа (порт TriggerPlan из MRX): всё сошлось, кроме бара-триггера. Бот входит, когда цена
 * пройдёт [triggerLevel], но не выше [maxEntryPrice]; цена у [invalidationPrice] ломает сетап.
 * @param stopPrice стоп входа — под структурой, от уровня не зависит.
 */
data class TriggerPlan(
    val triggerLevel: Double,
    val maxEntryPrice: Double,
    val invalidationPrice: Double,
    val stopPrice: Double,
    val confidence: Double,
    val reason: String,
)

/**
 * Разбор монеты стратегией для доски наблюдения (порт Evaluation из MRX).
 *
 * Детекторы DRX останавливаются на первом непройденном условии, поэтому [passed] из [total] здесь —
 * «дошли до шага N»: условия идут по порядку, как их читает сама стратегия (импульс → откат → триггер…).
 *
 * @param missing первое непройденное условие с цифрами; null — всё сошлось (есть [signal]).
 * @param signal сетап сложился — дальше вход решают общие фильтры (range, уверенность, риск, проверка монеты).
 * @param entryLevel уровень, у которого стратегия ждёт входа (пробой максимума, триггер выше бара);
 *   null — пока не определён. По нему доска считает, насколько цена близко.
 * @param checks полный список проверок, если стратегия считает их все (Эллиотт) — для графика бота.
 * @param trigger сетапу не хватает только триггера, и стратегия может взвести вход по касанию уровня
 *   ([StrategyConfig.armedEntryEnabled]); null — взводить нечего.
 */
data class Evaluation(
    val passed: Int,
    val total: Int,
    val missing: String?,
    val signal: Signal? = null,
    val entryLevel: Double? = null,
    val checks: List<CheckResult> = emptyList(),
    val trigger: TriggerPlan? = null,
) {
    companion object {
        /** Сетап сложился: все [total] условий пройдены. */
        fun ready(signal: Signal, total: Int, entryLevel: Double? = null, checks: List<CheckResult> = emptyList()) =
            Evaluation(total, total, null, signal, entryLevel, checks)

        /** Остановились на шаге [stage] (с 1) из [total]: пройдено stage − 1. */
        fun stoppedAt(stage: Int, total: Int, reason: String, entryLevel: Double? = null) =
            Evaluation((stage - 1).coerceIn(0, total), total, reason, null, entryLevel)
    }
}
