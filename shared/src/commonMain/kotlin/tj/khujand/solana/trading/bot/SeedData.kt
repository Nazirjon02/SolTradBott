package tj.khujand.solana.trading.bot

import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/**
 * Вызови seedDefaultStrategies(db) один раз при первом запуске,
 * чтобы добавить стратегии по умолчанию.
 *
 * Живёт в shared, чтобы и сервер, и desktop-приложение сеяли одни и те же дефолты
 * в общую базу.
 *
 * Четыре стратегии: Dars / Smart Money (по умолчанию активна), «Пробой + откат», «Мем-плейбук»
 * и «Волны Эллиотта» (три последние включаются вручную).
 */
fun seedDefaultStrategies(db: DrxDatabase) {
    // Momentum Scalping и RSI+EMA удалены из бота — убираем их строки из старых БД.
    db.strategyQueries.deleteRemovedTypes()

    // Уже засеянные БД подтягиваем к значениям методички один раз (см. ниже).
    normalizeDarsDefaults(db)
    normalizeDrawdownLimits(db)

    val existing = db.strategyQueries.getAll().executeAsList()
    if (existing.isNotEmpty()) {
        // Базовые стратегии уже есть, но «Пробой + откат», «Мем-плейбук» и «Волны Эллиотта»
        // появились позже — досеваем один раз.
        seedBreakoutPullbackOnce(db)
        seedMemePlaybookOnce(db)
        seedElliottOnce(db)
        return
    }

    val now = Clock.System.now().toEpochMilliseconds()

    // ─── Стратегия 1 (по умолчанию): Dars / Smart Money на сканере мемкоинов ──
    db.strategyQueries.insert(
        id = "dars-memecoins",
        name = "Dars / Smart Money — сканер мемкоинов",
        type = "DARS",
        is_active = 1L,
        timeframe = "1m",
        position_size = 5.0,
        max_positions = 1L,
        slippage_percent = 1.5,
        priority_fee_lamports = 0L,
        stop_loss_percent = 15.0,
        take_profit_percent = 30.0,   // RR ≈ 2
        trailing_stop = 0L,
        trailing_stop_percent = 10.0,
        trailing_activation_percent = 15.0,
        break_even_enabled = 0L,
        break_even_trigger_percent = 10.0,
        break_even_offset_percent = 1.0,
        partial_tp_enabled = 0L,
        tp1_percent = 15.0,
        tp1_close_percent = 50.0,
        tp2_percent = 25.0,
        tp2_close_percent = 30.0,
        time_stop_minutes = 0L,
        liquidity_exit_drop_percent = 50.0,
        max_daily_loss = 5.0,
        max_drawdown = 30.0,
        cooldown_seconds = 300L,
        min_liquidity_usd = 10_000.0,
        min_market_cap = 50_000.0,
        max_market_cap = 10_000_000.0,
        min_token_age_minutes = 30L,
        max_token_age_minutes = 43_200L,
        atr_period = 14L,
        dars_higher_tf = "15m",
        dars_candle_limit = 200L,
        dars_swing_pivot_pct = 1.0,
        dars_require_htf_trend = 1L,
        dars_dominance_ratio = 1.5,
        dars_min_correction_len_pct = 70.0,
        dars_reject_at_resistance = 1L,
        dars_resistance_proximity_pct = 1.0,
        dars_min_legs = 4L,
        dars_use_impulse_correction = 1L,
        dars_use_trend_levels = 1L,
        dars_use_false_breakout = 1L,
        dars_use_triangle = 1L,
        created_at = now,
        updated_at = now
    )

    // ─── Стратегия 2: Пробой + откат (включается вручную) ─────────────────────
    seedBreakoutPullbackOnce(db)

    // ─── Стратегия 3: Мем-плейбук (включается вручную) ────────────────────────
    seedMemePlaybookOnce(db)

    // ─── Стратегия 4: Волны Эллиотта (включается вручную) ─────────────────────
    seedElliottOnce(db)
}

/** Ключ-флаг: стратегия «Пробой + откат» уже засеяна (удалённую пользователем не возвращаем). */
private const val BREAKOUT_PULLBACK_SEED_KEY = "breakout_pullback_seeded_v1"

internal const val BREAKOUT_PULLBACK_ID = "breakout-pullback"

/**
 * Дефолты «Пробой + откат» (STRATEGY_RESEARCH.md §3): рабочий ТФ 5m + старший 15m,
 * размер от риска 1% (потолок 10% баланса и 1% ликвидности пула), выходы в R:
 * TP1 40% на +1.5R → безубыток, TP2 30% на +3R, остаток 30% — раннер под ATR-трейлингом.
 */
