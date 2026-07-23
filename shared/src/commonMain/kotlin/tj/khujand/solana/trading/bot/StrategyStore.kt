package tj.khujand.solana.trading.bot

import kotlin.time.Clock
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.toStrategyConfig
import tj.khujand.solana.trading.bot.data.db.DrxDatabase

/** Доступ UI к стратегиям в БД (аналог StrategyStore в MRX). */
class StrategyStore(private val db: DrxDatabase) {

    fun loadAll(): List<StrategyConfig> =
        db.strategyQueries.getAll().executeAsList().map { it.toStrategyConfig() }

    fun setActive(id: String, active: Boolean) {
        db.strategyQueries.updateActive(if (active) 1L else 0L, now(), id)
    }

    fun delete(id: String) = db.strategyQueries.delete(id)

    fun save(cfg: StrategyConfig) {
        val exists = db.strategyQueries.getById(cfg.id).executeAsOneOrNull() != null
        if (exists) update(cfg) else insert(cfg)
        // Фильтр по диапазону не входит в insert/updateStrategy — пишем отдельным запросом.
        db.strategyQueries.updateRangeParams(
            range_filter_enabled = if (cfg.rangeFilterEnabled) 1L else 0L,
            range_max_entry_pct = cfg.rangeMaxEntryPct,
            range_lookback_bars = cfg.rangeLookbackBars.toLong(),
            id = cfg.id,
        )
    }

    private fun update(cfg: StrategyConfig) = db.strategyQueries.updateStrategy(
        name = cfg.name,
        type = cfg.type,
        is_active = if (cfg.isActive) 1L else 0L,
        timeframe = cfg.timeframe,
        position_size = cfg.positionSize,
        max_positions = cfg.maxPositions.toLong(),
        slippage_percent = cfg.slippagePercent,
        priority_fee_lamports = cfg.priorityFeeLamports,
        stop_loss_percent = cfg.stopLossPercent,
        take_profit_percent = cfg.takeProfitPercent,
        trailing_stop = if (cfg.trailingStopEnabled) 1L else 0L,
        trailing_stop_percent = cfg.trailingStopPercent,
        trailing_activation_percent = cfg.trailingActivationPercent,
        break_even_enabled = if (cfg.breakEvenEnabled) 1L else 0L,
        break_even_trigger_percent = cfg.breakEvenTriggerPercent,
        break_even_offset_percent = cfg.breakEvenOffsetPercent,
        partial_tp_enabled = if (cfg.partialTpEnabled) 1L else 0L,
        tp1_percent = cfg.tp1Percent,
        tp1_close_percent = cfg.tp1ClosePercent,
        tp2_percent = cfg.tp2Percent,
        tp2_close_percent = cfg.tp2ClosePercent,
        time_stop_minutes = cfg.timeStopMinutes.toLong(),
        liquidity_exit_drop_percent = cfg.liquidityExitDropPercent,
        max_daily_loss = cfg.maxDailyLoss,
        max_drawdown = cfg.maxDrawdown,
        cooldown_seconds = cfg.cooldownSeconds.toLong(),
        min_liquidity_usd = cfg.minLiquidityUsd,
        min_market_cap = cfg.minMarketCap,
        max_market_cap = cfg.maxMarketCap,
        min_token_age_minutes = cfg.minTokenAgeMinutes,
        max_token_age_minutes = cfg.maxTokenAgeMinutes,
        min_buy_sell_ratio = cfg.minBuySellRatio,
        rsi_period = cfg.rsiPeriod.toLong(),
        rsi_overbought = cfg.rsiOverbought,
        rsi_oversold = cfg.rsiOversold,
        ema_fast = cfg.emaFast.toLong(),
        ema_slow = cfg.emaSlow.toLong(),
        atr_period = cfg.atrPeriod.toLong(),
        volume_threshold = cfg.volumeThreshold,
        dars_higher_tf = cfg.darsHigherTf,
        dars_candle_limit = cfg.darsCandleLimit.toLong(),
        dars_swing_pivot_pct = cfg.darsSwingPivotPct,
        dars_require_htf_trend = if (cfg.darsRequireHtfTrend) 1L else 0L,
        dars_dominance_ratio = cfg.darsDominanceRatio,
        dars_min_correction_len_pct = cfg.darsMinCorrectionLenPct,
        dars_reject_at_resistance = if (cfg.darsRejectAtResistance) 1L else 0L,
        dars_resistance_proximity_pct = cfg.darsResistanceProximityPct,
        dars_min_legs = cfg.darsMinLegs.toLong(),
        dars_use_impulse_correction = if (cfg.darsUseImpulseCorrection) 1L else 0L,
        dars_use_trend_levels = if (cfg.darsUseTrendLevels) 1L else 0L,
        dars_use_false_breakout = if (cfg.darsUseFalseBreakout) 1L else 0L,
        dars_use_triangle = if (cfg.darsUseTriangle) 1L else 0L,
        updated_at = now(),
        id = cfg.id,
    )

