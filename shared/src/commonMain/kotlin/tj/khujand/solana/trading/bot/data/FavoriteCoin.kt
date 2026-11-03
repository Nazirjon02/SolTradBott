package tj.khujand.solana.trading.bot.data

import kotlinx.serialization.Serializable

/**
 * Снимок монеты, отмеченной «в избранное» на экране «Анализ». Раньше избранное хранило только
 * mint-адреса, а сама карточка бралась из результата свежего разбора текущих кандидатов сканера.
 * Из-за этого через некоторое время (сканер сменил список монет) избранное выглядело «пропавшим»:
 * адрес в базе есть, но показать нечего — монеты уже нет среди кандидатов и разбор её не касается.
 *
 * Теперь при добавлении в избранное сохраняем самодостаточный снимок разбора, чтобы список
 * избранного можно было показать прямо из БД в любой момент — даже без запуска анализа и даже
 * когда монета давно ушла из сканера. Храним всё, что нужно карточке (кроме тяжёлых ног/уровней —
 * их сводим к готовым строкам и ключевым числам).
 */
@Serializable
data class FavoriteCoin(
    /** Адрес смарт-контракта (mint) — ключ избранного. */
    val mint: String,
    val symbol: String = "",
    /** URL реальной иконки монеты, null — иконки нет. */
    val iconUrl: String? = null,
    val price: Double = 0.0,
    /** Балл разбора 0..100 на момент снимка. */
    val score: Int = 0,
    /** Направление тренда — имя [tj.khujand.solana.trading.bot.domain.dars.TrendDirection] (UP/DOWN/FLAT). */
    val trend: String = "",
    /** Фаза рынка, человекочитаемо («Импульс», «Коррекция»…). */
    val phaseLabel: String = "",
    /** Готовность ко входу, человекочитаемо («Готов ко входу»…). */
    val readinessLabel: String = "",
    val readinessEmoji: String = "",
    /** «1h → 5m» — таймфреймы тренда и входа на момент снимка. */
    val timeframeLabel: String = "",
    /** Название сработавшего сетапа, если был. */
    val setup: String? = null,

    // ─── Урок 1: доминирование / вторая сторона ──────────────────────────────
    val dominance: Double = 0.0,
    val opposition: Double = 0.0,
    /** Готовые строки характера ног («скорость …, бары …, закрытия …, объём …»). */
    val impulseDesc: String? = null,
    val correctionDesc: String? = null,

    // ─── Зона входа (ориентир). entryPrice<=0 — плана нет ─────────────────────
    val entryZoneLow: Double = 0.0,
    val entryZoneHigh: Double = 0.0,
    val entryPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val takeProfit: Double = 0.0,
    val riskReward: Double = 0.0,
    val riskPercent: Double = 0.0,
    val rewardPercent: Double = 0.0,
    val entryTrigger: String? = null,

    // ─── Уровни (0 = не найден) ──────────────────────────────────────────────
    val supportPrice: Double = 0.0,
    val resistancePrice: Double = 0.0,

    // ─── Рыночный фон из сканера (DexScreener) ───────────────────────────────
    val liquidityUsd: Double = 0.0,
    val marketCap: Double = 0.0,
    val volumeH1Usd: Double = 0.0,
    val changeM5Percent: Double = 0.0,
    val changeH1Percent: Double = 0.0,
    val buysH1: Int = 0,
    val sellsH1: Int = 0,

    // ─── Причины ─────────────────────────────────────────────────────────────
    /** Урок 4: что мешало входу на момент снимка. */
    val blockers: List<String> = emptyList(),
    /** Что видно на графике — человеческим языком. */
    val notes: List<String> = emptyList(),

    // ─── Времена ─────────────────────────────────────────────────────────────
    /** Когда пользователь добавил монету в избранное, epoch millis. Ставится один раз и НЕ
     *  меняется при авто-обновлении снимка — это «когда я добавила», а не «когда обновилось». */
    val addedAt: Long = 0L,
    /** Когда сделан сам разбор, epoch millis (обновляется при повторном разборе). */
    val analyzedAt: Long = 0L,
    /** Время открытия последней свечи разбора (для сверки с DexScreener), epoch millis. */
    val lastBarTime: Long = 0L,
) {
    /** Доля покупок в сделках за час, 0..1; null — сделок не было. */
    val buyRatioH1: Double? get() {
        val total = buysH1 + sellsH1
        return if (total > 0) buysH1.toDouble() / total else null
    }
    /** Есть ли сохранённый план входа. */
    val hasEntry: Boolean get() = entryPrice > 0.0
}