internal fun breakoutPullbackDefaults(): StrategyConfig = StrategyConfig(
    id = BREAKOUT_PULLBACK_ID,
    name = "Пробой + откат — мемкоины с историей",
    type = StrategyType.BREAKOUT_PULLBACK.name,
    isActive = false,
    timeframe = "5m",
    positionSize = 10.0,          // потолок размера; фактический — от риска
    maxPositions = 1,
    slippagePercent = 1.5,
    stopLossPercent = 12.0,       // стоп дальше 12% — сетап «не тугой», вход отклоняется
    takeProfitPercent = 100.0,    // в режиме раннера не закрывает позицию
    breakEvenOffsetPercent = 2.0, // безубыток с запасом на слиппедж выхода
    tp1ClosePercent = 40.0,
    tp2ClosePercent = 30.0,
    timeStopMinutes = 120,        // ~24 бара 5m: не дошла до TP1 — выходим
    rLadderEnabled = true,
    tp1R = 1.5,
    tp2R = 3.0,
    breakEvenAfterTp1 = true,
    runnerEnabled = true,
    atrTrailEnabled = true,
    atrTrailMult = 3.0,
    atrTrailActivationR = 1.0,
    riskSizingEnabled = true,
    riskPerTradePercent = 1.0,
    maxPoolSharePercent = 1.0,
    maxDrawdown = 30.0,
    minLiquidityUsd = 20_000.0,
    minMarketCap = 100_000.0,
    maxMarketCap = 20_000_000.0,
    minTokenAgeMinutes = 180,     // ≥ 36 баров 5m: база + пробой + откат
    maxTokenAgeMinutes = 43_200,
)

/** Добавляет «Пробой + откат» один раз — и в новую, и в уже существующую БД. */
private fun seedBreakoutPullbackOnce(db: DrxDatabase) {
    val alreadyDone = db.settingsQueries.get(BREAKOUT_PULLBACK_SEED_KEY).executeAsOneOrNull() != null
    if (alreadyDone) return
    val store = StrategyStore(db)
    if (store.loadAll().none { it.id == BREAKOUT_PULLBACK_ID }) store.save(breakoutPullbackDefaults())
    db.settingsQueries.set(BREAKOUT_PULLBACK_SEED_KEY, "1", Clock.System.now().toEpochMilliseconds())
}

/** Ключ-флаг: стратегия «Мем-плейбук» уже засеяна (удалённую пользователем не возвращаем). */
private const val MEME_PLAYBOOK_SEED_KEY = "meme_playbook_seeded_v1"

internal const val MEME_PLAYBOOK_ID = "meme-playbook"

/**
 * Дефолты «Мем-плейбука» (STRATEGY_RESEARCH.md §8): 5m, вход на откате 0.382–0.786 после импульса ≥ 50%,
 * стоп под минимумом отката, не глубже 0.786 (дальше 25% — пропуск), риск 2% на сделку
 * (потолок 10% баланса и 1% пула).
 * Выходы: 50% на 2x («вернуть тело») → безубыток, 25% на 3x, остаток 25% — под ступенчатым трейлингом
 * (40% / 30% / 20% / 15% от пика по иксам); 24 ч без нового максимума — выход; пул −20% — выход.
 */
internal fun memePlaybookDefaults(): StrategyConfig = StrategyConfig(
    id = MEME_PLAYBOOK_ID,
    name = "Мем-плейбук — откат по Фибо, тело на 2x",
    type = StrategyType.MEME_PLAYBOOK.name,
    isActive = false,
    timeframe = "5m",
    positionSize = 10.0,          // потолок размера; фактический — от риска
    maxPositions = 2,
    slippagePercent = 2.0,        // пулы мемкоинов тонкие
    stopLossPercent = 25.0,       // структурный стоп дальше 25% — вход пропускается (проигрыш ≤ ~0.75x)
    takeProfitPercent = 200.0,    // в режиме раннера не закрывает позицию
    breakEvenOffsetPercent = 2.0, // безубыток с запасом на слиппедж выхода
    partialTpEnabled = true,
    tp1Percent = 100.0,           // 2x — продаём половину, вложенное вернулось
    tp1ClosePercent = 50.0,
    tp2Percent = 200.0,           // 3x — ещё четверть
    tp2ClosePercent = 25.0,
    liquidityExitDropPercent = 20.0,
    breakEvenAfterTp1 = true,
    runnerEnabled = true,
    tieredTrailEnabled = true,
    staleExitEnabled = true,
    staleExitMinutes = 1440,
    riskSizingEnabled = true,
    riskPerTradePercent = 2.0,
    maxPoolSharePercent = 1.0,
    maxDrawdown = 50.0,           // вдвое больше SL — штатный стоп не блокирует стратегию
    minLiquidityUsd = 10_000.0,
    minMarketCap = 50_000.0,
    maxMarketCap = 20_000_000.0,
    minTokenAgeMinutes = 60,      // моложе часа — лотерея запуска, а не сделка
    maxTokenAgeMinutes = 43_200,
)

