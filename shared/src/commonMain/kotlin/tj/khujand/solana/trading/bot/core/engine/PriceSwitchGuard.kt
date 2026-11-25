package tj.khujand.solana.trading.bot.core.engine

import kotlin.math.abs
import tj.khujand.solana.trading.bot.exchange.dex.LivePrice
import tj.khujand.solana.trading.bot.exchange.dex.PriceSource

/**
 * Скачок цены при смене источника больше этого (%) ждёт подтверждения. DexScreener расходится с настоящими
 * сделками на 1,2% в медиане и на 4,6% у худших 10% (замер 24.11.2026): 3% отсекают отставание источника,
 * не задерживая обычные смены.
 */
const val SOURCE_SWITCH_MAX_JUMP_PCT = 3.0

/**
 * Защита от скачков при смене источника цены (Jupiter ↔ DexScreener). Отставший источник может показать цену
 * минутной давности; принятая сразу, она подняла бы пик трейлинга или задела бы стоп. Поэтому цена ДРУГОГО
 * источника, которая отличается от последней принятой больше чем на [maxJumpPct], принимается, только если
 * следующая проверка подтвердит уровень. Задержка — один цикл монитора и только на смене источника: скачки
 * внутри одного источника — это рынок, их не трогаем.
 *
 * Состояние — по ключу (id сделки); монитор вызывает из одной корутины, блокировки не нужны.
 */
class PriceSwitchGuard(private val maxJumpPct: Double = SOURCE_SWITCH_MAX_JUMP_PCT) {

    sealed interface Verdict {
        /** Цену можно брать для решений; [switchedFrom] — источник сменился (скачка нет или он подтвердился). */
        data class Use(val price: LivePrice, val switchedFrom: PriceSource? = null) : Verdict

        /** Новый источник дал цену далеко от прежней: ждём подтверждения, а решения этого цикла — по [last]. */
        data class Hold(val candidate: LivePrice, val last: LivePrice, val jumpPct: Double) : Verdict
    }

    private class State(var last: LivePrice, var pending: LivePrice?)

    private val states = HashMap<String, State>()

    fun check(key: String, price: LivePrice): Verdict {
        val s = states[key] ?: return Verdict.Use(price).also { states[key] = State(price, null) }
        if (price.source == s.last.source) {
            s.last = price
            s.pending = null
            return Verdict.Use(price)
        }
        val jump = jumpPct(price.priceUsd, s.last.priceUsd)
        val pending = s.pending
        val confirmed = pending != null && pending.source == price.source &&
            jumpPct(price.priceUsd, pending.priceUsd) <= maxJumpPct
        if (jump <= maxJumpPct || confirmed) {
            val from = s.last.source
            s.last = price
            s.pending = null
            return Verdict.Use(price, switchedFrom = from)
        }
        s.pending = price
        return Verdict.Hold(price, s.last, jump)
    }

    /** Оставить состояние только этих ключей (закрытые сделки забываем). */
    fun retain(keys: Set<String>) {
        states.keys.retainAll(keys)
    }

    private fun jumpPct(a: Double, b: Double): Double = if (b > 0.0) abs(a / b - 1) * 100 else Double.MAX_VALUE
}