    private fun insert(cfg: StrategyConfig) = db.strategyQueries.insert(
        id = cfg.id,
        name = cfg.name,
        type = cfg.type,
        is_active = if (cfg.isActive) 1L else 0L,
        timeframe = cfg.timeframe,
        position_size = cfg.positionSize,
        max_positions = cfg.maxPositions.toLong(),
        slippage_percent = cfg.slippagePercent,
        priority_fee_lamports = cfg.priorityFeeLamports,
        stop_loss_percent = cfg.stopLossPercent,
        take_profit_percent = cfg.takeProfitPercent,
        trailing_stop = if (cfg.trailingStopEnabled) 1L else 0L,
        trailing_stop_percent = cfg.trailingStopPercent,
        trailing_activation_percent = cfg.trailingActivationPercent,
        break_even_enabled = if (cfg.breakEvenEnabled) 1L else 0L,
        break_even_trigger_percent = cfg.breakEvenTriggerPercent,
        break_even_offset_percent = cfg.breakEvenOffsetPercent,
        partial_tp_enabled = if (cfg.partialTpEnabled) 1L else 0L,
        tp1_percent = cfg.tp1Percent,
        tp1_close_percent = cfg.tp1ClosePercent,
        tp2_percent = cfg.tp2Percent,
        tp2_close_percent = cfg.tp2ClosePercent,
        time_stop_minutes = cfg.timeStopMinutes.toLong(),
        liquidity_exit_drop_percent = cfg.liquidityExitDropPercent,
        max_daily_loss = cfg.maxDailyLoss,
        max_drawdown = cfg.maxDrawdown,
        cooldown_seconds = cfg.cooldownSeconds.toLong(),
        min_liquidity_usd = cfg.minLiquidityUsd,
        min_market_cap = cfg.minMarketCap,
        max_market_cap = cfg.maxMarketCap,
        min_token_age_minutes = cfg.minTokenAgeMinutes,
        max_token_age_minutes = cfg.maxTokenAgeMinutes,
        min_buy_sell_ratio = cfg.minBuySellRatio,
        rsi_period = cfg.rsiPeriod.toLong(),
        rsi_overbought = cfg.rsiOverbought,
        rsi_oversold = cfg.rsiOversold,
        ema_fast = cfg.emaFast.toLong(),
        ema_slow = cfg.emaSlow.toLong(),
        atr_period = cfg.atrPeriod.toLong(),
        volume_threshold = cfg.volumeThreshold,
        dars_higher_tf = cfg.darsHigherTf,
        dars_candle_limit = cfg.darsCandleLimit.toLong(),
        dars_swing_pivot_pct = cfg.darsSwingPivotPct,
        dars_require_htf_trend = if (cfg.darsRequireHtfTrend) 1L else 0L,
        dars_dominance_ratio = cfg.darsDominanceRatio,
        dars_min_correction_len_pct = cfg.darsMinCorrectionLenPct,
        dars_reject_at_resistance = if (cfg.darsRejectAtResistance) 1L else 0L,
        dars_resistance_proximity_pct = cfg.darsResistanceProximityPct,
        dars_min_legs = cfg.darsMinLegs.toLong(),
        dars_use_impulse_correction = if (cfg.darsUseImpulseCorrection) 1L else 0L,
        dars_use_trend_levels = if (cfg.darsUseTrendLevels) 1L else 0L,
        dars_use_false_breakout = if (cfg.darsUseFalseBreakout) 1L else 0L,
        dars_use_triangle = if (cfg.darsUseTriangle) 1L else 0L,
        created_at = now(),
        updated_at = now(),
    )

    private fun now() = Clock.System.now().toEpochMilliseconds()
}