/** Добавляет «Мем-плейбук» один раз — и в новую, и в уже существующую БД. */
private fun seedMemePlaybookOnce(db: DrxDatabase) {
    val alreadyDone = db.settingsQueries.get(MEME_PLAYBOOK_SEED_KEY).executeAsOneOrNull() != null
    if (alreadyDone) return
    val store = StrategyStore(db)
    if (store.loadAll().none { it.id == MEME_PLAYBOOK_ID }) store.save(memePlaybookDefaults())
    db.settingsQueries.set(MEME_PLAYBOOK_SEED_KEY, "1", Clock.System.now().toEpochMilliseconds())
}

/** Ключ-флаг: стратегия «Волны Эллиотта» уже засеяна (удалённую пользователем не возвращаем). */
private const val ELLIOTT_SEED_KEY = "elliott_seeded_v1"

internal const val ELLIOTT_ID = "elliott-waves"

/**
 * Дефолты «Волн Эллиотта» (порт из MRX): 15m — на крупном масштабе волны надёжнее, а 200 свечей
 * 15m (~2 суток) ещё укладываются в жизнь мемкоина. Стоп и цель — от разметки волн; Stop Loss 25%
 * здесь только потолок: стоп по правилу дальше — вход пропускается. Риск 1% баланса на сделку
 * (потолок 10% баланса и 1% пула). Разбору нужно ≥ 44 закрытых свечей (ATR 14 + 30) — на 15m это
 * 11 часов, поэтому монеты от 12 часов. Старшая степень (1h) выключена, как в MRX.
 */
internal fun elliottDefaults(): StrategyConfig = StrategyConfig(
    id = ELLIOTT_ID,
    name = "Волны Эллиотта — вход в 3-ю и 5-ю волну",
    type = StrategyType.ELLIOTT.name,
    isActive = false,
    timeframe = "15m",
    positionSize = 10.0,          // потолок размера; фактический — от риска
    maxPositions = 2,
    slippagePercent = 2.0,
    stopLossPercent = 25.0,       // потолок структурного стопа
    riskSizingEnabled = true,
    riskPerTradePercent = 1.0,
    maxPoolSharePercent = 1.0,
    maxDrawdown = 50.0,           // вдвое больше SL — штатный стоп не блокирует стратегию
    minTokenAgeMinutes = 720,
    maxTokenAgeMinutes = 43_200,
)

/** Добавляет «Волны Эллиотта» один раз — и в новую, и в уже существующую БД. */
private fun seedElliottOnce(db: DrxDatabase) {
    val alreadyDone = db.settingsQueries.get(ELLIOTT_SEED_KEY).executeAsOneOrNull() != null
    if (alreadyDone) return
    val store = StrategyStore(db)
    if (store.loadAll().none { it.id == ELLIOTT_ID }) store.save(elliottDefaults())
    db.settingsQueries.set(ELLIOTT_SEED_KEY, "1", Clock.System.now().toEpochMilliseconds())
}

/** Ключ-флаг: нормализация Dars-дефолтов к методичке выполнена (чтобы не повторять). */
private const val DARS_DEFAULTS_MIGRATION_KEY = "dars_defaults_normalized_v1"

/** Ключ-флаг: лимиты просадки приведены в согласие со стоп-лоссом. */
private const val DRAWDOWN_LIMITS_MIGRATION_KEY = "drawdown_limits_normalized_v1"

/**
 * Одноразовая починка: в конфигах, где `max_drawdown <= stop_loss_percent`, штатный выход
 * по стоп-лоссу сам по себе перекрывал лимит просадки — и RiskManager блокировал стратегию.
 * Поднимаем такие лимиты до двойного SL; корректные пользовательские значения не трогаем.
 */
private fun normalizeDrawdownLimits(db: DrxDatabase) {
    val alreadyDone = db.settingsQueries.get(DRAWDOWN_LIMITS_MIGRATION_KEY).executeAsOneOrNull() != null
    if (alreadyDone) return
    val now = Clock.System.now().toEpochMilliseconds()
    db.strategyQueries.normalizeDrawdownLimits(now)
    db.settingsQueries.set(DRAWDOWN_LIMITS_MIGRATION_KEY, "1", now)
}

/**
 * Одноразовая миграция: подтягивает уже засеянные Dars-стратегии к значениям методички
 * (коррекция ≥70% импульса, minLegs=4, доминирование 1.5×). Затрагивает только строки,
 * где осталась ровно старая слабая тройка значений (30/2/1.3) — пользовательские правки
 * не трогаем. Выполняется один раз: результат помечается флагом в settings.
 */
private fun normalizeDarsDefaults(db: DrxDatabase) {
    val alreadyDone = db.settingsQueries.get(DARS_DEFAULTS_MIGRATION_KEY).executeAsOneOrNull() != null
    if (alreadyDone) return
    val now = Clock.System.now().toEpochMilliseconds()
    db.strategyQueries.normalizeDarsDefaults(now)
    db.settingsQueries.set(DARS_DEFAULTS_MIGRATION_KEY, "1", now)
}
