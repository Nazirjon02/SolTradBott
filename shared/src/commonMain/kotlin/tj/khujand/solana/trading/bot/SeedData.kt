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
 * Четыре стратегии: Dars / Smart Money (по умолчанию активна),
 * Momentum Scalping, RSI+EMA и «Пробой + откат» (включаются вручную).
 */
fun seedDefaultStrategies(db: DrxDatabase) {
    // Уже засеянные БД подтягиваем к значениям методички один раз (см. ниже).
    normalizeDarsDefaults(db)
    normalizeDrawdownLimits(db)

    val existing = db.strategyQueries.getAll().executeAsList()
    if (existing.isNotEmpty()) {
        // Базовые стратегии уже есть, но «Пробой + откат» появилась позже — досеваем один раз.
        seedBreakoutPullbackOnce(db)
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
        rsi_period = 14L,
        rsi_overbought = 70.0,
        rsi_oversold = 30.0,
        ema_fast = 9L,
        ema_slow = 21L,
        atr_period = 14L,
        volume_threshold = 1.5,
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

    // ─── Стратегия 2: Momentum Scalping — свежие токены, короткие цели ────────
    db.strategyQueries.insert(
        id = "momentum-scalp",
        name = "Momentum Scalping — свежие мемкоины",
        type = "MOMENTUM",
        is_active = 0L,
        timeframe = "1m",
        position_size = 3.0,
        max_positions = 1L,
        slippage_percent = 2.0,
        priority_fee_lamports = 0L,
        stop_loss_percent = 10.0,
        take_profit_percent = 15.0,
        trailing_stop = 1L,
        trailing_stop_percent = 7.0,
        trailing_activation_percent = 10.0,
        break_even_enabled = 1L,
        break_even_trigger_percent = 8.0,
        break_even_offset_percent = 1.0,
        partial_tp_enabled = 0L,
        tp1_percent = 10.0,
        tp1_close_percent = 50.0,
        tp2_percent = 12.0,
        tp2_close_percent = 30.0,
        time_stop_minutes = 60L,     // скальпинг: висим не дольше часа
        liquidity_exit_drop_percent = 40.0,
        max_daily_loss = 5.0,
        max_drawdown = 30.0,
        cooldown_seconds = 180L,
        min_liquidity_usd = 15_000.0,
        min_market_cap = 30_000.0,
        max_market_cap = 3_000_000.0,
        min_token_age_minutes = 10L,
        max_token_age_minutes = 2_880L,  // не старше 2 суток
        rsi_period = 14L,
        rsi_overbought = 70.0,
        rsi_oversold = 30.0,
        ema_fast = 9L,
        ema_slow = 21L,
        atr_period = 14L,
        volume_threshold = 2.0,
        dars_higher_tf = "5m",
        dars_candle_limit = 100L,
        dars_swing_pivot_pct = 1.0,
        dars_require_htf_trend = 0L,
        dars_dominance_ratio = 1.5,
        dars_min_correction_len_pct = 70.0,
        dars_reject_at_resistance = 0L,
        dars_resistance_proximity_pct = 1.0,
        dars_min_legs = 4L,
        dars_use_impulse_correction = 0L,
        dars_use_trend_levels = 0L,
        dars_use_false_breakout = 0L,
        dars_use_triangle = 0L,
        created_at = now,
        updated_at = now
    )

    // ─── Стратегия 3: RSI + EMA — «взрослые» мемкоины с историей ──────────────
    db.strategyQueries.insert(
        id = "rsi-ema-memecoins",
        name = "RSI + EMA — мемкоины с историей",
        type = "RSI_EMA",
        is_active = 0L,
        timeframe = "5m",
        position_size = 5.0,
        max_positions = 1L,
        slippage_percent = 1.0,
        priority_fee_lamports = 0L,
        stop_loss_percent = 12.0,
        take_profit_percent = 24.0,
        trailing_stop = 0L,
        trailing_stop_percent = 10.0,
        trailing_activation_percent = 15.0,
        break_even_enabled = 0L,
        break_even_trigger_percent = 10.0,
        break_even_offset_percent = 1.0,
        partial_tp_enabled = 0L,
        tp1_percent = 12.0,
        tp1_close_percent = 50.0,
        tp2_percent = 18.0,
        tp2_close_percent = 30.0,
        time_stop_minutes = 0L,
        liquidity_exit_drop_percent = 50.0,
        max_daily_loss = 5.0,
        max_drawdown = 30.0,
        cooldown_seconds = 300L,
        min_liquidity_usd = 50_000.0,
        min_market_cap = 500_000.0,
        max_market_cap = 100_000_000.0,
        min_token_age_minutes = 10_080L, // старше недели
        max_token_age_minutes = 10_000_000L,
        rsi_period = 14L,
        rsi_overbought = 70.0,
        rsi_oversold = 30.0,
        ema_fast = 9L,
        ema_slow = 21L,
        atr_period = 14L,
        volume_threshold = 1.5,
        dars_higher_tf = "1h",
        dars_candle_limit = 200L,
        dars_swing_pivot_pct = 1.0,
        dars_require_htf_trend = 1L,
        dars_dominance_ratio = 1.5,
        dars_min_correction_len_pct = 70.0,
        dars_reject_at_resistance = 1L,
        dars_resistance_proximity_pct = 1.0,
        dars_min_legs = 4L,
        dars_use_impulse_correction = 0L,
        dars_use_trend_levels = 0L,
        dars_use_false_breakout = 0L,
        dars_use_triangle = 0L,
        created_at = now,
        updated_at = now
    )

    // ─── Стратегия 4: Пробой + откат (включается вручную) ─────────────────────
    seedBreakoutPullbackOnce(db)
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
