package tj.khujand.solana.trading.bot.core.strategy

import tj.khujand.solana.trading.bot.data.BotStatus
import tj.khujand.solana.trading.bot.util.plural

/** Насколько плохо то, что сказано в диагнозе. */
enum class DiagnosisTone { OK, INFO, WARN, BAD }

/** Ответ на «почему нет сделок»: главная причина одной строкой и что с ней делать. */
data class EntryDiagnosis(val headline: String, val tone: DiagnosisTone, val hints: List<String> = emptyList())

/** Цикл стратегии старше этого уже не говорит о том, что с ней сейчас (цикл — раз в 30–35 с). */
private const val FRESH_CYCLE_MS = 3 * 60_000L

/**
 * Почему бот не входит — по воронке [funnel], состоянию бота и доске наблюдения (чистая функция).
 * Причины проверяются по порядку: от «бот стоит» до «сетапы были, но их отклонили».
 *
 * @param activeStrategyIds включённые стратегии; их последний цикл говорит, стоят ли они на кулдауне или лимите.
 * @param nearest лучшая строка доски наблюдения — «кто ближе всех к входу».
 * @param candlesLimited источник свечей недавно отказывал по лимиту запросов.
 * @param candleBudgetPerMinute сколько запросов свечей в минуту даёт источник; null — не сравнивать. Стратегии
 *   просят больше — к диагнозу добавляется подсказка: часть монет разбирается по свечам с опозданием.
 */
fun diagnoseEntries(
    status: BotStatus,
    activeStrategyIds: Set<String>,
    funnel: FunnelSnapshot,
    signalOnly: Boolean,
    nearest: WatchItem?,
    candlesLimited: Boolean,
    nowMs: Long,
    candleBudgetPerMinute: Double? = null,
): EntryDiagnosis {
    val base = baseDiagnosis(status, activeStrategyIds, funnel, signalOnly, nearest, candlesLimited, nowMs)
    val shortage = candleBudgetPerMinute
        ?.takeIf { status == BotStatus.RUNNING }
        ?.let { candleShortageHint(funnel.candleDemand, it) }
        ?: return base
    // Нехватка свечей — причина, которую видно раньше остальных: с ней стратегии решают по устаревшим барам.
    return base.copy(hints = listOf(shortage) + base.hints)
}

/**
 * Подсказка о нехватке свечей или null, когда источник успевает (с запасом 10% — оценка спроса приблизительная).
 * Называет ТФ, который просит больше всего, если на него приходится больше половины запросов.
 */
internal fun candleShortageHint(demand: CandleDemand, budgetPerMinute: Double): String? {
    if (demand.pools == 0 || demand.requestsPerMinute <= budgetPerMinute * 1.1) return null
    val need = kotlin.math.round(demand.requestsPerMinute * 10) / 10
    // После дробного числа — «запроса» (7,2 запроса), после целого — по обычному правилу.
    val word = if (need % 1.0 != 0.0) "запроса" else plural(need.toInt(), "запрос", "запроса", "запросов")
    val head = "Свечей не хватает: стратегиям нужно около ${oneDecimal(need)} $word в минуту " +
        "(${demand.pools} ${plural(demand.pools, "монета", "монеты", "монет")}), источник даёт ${oneDecimal(budgetPerMinute)} — " +
        "часть монет разбирается по свечам с опозданием."
    val heaviest = demand.poolsByTimeframeMs.maxByOrNull { (tfMs, pools) -> pools * 60_000.0 / tfMs }
    val advice = if (heaviest != null && heaviest.value * 60_000.0 / heaviest.key > demand.requestsPerMinute / 2 &&
        demand.poolsByTimeframeMs.size > 1
    ) {
        " Больше всего просит ТФ ${timeframeLabel(heaviest.key)} (${heaviest.value} ${plural(heaviest.value, "монета", "монеты", "монет")}): " +
            "перенесите эту стратегию на ТФ крупнее или выключите её."
    } else {
        " Сократите число стратегий или монет либо возьмите ТФ крупнее."
    }
    return head + advice
}

