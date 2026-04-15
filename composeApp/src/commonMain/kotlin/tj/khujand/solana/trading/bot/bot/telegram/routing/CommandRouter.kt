package tj.khujand.solana.trading.bot.bot.telegram.routing

import tj.khujand.solana.trading.bot.bot.application.TradingBotService
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMenuBuilder
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMessageFormatter
import tj.khujand.solana.trading.bot.bot.telegram.api.TelegramHttpClient

class CommandRouter(
    private val service: TradingBotService,
    private val telegram: TelegramHttpClient
) {
    suspend fun route(command: String, ctx: RouterContext) {
        when (command) {
            "/start" -> sendMain(ctx.chatId)
            "/help" -> sendHelp(ctx.chatId)
            "/status" -> {
                val text = TelegramMessageFormatter.statusMessage(service.getSystemSnapshot())
                telegram.sendMessage(ctx.chatId, text, TelegramMenuBuilder.mainMenu())
            }
            "/monitor_start" -> {
                val result = service.startTrading()
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.actionNotice(result.message) +
                        TelegramMessageFormatter.mainMenuMessage(service.getSystemSnapshot()),
                    TelegramMenuBuilder.mainMenu()
                )
            }
            "/monitor_stop" -> {
                val result = service.stopTrading()
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.actionNotice(result.message) +
                        TelegramMessageFormatter.mainMenuMessage(service.getSystemSnapshot()),
                    TelegramMenuBuilder.mainMenu()
                )
            }
            "/mode" -> {
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.modeMessage(service.getMode()),
                    TelegramMenuBuilder.modeMenu()
                )
            }
            "/balance" -> {
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.balanceMessage(service.getBalanceUsd(), service.getMode()),
                    TelegramMenuBuilder.balanceMenu()
                )
            }
            "/deals" -> {
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.dealsSummaryMessage(service.getDealsSummary()),
                    TelegramMenuBuilder.dealsMenu()
                )
            }
            "/monitoring" -> {
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.monitoringMessage(service.getMonitoredTokensView()),
                    TelegramMenuBuilder.monitoringMenu()
                )
            }
            "/filters" -> {
                val view = service.getFilterSettingsView()
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.filtersMessage(view),
                    TelegramMenuBuilder.filtersMenu(view)
                )
            }
            "/exit" -> {
                val view = service.getExitStrategyView()
                telegram.sendMessage(
                    ctx.chatId,
                    TelegramMessageFormatter.exitStrategyMessage(view),
                    TelegramMenuBuilder.exitStrategyMenu(view)
                )
            }
            else -> {
                sendHelp(ctx.chatId)
            }
        }
    }

    private suspend fun sendMain(chatId: Long) {
        val text = TelegramMessageFormatter.mainMenuMessage(service.getSystemSnapshot())
        telegram.sendMessage(chatId, text, TelegramMenuBuilder.mainMenu())
    }

    private suspend fun sendHelp(chatId: Long) {
        telegram.sendMessage(chatId, TelegramMessageFormatter.helpMessage(), TelegramMenuBuilder.mainMenu())
    }
}
