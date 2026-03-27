package tj.khujand.solana.trading.bot.bot.presentation

import tj.khujand.solana.trading.bot.bot.domain.model.DealsSummary
import tj.khujand.solana.trading.bot.bot.domain.model.ExitStrategyView
import tj.khujand.solana.trading.bot.bot.domain.model.FilterSettingsView
import tj.khujand.solana.trading.bot.bot.domain.model.SystemSnapshot
import tj.khujand.solana.trading.bot.bot.domain.model.TradingMode
import kotlin.math.absoluteValue

object TelegramMessageFormatter {
    fun mainMenuMessage(snapshot: SystemSnapshot): String {
        return buildString {
            appendLine("*SolTradBot Control Panel*")
            appendLine()
            appendLine("• Мониторинг: ${if (snapshot.isMonitoring) "🟢 active" else "⚪ stopped"}")
            appendLine("• Режим: ${formatMode(snapshot.mode)}")
            appendLine("• Demo balance: `$${formatUsd(snapshot.demoBalanceUsd)}`")
            appendLine("• Сделки: `${snapshot.dealsSummary.totalTrades}` (win `${snapshot.dealsSummary.profitableTrades}` / loss `${snapshot.dealsSummary.losingTrades}`)")
        }
    }

    fun statusMessage(snapshot: SystemSnapshot): String {
        return buildString {
            appendLine("*System Status*")
            appendLine("Мониторинг: ${if (snapshot.isMonitoring) "🟢 работает" else "⚪ остановлен"}")
            appendLine("Режим: ${formatMode(snapshot.mode)}")
            appendLine("Баланс: `$${formatUsd(snapshot.demoBalanceUsd)}`")
            appendLine()
            appendLine("*Сделки*")
            appendLine(formatDealsSummary(snapshot.dealsSummary))
            appendLine()
            appendLine("*Ключевые параметры*")
            snapshot.keyParameters.forEach { (k, v) ->
                appendLine("• `$k`: `$v`")
            }
        }
    }

    fun balanceMessage(balanceUsd: Double, mode: TradingMode): String {
        return buildString {
            appendLine("*Balance*")
            appendLine("Режим: ${formatMode(mode)}")
            appendLine("Текущий demo баланс: `$${formatUsd(balanceUsd)}`")
            appendLine("_Нажмите Refresh для обновления_")
        }
    }

    fun dealsSummaryMessage(summary: DealsSummary): String {
        return buildString {
            appendLine("*Deals Summary*")
            appendLine(formatDealsSummary(summary))
        }
    }

    fun filtersMessage(view: FilterSettingsView): String {
        val s = view.settings
        return buildString {
            appendLine("*Filter Settings*")
            appendLine("• maxTokensToMonitor: `${s.maxTokensToMonitor}`")
            appendLine("• entryMaxAgeMinutes: `${s.entryMaxAgeMinutes}`")
            appendLine("• entryMinMarketCap: `${s.entryMinMarketCap.toInt()}`")
            appendLine("• entryMaxMarketCap: `${s.entryMaxMarketCap.toInt()}`")
            appendLine("• entryMinLiquidity: `${s.entryMinLiquidity.toInt()}`")
            appendLine("• entryMinVolume24h: `${s.entryMinVolume.toInt()}`")
            appendLine("• entryMinVolumeM5: `${s.entryMinVolumeM5.toInt()}`")
            appendLine("• useVolumeH24: `${s.useVolumeH24}`")
            appendLine("• useVolumeM5: `${s.useVolumeM5}`")
            appendLine("• requireSocials: `${s.requireSocials}`")
            appendLine("• requireWebsite: `${s.requireWebsite}`")
            appendLine()
            appendLine("*AI checks*")
            appendLine("• useAiAnalysis: `${s.useAiAnalysis}`")
            appendLine("• aiFailClosed: `${s.aiFailClosed}`")
            appendLine("• minAiScore: `${s.minAiScore}`")
            appendLine("• maxAiRugRisk: `${s.maxAiRugRisk}`")
            appendLine()
            appendLine("*Risk limits*")
            appendLine("• maxDailyLossUsd: `${s.maxDailyLossUsd.toInt()}`")
            appendLine("• maxTotalExposureUsd: `${s.maxTotalExposureUsd.toInt()}`")
            appendLine("• maxConsecutiveLosses: `${s.maxConsecutiveLosses}`")
            appendLine()
            appendLine("_Изменение параметров доступно через кнопки ниже_")
        }
    }

    fun exitStrategyMessage(view: ExitStrategyView): String {
        val s = view.settings
        return buildString {
            appendLine("*Exit Strategy*")
            appendLine("• strategy: `${s.exitStrategy}`")
            appendLine("• aggressiveTakeProfitPct: `${s.aggressiveTakeProfitPct.toInt()}%`")
            appendLine("• aggressiveSellPct: `${s.aggressiveSellPct.toInt()}%`")
            appendLine()
            appendLine("*Stages*")
            appendLine("• stage1: `${s.exitStage1Cap.toInt()}` / `${s.exitStage1Pct.toInt()}%`")
            appendLine("• stage2: `${s.exitStage2Cap.toInt()}` / `${s.exitStage2Pct.toInt()}%`")
            appendLine("• stage3: `${s.exitStage3Cap.toInt()}` / `${s.exitStage3Pct.toInt()}%`")
            appendLine("• stage4: `${s.exitStage4Cap.toInt()}` / `${s.exitStage4Pct.toInt()}%`")
            appendLine()
            appendLine("_Изменение параметров доступно через кнопки ниже_")
        }
    }

    fun modeMessage(mode: TradingMode): String {
        return buildString {
            appendLine("*Trading Mode*")
            appendLine("Текущий режим: ${formatMode(mode)}")
            appendLine()
            appendLine("⚠️ `real` режим использует реальные сделки через Jupiter.")
        }
    }

    private fun formatDealsSummary(summary: DealsSummary): String {
        val pnlPrefix = if (summary.netProfitUsd >= 0) "+" else "-"
        return buildString {
            appendLine("• Всего: `${summary.totalTrades}`")
            appendLine("• Прибыльные: `+${summary.profitableTrades}`")
            appendLine("• Убыточные: `-${summary.losingTrades}`")
            appendLine("• Win rate: `${summary.winRatePct.toInt()}%`")
            appendLine("• Итог PnL: `${pnlPrefix}$${formatUsd(summary.netProfitUsd.absoluteValue)}`")
        }
    }

    private fun formatMode(mode: TradingMode): String {
        return if (mode == TradingMode.REAL) "🔴 real" else "🟢 demo"
    }

    private fun formatUsd(value: Double): String {
        return (kotlin.math.round(value * 100.0) / 100.0).toString()
    }
}