/** «1m», «5m», «1h» — как ТФ называется в настройках стратегии. */
private fun timeframeLabel(tfMs: Long): String {
    val minutes = tfMs / 60_000L
    return if (minutes >= 60 && minutes % 60 == 0L) "${minutes / 60}h" else "${minutes}m"
}

/** Число с одним знаком после запятой, без «,0». */
private fun oneDecimal(v: Double): String {
    val r = kotlin.math.round(v * 10) / 10
    return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString().replace('.', ',')
}

private fun baseDiagnosis(
    status: BotStatus,
    activeStrategyIds: Set<String>,
    funnel: FunnelSnapshot,
    signalOnly: Boolean,
    nearest: WatchItem?,
    candlesLimited: Boolean,
    nowMs: Long,
): EntryDiagnosis {
    val window = "${funnel.windowMinutes} мин"
    if (status == BotStatus.STOPPED) {
        return EntryDiagnosis(
            "Бот остановлен — стратегии не ищут входы", DiagnosisTone.INFO,
            buildList {
                add("Нажмите «Старт» — сканирование начнётся сразу.")
                if (activeStrategyIds.isEmpty()) add("Нет включённых стратегий — включите хотя бы одну на вкладке «Стратегии».")
            }
        )
    }
    if (activeStrategyIds.isEmpty()) {
        return EntryDiagnosis(
            "Нет включённых стратегий", DiagnosisTone.WARN,
            listOf("Включите стратегию на вкладке «Стратегии» — без неё бот только ведёт открытые позиции.")
        )
    }
    if (status == BotStatus.PAUSED) {
        return EntryDiagnosis(
            "Бот на паузе — новые входы не открываются", DiagnosisTone.INFO,
            listOf("Открытые позиции ведутся: стопы и тейки работают.")
        )
    }
    if (funnel.cycles == 0) {
        return EntryDiagnosis("Стратегии запускаются — первый цикл ещё не прошёл", DiagnosisTone.INFO)
    }
    if (funnel.opened > 0) {
        return EntryDiagnosis(
            "За $window ${plural(funnel.opened, "открыта", "открыты", "открыто")} ${funnel.opened} " +
                plural(funnel.opened, "сделка", "сделки", "сделок"),
            DiagnosisTone.OK,
        )
    }

    // Все включённые стратегии в последнем цикле стоят на кулдауне или лимите — до монет дело не доходит.
    val fresh = funnel.strategies.filter { (id, c) -> id in activeStrategyIds && nowMs - c.atMs <= FRESH_CYCLE_MS }
    val blocked = fresh.values.filter { it.block != null }
    if (fresh.isNotEmpty() && blocked.size == fresh.size) {
        val worst = blocked.minBy { BLOCK_PRIORITY.indexOf(it.block!!) }
        val detail = worst.detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
        return when (worst.block!!) {
            CycleBlock.HARD_STOP -> EntryDiagnosis(
                "Остановлено лимитом убытка портфеля$detail", DiagnosisTone.BAD,
                listOf("Бот возобновит входы только после ручного запуска. Лимиты — «Настройки → Защита».")
            )
            CycleBlock.PORTFOLIO -> EntryDiagnosis(
                "Входы закрыты лимитами портфеля$detail", DiagnosisTone.WARN,
                listOf("Лимиты — «Настройки → Защита»; счёт заново с этого момента — «Новый старт» в «Настройки → Данные».")
            )
            CycleBlock.STRATEGY_LIMITS -> EntryDiagnosis(
                "Стратегии упёрлись в свои лимиты$detail", DiagnosisTone.WARN,
                listOf("Дневной убыток и просадка задаются в настройках каждой стратегии.")
            )
            CycleBlock.COOLDOWN -> EntryDiagnosis(
                "Кулдаун после сделки$detail", DiagnosisTone.INFO,
                listOf("Пауза между сделками стратегии — поле «Кулдаун» в её настройках.")
            )
        }
    }

    if (signalOnly) {
        return EntryDiagnosis(
            "Режим «только сигнал» — сделки не открываются", DiagnosisTone.INFO,
            listOf(
                "Сигналов за $window: ${funnel.signals}.",
                "Выключить — «Настройки → Торговля».",
            )
        )
    }

    if (funnel.coins == 0) {
        return EntryDiagnosis(
            "Под фильтры стратегий не подходит ни одна монета", DiagnosisTone.WARN,
            listOf(
                "Сканер наблюдает монеты, но ликвидность, капитализация или возраст не проходят фильтры стратегий.",
                "Ослабьте фильтры в настройках стратегий или подождите новых монет — источники опрашиваются раз в 5 минут.",
            )
        )
    }

    val untraded = funnel.coins - funnel.alreadyTraded
    if (untraded <= 0) {
        return EntryDiagnosis(
            "Все монеты из списка уже торговались", DiagnosisTone.INFO,
            listOf("Правило «одна монета — одна сделка»: бот ждёт новых монет от сканера.")
        )
    }

    if (funnel.noCandles > 0 && funnel.noCandles * 2 >= untraded && funnel.setups == 0) {
        return EntryDiagnosis(
            "Не хватает свечей: ${funnel.noCandles} из $untraded " + plural(untraded, "монеты", "монет", "монет") + " без данных",
            DiagnosisTone.WARN,
            listOf(
                if (candlesLimited) "GeckoTerminal отвечает «слишком много запросов» — уменьшите число стратегий или кандидатов."
                else "Свечи грузятся по очереди: без ключа источник даёт около 4 запросов в минуту.",
            )
        )
    }

    if (funnel.setups == 0) {
        return EntryDiagnosis(
            "Сетапов нет: разобрано $untraded " + plural(untraded, "монета", "монеты", "монет") +
                " — условия стратегий не сложились",
            DiagnosisTone.INFO,
            buildList {
                nearest?.let { w ->
                    add(
                        "Ближе всех: ${w.symbol} (${w.strategyName}) — ${w.passed} из ${w.total}" +
                            (w.missing?.let { ", не хватает: $it" } ?: "")
                    )
                }
                if (candlesLimited) add("Источник свечей отказывает по лимиту — часть монет разбирается с задержкой.")
            }
        )
    }

    val reasons = funnel.losses.entries.sortedByDescending { it.value }
    val top = reasons.firstOrNull()
        ?: return EntryDiagnosis(
            "Сетапов: ${funnel.setups} — вход ещё не состоялся", DiagnosisTone.INFO,
            listOf("Сетап проверяется перед покупкой: риск, лимиты, проверка монеты.")
        )
    return EntryDiagnosis(
        "Сетапов: ${funnel.setups}, сделок нет — чаще всего мешает: ${top.key.label}", DiagnosisTone.WARN,
        buildList {
            add(LOSS_ADVICE.getValue(top.key))
            reasons.drop(1).take(3).forEach { (reason, n) -> add("Ещё: ${reason.label} — $n") }
        }
    )
}

