package tj.khujand.solana.trading.bot.data

import kotlinx.serialization.Serializable

/**
 * Глобальная защита торговли — действует на ВСЕ стратегии сразу, в отличие от лимитов в карточке
 * стратегии. Хранится одним JSON в settings ([SettingsStore.getProtection]); правится в настройках
 * приложения. Любой числовой лимит ≤ 0 — выключен.
 *
 * Проверка монеты перед любой покупкой (EntryGuard):
 * @param guardEnabled главный выключатель проверки.
 * @param requireRevokedAuthorities mint/freeze authority должны быть отозваны (иначе монету могут
 *   допечатать или заморозить наши токены).
 * @param rejectRiskyToken2022 отказ для Token-2022 с опасными расширениями (комиссия перевода,
 *   перманентный делегат, хук перевода, пауза, непереводимость, замороженные счета по умолчанию).
 * @param maxPriceImpactPct влияние нашей покупки на цену по котировке Jupiter, %.
 * @param maxRoundTripLossPct потери «купить и сразу продать» по котировкам Jupiter, % — комиссии пулов
 *   и проскальзывание в обе стороны. Котировка обратной продажи нужна всегда: нет маршрута — нет входа.
 *
 * Лимиты портфеля — все стратегии текущего режима (DEMO/REAL) вместе:
 * @param portfolioEnabled главный выключатель лимитов портфеля.
 * @param maxOpenPositions открытых позиций всего (вместе с покупками «в полёте»).
 * @param maxExposurePct сумма в позициях (по себестоимости), % капитала (свободный баланс + позиции).
 * @param maxDailyLossPct убыток с начала дня — закрытые сделки и открытые по последней цене, % капитала.
 * @param maxWeeklyLossPct то же за последние 7 дней.
 * @param lossStreakLimit столько убыточных сделок подряд — и новые входы на паузе…
 * @param lossStreakPauseMinutes …столько минут от последнего убытка.
 * @param maxDrawdownPct просадка портфеля от пика, % капитала на пике: кривая — нарастающий итог закрытых
 *   сделок плюс открытые по последней цене (по сделкам, а не по балансу: пополнение кошелька и цена SOL —
 *   не просадка). Сама не отпускает. По умолчанию выключена (0).
 * @param autoStopOnLimit лимит убытка портфеля (день, неделя, просадка) не ставит входы на паузу, а
 *   останавливает бота целиком: дальше — только ручной запуск. По умолчанию выключен.
 *
 * Выход:
 * @param maxSellSlippagePct потолок slippage продажи: после каждой неудачной попытки slippage растёт
 *   (3% → 6% → 12% …), но не выше этого значения.
 * @param reversalExitEnabled «выход по развороту»: открытая сделка продаётся, не дожидаясь стопа, когда на
 *   закрытых барах рабочего ТФ стратегии EMA(21) ушла под EMA(50) и после входа сложились нижние максимум и
 *   минимум (см. [tj.khujand.solana.trading.bot.core.strategy.marketReversedAgainstLong]). По умолчанию выключен.
 */
@Serializable
data class ProtectionSettings(
    val guardEnabled: Boolean = true,
    val requireRevokedAuthorities: Boolean = true,
    val rejectRiskyToken2022: Boolean = true,
    val maxPriceImpactPct: Double = 3.0,
    val maxRoundTripLossPct: Double = 5.0,

    val portfolioEnabled: Boolean = true,
    val maxOpenPositions: Int = 3,
    val maxExposurePct: Double = 50.0,
    val maxDailyLossPct: Double = 10.0,
    val maxWeeklyLossPct: Double = 20.0,
    val lossStreakLimit: Int = 3,
    val lossStreakPauseMinutes: Int = 30,
    val maxDrawdownPct: Double = 0.0,
    val autoStopOnLimit: Boolean = false,

    val maxSellSlippagePct: Double = 15.0,
    val reversalExitEnabled: Boolean = false,
)
