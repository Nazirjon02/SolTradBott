package tj.khujand.solana.trading.bot.bot.application

import tj.khujand.solana.trading.bot.bot.domain.model.ActionResult
import tj.khujand.solana.trading.bot.bot.domain.model.DealsSummary
import tj.khujand.solana.trading.bot.bot.domain.model.ExitStrategyView
import tj.khujand.solana.trading.bot.bot.domain.model.FilterFieldSpec
import tj.khujand.solana.trading.bot.bot.domain.model.FilterSettingsView
import tj.khujand.solana.trading.bot.bot.domain.model.MonitoredTokenView
import tj.khujand.solana.trading.bot.bot.domain.model.SystemSnapshot
import tj.khujand.solana.trading.bot.bot.domain.model.TradingMode
import tj.khujand.solana.trading.bot.domain.MonitoredToken
import tj.khujand.solana.trading.bot.domain.TokenStatus
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.DemoAccountManager
import tj.khujand.solana.trading.bot.domain.TokenHistoryManager
import tj.khujand.solana.trading.bot.network.FilterSettings

class TradingBotService(
    private val engineController: TradingEngineController = TradingEngineController()
) {
    private val editableFields: List<FilterFieldSpec> = listOf(
        FilterFieldSpec(
            key = "maxTokensToMonitor",
            title = "Max tokens",
            min = 1.0,
            max = 20.0,
            step = 1.0
        ),
        FilterFieldSpec(
            key = "entryMaxAgeMinutes",
            title = "Max age (min)",
            min = 1.0,
            max = 180.0,
            step = 1.0
        ),
        FilterFieldSpec(
            key = "entryMinMarketCap",
            title = "Min market cap",
            min = 1_000.0,
            max = 5_000_000.0,
            step = 10_000.0
        ),
        FilterFieldSpec(
            key = "entryMaxMarketCap",
            title = "Max market cap",
            min = 10_000.0,
            max = 10_000_000.0,
            step = 10_000.0
        ),
        FilterFieldSpec(
            key = "entryMinLiquidity",
            title = "Min liquidity",
            min = 500.0,
            max = 5_000_000.0,
            step = 5_000.0
        ),
        FilterFieldSpec(
            key = "entryMinVolume",
            title = "Min vol 24h",
            min = 1_000.0,
            max = 10_000_000.0,
            step = 10_000.0
        ),
        FilterFieldSpec(
            key = "entryMinVolumeM5",
            title = "Min vol 5m",
            min = 1_000.0,
            max = 2_000_000.0,
            step = 5_000.0
        ),
        FilterFieldSpec(
            key = "minAiScore",
            title = "Min AI score",
            min = 0.0,
            max = 100.0,
            step = 5.0
        ),
        FilterFieldSpec(
            key = "maxDailyLossUsd",
            title = "Max daily loss $",
            min = 10.0,
            max = 10_000.0,
            step = 25.0
        ),
        FilterFieldSpec(
            key = "maxTotalExposureUsd",
            title = "Max exposure $",
            min = 10.0,
            max = 20_000.0,
            step = 25.0
        ),
        FilterFieldSpec(
            key = "maxConsecutiveLosses",
            title = "Max losses row",
            min = 1.0,
            max = 20.0,
            step = 1.0
        ),
        FilterFieldSpec(
            key = "stopLossByPricePct",
            title = "SL by price %",
            min = 1.0,
            max = 80.0,
            step = 1.0
        ),
        FilterFieldSpec(
            key = "stopLossByMarketCapPct",
            title = "SL by mcap %",
            min = 1.0,
            max = 90.0,
            step = 1.0
        ),
        FilterFieldSpec(
            key = "trailingStopPct",
            title = "Trailing stop %",
            min = 1.0,
            max = 90.0,
            step = 1.0
        ),
        FilterFieldSpec(
            key = "stagePullbackPct",
            title = "Stage pullback %",
            min = 1.0,
            max = 90.0,
            step = 1.0
        )
    )

    private val exitEditableFields: List<FilterFieldSpec> = listOf(
        FilterFieldSpec(
            key = "aggressiveTakeProfitPct",
            title = "Aggressive TP %",
            min = 20.0,
            max = 300.0,
            step = 5.0
        ),
        FilterFieldSpec(
            key = "aggressiveSellPct",
            title = "Aggressive sell %",
            min = 10.0,
            max = 100.0,
            step = 5.0
        ),
        FilterFieldSpec(
            key = "exitStage1Cap",
            title = "Stage1 cap",
            min = 10_000.0,
            max = 5_000_000.0,
            step = 10_000.0
        ),
        FilterFieldSpec(
            key = "exitStage1Pct",
            title = "Stage1 %",
            min = 5.0,
            max = 90.0,
            step = 5.0
        ),
        FilterFieldSpec(
            key = "exitStage2Cap",
            title = "Stage2 cap",
            min = 20_000.0,
            max = 5_000_000.0,
            step = 10_000.0
        ),
        FilterFieldSpec(
            key = "exitStage2Pct",
            title = "Stage2 %",
            min = 5.0,
            max = 90.0,
            step = 5.0
        ),
        FilterFieldSpec(
            key = "exitStage3Cap",
            title = "Stage3 cap",
            min = 30_000.0,
            max = 5_000_000.0,
            step = 10_000.0
        ),
        FilterFieldSpec(
            key = "exitStage3Pct",
            title = "Stage3 %",
            min = 5.0,
            max = 90.0,
            step = 5.0
        ),
        FilterFieldSpec(
            key = "exitStage4Cap",
            title = "Stage4 cap",
            min = 40_000.0,
            max = 5_000_000.0,
            step = 10_000.0
        ),
        FilterFieldSpec(
            key = "exitStage4Pct",
            title = "Stage4 %",
            min = 5.0,
            max = 90.0,
            step = 5.0
        )
    )

    fun startTrading(): ActionResult = engineController.startMonitoring()

    fun stopTrading(): ActionResult = engineController.stopMonitoring()

    fun getMode(): TradingMode {
        val settings = FilterSettingsManager.loadSettings()
        return if (settings.jupiterEnabled) TradingMode.REAL else TradingMode.DEMO
    }

    fun switchMode(targetMode: TradingMode, force: Boolean = false): ActionResult {
        val current = FilterSettingsManager.loadSettings()
        val currentMode = if (current.jupiterEnabled) TradingMode.REAL else TradingMode.DEMO

        if (currentMode == targetMode) {
            return ActionResult(success = true, message = "Режим уже установлен: ${currentMode.name.lowercase()}")
        }
        if (engineController.isMonitoring() && !force) {
            return ActionResult(
                success = false,
                message = "Остановите мониторинг перед сменой режима"
            )
        }

        val updated = current.copy(jupiterEnabled = targetMode == TradingMode.REAL)
        FilterSettingsManager.saveSettings(updated)
        return ActionResult(
            success = true,
            message = "Режим переключен: ${targetMode.name.lowercase()}"
        )
    }

    fun getFilterSettingsView(): FilterSettingsView {
        return FilterSettingsView(
            settings = FilterSettingsManager.loadSettings(),
            editableFields = editableFields
        )
    }

    fun getExitStrategyView(): ExitStrategyView {
        return ExitStrategyView(
            settings = FilterSettingsManager.loadSettings(),
            editableFields = exitEditableFields
        )
    }

    fun updateFilterValue(fieldKey: String, operation: String): ActionResult {
        val settings = FilterSettingsManager.loadSettings()
        val field = editableFields.firstOrNull { it.key == fieldKey }
            ?: return ActionResult(success = false, message = "Параметр не найден")

        val delta = if (operation == "inc") field.step else -field.step
        val updated = when (fieldKey) {
            "maxTokensToMonitor" -> {
                val value = (settings.maxTokensToMonitor.toDouble() + delta)
                    .coerceIn(field.min, field.max)
                    .toInt()
                settings.copy(maxTokensToMonitor = value)
            }
            "entryMaxAgeMinutes" -> {
                val value = (settings.entryMaxAgeMinutes.toDouble() + delta)
                    .coerceIn(field.min, field.max)
                    .toInt()
                settings.copy(entryMaxAgeMinutes = value)
            }
            "entryMinMarketCap" -> {
                val value = (settings.entryMinMarketCap + delta).coerceIn(field.min, field.max)
                settings.copy(entryMinMarketCap = value)
            }
            "entryMinLiquidity" -> {
                val value = (settings.entryMinLiquidity + delta).coerceIn(field.min, field.max)
                settings.copy(entryMinLiquidity = value)
            }
            "entryMaxMarketCap" -> {
                val value = (settings.entryMaxMarketCap + delta).coerceIn(field.min, field.max)
                settings.copy(entryMaxMarketCap = value)
            }
            "entryMinVolume" -> {
                val value = (settings.entryMinVolume + delta).coerceIn(field.min, field.max)
                settings.copy(entryMinVolume = value)
            }
            "entryMinVolumeM5" -> {
                val value = (settings.entryMinVolumeM5 + delta).coerceIn(field.min, field.max)
                settings.copy(entryMinVolumeM5 = value)
            }
            "minAiScore" -> {
                val value = (settings.minAiScore.toDouble() + delta).coerceIn(field.min, field.max).toInt()
                settings.copy(minAiScore = value)
            }
            "maxDailyLossUsd" -> {
                val value = (settings.maxDailyLossUsd + delta).coerceIn(field.min, field.max)
                settings.copy(maxDailyLossUsd = value)
            }
            "maxTotalExposureUsd" -> {
                val value = (settings.maxTotalExposureUsd + delta).coerceIn(field.min, field.max)
                settings.copy(maxTotalExposureUsd = value)
            }
            "maxConsecutiveLosses" -> {
                val value = (settings.maxConsecutiveLosses.toDouble() + delta).coerceIn(field.min, field.max).toInt()
                settings.copy(maxConsecutiveLosses = value)
            }
            "stopLossByPricePct" -> {
                val value = (settings.stopLossByPricePct + delta).coerceIn(field.min, field.max)
                settings.copy(stopLossByPricePct = value)
            }
            "stopLossByMarketCapPct" -> {
                val value = (settings.stopLossByMarketCapPct + delta).coerceIn(field.min, field.max)
                settings.copy(stopLossByMarketCapPct = value)
            }
            "trailingStopPct" -> {
                val value = (settings.trailingStopPct + delta).coerceIn(field.min, field.max)
                settings.copy(trailingStopPct = value)
            }
            "stagePullbackPct" -> {
                val value = (settings.stagePullbackPct + delta).coerceIn(field.min, field.max)
                settings.copy(stagePullbackPct = value)
            }
            else -> settings
        }
        FilterSettingsManager.saveSettings(updated)
        return ActionResult(success = true, message = "Параметр ${field.title} обновлен")
    }

    fun toggleFilterFlag(fieldKey: String): ActionResult {
        val settings = FilterSettingsManager.loadSettings()
        val updated = when (fieldKey) {
            "useVolumeH24" -> settings.copy(useVolumeH24 = !settings.useVolumeH24)
            "useVolumeM5" -> settings.copy(useVolumeM5 = !settings.useVolumeM5)
            "requireSocials" -> settings.copy(requireSocials = !settings.requireSocials)
            "requireWebsite" -> settings.copy(requireWebsite = !settings.requireWebsite)
            "useAiAnalysis" -> settings.copy(useAiAnalysis = !settings.useAiAnalysis)
            "aiFailClosed" -> settings.copy(aiFailClosed = !settings.aiFailClosed)
            else -> return ActionResult(success = false, message = "Флаг не найден")
        }
        FilterSettingsManager.saveSettings(updated)
        return ActionResult(success = true, message = "Флаг $fieldKey переключен")
    }

    fun setMaxAiRugRisk(value: String): ActionResult {
        val target = value.uppercase()
        if (target !in setOf("LOW", "MEDIUM", "HIGH")) {
            return ActionResult(success = false, message = "Некорректный уровень риска")
        }
        val settings = FilterSettingsManager.loadSettings()
        FilterSettingsManager.saveSettings(settings.copy(maxAiRugRisk = target))
        return ActionResult(success = true, message = "Max AI rug risk: $target")
    }

    fun updateExitStrategyValue(fieldKey: String, operation: String): ActionResult {
        val settings = FilterSettingsManager.loadSettings()
        val field = exitEditableFields.firstOrNull { it.key == fieldKey }
            ?: return ActionResult(success = false, message = "Параметр не найден")
        val delta = if (operation == "inc") field.step else -field.step
        val updated = when (fieldKey) {
            "aggressiveTakeProfitPct" -> settings.copy(
                aggressiveTakeProfitPct = (settings.aggressiveTakeProfitPct + delta).coerceIn(field.min, field.max)
            )
            "aggressiveSellPct" -> settings.copy(
                aggressiveSellPct = (settings.aggressiveSellPct + delta).coerceIn(field.min, field.max)
            )
            "exitStage1Cap" -> settings.copy(
                exitStage1Cap = (settings.exitStage1Cap + delta).coerceIn(field.min, field.max)
            )
            "exitStage1Pct" -> settings.copy(
                exitStage1Pct = (settings.exitStage1Pct + delta).coerceIn(field.min, field.max)
            )
            "exitStage2Cap" -> settings.copy(
                exitStage2Cap = (settings.exitStage2Cap + delta).coerceIn(field.min, field.max)
            )
            "exitStage2Pct" -> settings.copy(
                exitStage2Pct = (settings.exitStage2Pct + delta).coerceIn(field.min, field.max)
            )
            "exitStage3Cap" -> settings.copy(
                exitStage3Cap = (settings.exitStage3Cap + delta).coerceIn(field.min, field.max)
            )
            "exitStage3Pct" -> settings.copy(
                exitStage3Pct = (settings.exitStage3Pct + delta).coerceIn(field.min, field.max)
            )
            "exitStage4Cap" -> settings.copy(
                exitStage4Cap = (settings.exitStage4Cap + delta).coerceIn(field.min, field.max)
            )
            "exitStage4Pct" -> settings.copy(
                exitStage4Pct = (settings.exitStage4Pct + delta).coerceIn(field.min, field.max)
            )
            else -> settings
        }
        FilterSettingsManager.saveSettings(updated)
        return ActionResult(success = true, message = "Параметр ${field.title} обновлен")
    }

    fun setExitStrategy(mode: String): ActionResult {
        val normalized = mode.lowercase()
        if (normalized !in setOf("stages", "aggressive")) {
            return ActionResult(success = false, message = "Неизвестная exit strategy")
        }
        val settings = FilterSettingsManager.loadSettings()
        FilterSettingsManager.saveSettings(settings.copy(exitStrategy = normalized))
        return ActionResult(success = true, message = "Exit strategy: $normalized")
    }

    fun getBalanceUsd(): Double {
        return DemoAccountManager.getBalance()
    }

    fun getDealsSummary(): DealsSummary {
        val stats = TokenHistoryManager.getStatistics()
        return DealsSummary(
            totalTrades = stats.totalTrades,
            profitableTrades = stats.tpCount,
            losingTrades = stats.slCount,
            netProfitUsd = stats.netProfit,
            winRatePct = stats.winRate
        )
    }

    fun getMonitoredTokensView(): List<MonitoredTokenView> {
        return engineController
            .tokenMonitor()
            .monitoredTokens
            .asSequence()
            .filter { it.status == TokenStatus.MONITORING }
            .map(::toMonitoredTokenView)
            .toList()
    }

    fun subscribeOnTokenFound(listener: (MonitoredTokenView) -> Unit): Long {
        return engineController.subscribeOnTokenFound { token ->
            listener(toMonitoredTokenView(token))
        }
    }

    fun unsubscribeOnTokenFound(id: Long) {
        engineController.unsubscribeOnTokenFound(id)
    }

    private fun toMonitoredTokenView(token: MonitoredToken): MonitoredTokenView {
        val rawName = token.tokenPair.baseToken?.name
            ?: token.tokenPair.baseToken?.symbol
            ?: "Unknown"
        val rawAddress = token.tokenPair.baseToken?.address
            ?: token.tokenPair.pairAddress
            ?: "N/A"
        return MonitoredTokenView(
            name = rawName,
            tokenAddress = rawAddress
        )
    }

    fun getSystemSnapshot(): SystemSnapshot {
        val settings: FilterSettings = FilterSettingsManager.loadSettings()
        val mode = if (settings.jupiterEnabled) TradingMode.REAL else TradingMode.DEMO
        return SystemSnapshot(
            isMonitoring = engineController.isMonitoring(),
            mode = mode,
            demoBalanceUsd = getBalanceUsd(),
            dealsSummary = getDealsSummary(),
            keyParameters = mapOf(
                "maxTokensToMonitor" to settings.maxTokensToMonitor.toString(),
                "entryMaxAgeMinutes" to settings.entryMaxAgeMinutes.toString(),
                "entryMinMarketCap" to settings.entryMinMarketCap.toInt().toString(),
                "entryMaxMarketCap" to settings.entryMaxMarketCap.toInt().toString(),
                "entryMinLiquidity" to settings.entryMinLiquidity.toInt().toString(),
                "entryMinVolume" to settings.entryMinVolume.toInt().toString(),
                "entryMinVolumeM5" to settings.entryMinVolumeM5.toInt().toString(),
                "exitStrategy" to settings.exitStrategy,
                "useAiAnalysis" to settings.useAiAnalysis.toString(),
                "aiFailClosed" to settings.aiFailClosed.toString(),
                "maxDailyLossUsd" to settings.maxDailyLossUsd.toInt().toString(),
                "maxTotalExposureUsd" to settings.maxTotalExposureUsd.toInt().toString(),
                "maxConsecutiveLosses" to settings.maxConsecutiveLosses.toString(),
                "stopLossByPricePct" to settings.stopLossByPricePct.toInt().toString(),
                "stopLossByMarketCapPct" to settings.stopLossByMarketCapPct.toInt().toString(),
                "trailingStopPct" to settings.trailingStopPct.toInt().toString(),
                "stagePullbackPct" to settings.stagePullbackPct.toInt().toString()
            )
        )
    }
}