/** Какая блокировка важнее, если стратегии стоят по разным причинам. */
private val BLOCK_PRIORITY = listOf(CycleBlock.HARD_STOP, CycleBlock.PORTFOLIO, CycleBlock.STRATEGY_LIMITS, CycleBlock.COOLDOWN)

private val LOSS_ADVICE = mapOf(
    EntryLoss.COIN_CHECK to "Проверка монеты отклоняет вход: authority, Token-2022, маршрут продажи или влияние на цену — " +
        "подробности в ленте (🛡). Пороги — «Настройки → Защита».",
    EntryLoss.PRICE_RAN to "Пока бот готовит вход, цена уходит больше чем на 3% от цены сигнала — сетап устаревает.",
    EntryLoss.RANGE to "Цена у верха диапазона — так решил range-фильтр в настройках стратегии.",
    EntryLoss.CONFIDENCE to "Уверенность сигнала ниже 60% — общий порог для стратегий без своего порога.",
    EntryLoss.PORTFOLIO to "Лимиты портфеля (позиции, доля капитала, убыток) — «Настройки → Защита».",
    EntryLoss.STRATEGY_LIMIT to "Лимиты стратегии: дневной убыток или просадка — в настройках стратегии.",
    EntryLoss.NO_SIZE to "Размер позиции получился нулевым — проверьте баланс счёта.",
    EntryLoss.EXECUTION to "Сделка не исполнилась — причина в ленте активности.",
    EntryLoss.ALREADY_TRADED to "Монету в этот момент уже купила другая стратегия.",
)
