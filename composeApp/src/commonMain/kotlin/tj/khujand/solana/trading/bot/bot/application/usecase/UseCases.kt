package tj.khujand.solana.trading.bot.bot.application.usecase

import tj.khujand.solana.trading.bot.bot.application.TradingBotService
import tj.khujand.solana.trading.bot.bot.domain.model.ActionResult
import tj.khujand.solana.trading.bot.bot.domain.model.DealsSummary
import tj.khujand.solana.trading.bot.bot.domain.model.FilterSettingsView
import tj.khujand.solana.trading.bot.bot.domain.model.SystemSnapshot
import tj.khujand.solana.trading.bot.bot.domain.model.TradingMode

class StartTradingUseCase(private val service: TradingBotService) {
    operator fun invoke(): ActionResult = service.startTrading()
}

class StopTradingUseCase(private val service: TradingBotService) {
    operator fun invoke(): ActionResult = service.stopTrading()
}

class SwitchModeUseCase(private val service: TradingBotService) {
    operator fun invoke(mode: TradingMode, force: Boolean = false): ActionResult {
        return service.switchMode(mode, force)
    }
}

class GetSystemStateUseCase(private val service: TradingBotService) {
    operator fun invoke(): SystemSnapshot = service.getSystemSnapshot()
}

class UpdateFilterUseCase(private val service: TradingBotService) {
    operator fun invoke(fieldKey: String, operation: String): ActionResult {
        return service.updateFilterValue(fieldKey, operation)
    }
}

class GetDealsSummaryUseCase(private val service: TradingBotService) {
    operator fun invoke(): DealsSummary = service.getDealsSummary()
}

class GetFilterSettingsUseCase(private val service: TradingBotService) {
    operator fun invoke(): FilterSettingsView = service.getFilterSettingsView()
}
