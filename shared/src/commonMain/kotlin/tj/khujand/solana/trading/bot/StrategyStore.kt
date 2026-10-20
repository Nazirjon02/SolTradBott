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
        // Параметры «Входа по баллу» — тоже вне insert/updateStrategy.
        db.strategyQueries.updateScoreParams(
            score_threshold = cfg.scoreThreshold.toLong(),
            scan_filters_enabled = if (cfg.scanFiltersEnabled) 1L else 0L,
            id = cfg.id,
        )
        // Выключатели риск-лимитов — тоже вне insert/updateStrategy.
        db.strategyQueries.updateRiskToggles(
            max_daily_loss_enabled = if (cfg.maxDailyLossEnabled) 1L else 0L,
            max_drawdown_enabled = if (cfg.maxDrawdownEnabled) 1L else 0L,
            id = cfg.id,
        )
        // Выходы в R, размер от риска и «Пробой + откат» — тоже вне insert/updateStrategy.
        db.strategyQueries.updateExitParams(
            r_ladder_enabled = if (cfg.rLadderEnabled) 1L else 0L,
            tp1_r = cfg.tp1R,
            tp2_r = cfg.tp2R,
            break_even_after_tp1 = if (cfg.breakEvenAfterTp1) 1L else 0L,
            runner_enabled = if (cfg.runnerEnabled) 1L else 0L,
            atr_trail_enabled = if (cfg.atrTrailEnabled) 1L else 0L,
            atr_trail_mult = cfg.atrTrailMult,
            atr_trail_activation_r = cfg.atrTrailActivationR,
            id = cfg.id,
        )
        db.strategyQueries.updateSizingParams(
            risk_sizing_enabled = if (cfg.riskSizingEnabled) 1L else 0L,
            risk_per_trade_percent = cfg.riskPerTradePercent,
            max_pool_share_percent = cfg.maxPoolSharePercent,
            id = cfg.id,
        )
        db.strategyQueries.updateBreakoutParams(
            bp_higher_tf = cfg.bpHigherTf,
            bp_require_htf_trend = if (cfg.bpRequireHtfTrend) 1L else 0L,
            bp_breakout_lookback = cfg.bpBreakoutLookback.toLong(),
            bp_breakout_volume_mult = cfg.bpBreakoutVolumeMult,
            bp_max_bars_since_breakout = cfg.bpMaxBarsSinceBreakout.toLong(),
            bp_pullback_volume_ratio = cfg.bpPullbackVolumeRatio,
            bp_trigger_volume_mult = cfg.bpTriggerVolumeMult,
            id = cfg.id,
        )
        // Выходы «по иксам» и «Мем-плейбук» — тоже вне insert/updateStrategy.
        db.strategyQueries.updateTieredExitParams(
            tiered_trail_enabled = if (cfg.tieredTrailEnabled) 1L else 0L,
            trail_tier1_pct = cfg.trailTier1Pct,
            trail_tier2_pct = cfg.trailTier2Pct,
            trail_tier3_pct = cfg.trailTier3Pct,
            trail_tier4_pct = cfg.trailTier4Pct,
            stale_exit_enabled = if (cfg.staleExitEnabled) 1L else 0L,
            stale_exit_minutes = cfg.staleExitMinutes.toLong(),
            id = cfg.id,
        )
        db.strategyQueries.updateMemeParams(
            mp_lookback_bars = cfg.mpLookbackBars.toLong(),
            mp_min_impulse_pct = cfg.mpMinImpulsePct,
            mp_fib_min = cfg.mpFibMin,
            mp_fib_max = cfg.mpFibMax,
            mp_pullback_volume_ratio = cfg.mpPullbackVolumeRatio,
            mp_trigger_volume_mult = cfg.mpTriggerVolumeMult,
            mp_min_liq_to_mc_pct = cfg.mpMinLiqToMcPct,
            mp_require_revoked_authorities = if (cfg.mpRequireRevokedAuthorities) 1L else 0L,
            mp_reject_copycats = if (cfg.mpRejectCopycats) 1L else 0L,
            id = cfg.id,
        )
        db.strategyQueries.updateElliottParams(
            ew_higher_tf = cfg.ewHigherTf,
            ew_require_htf = if (cfg.ewRequireHtf) 1L else 0L,
            ew_swing_atr = cfg.ewSwingAtr,
            ew_sub_degree = cfg.ewSubDegree,
            ew_structure = cfg.ewStructure,
            ew_trade_wave3 = if (cfg.ewTradeWave3) 1L else 0L,
            ew_trade_wave5 = if (cfg.ewTradeWave5) 1L else 0L,
            ew_trade_correction = if (cfg.ewTradeCorrection) 1L else 0L,
            ew_w2_retrace_min = cfg.ewW2RetraceMin,
            ew_w2_retrace_max = cfg.ewW2RetraceMax,
            ew_w4_retrace_min = cfg.ewW4RetraceMin,
            ew_w4_retrace_max = cfg.ewW4RetraceMax,
            ew_min_guidelines = cfg.ewMinGuidelines.toLong(),
            ew_wave3_entry = cfg.ewWave3Entry,
            ew_entry_window_bars = cfg.ewEntryWindowBars.toLong(),
            ew_stop_anchor = cfg.ewStopAnchor,
            ew_stop_buffer_atr = cfg.ewStopBufferAtr,
            ew_w3_target = cfg.ewW3Target,
            ew_w5_target = cfg.ewW5Target,
            ew_min_reward_risk = cfg.ewMinRewardRisk,
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
        atr_period = cfg.atrPeriod.toLong(),
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
        atr_period = cfg.atrPeriod.toLong(),
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
