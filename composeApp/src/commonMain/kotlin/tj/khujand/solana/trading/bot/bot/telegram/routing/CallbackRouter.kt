package tj.khujand.solana.trading.bot.bot.telegram.routing

import tj.khujand.solana.trading.bot.bot.application.TradingBotService
import tj.khujand.solana.trading.bot.bot.domain.model.TradingMode
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMenuBuilder
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMessageFormatter
import tj.khujand.solana.trading.bot.bot.telegram.api.TelegramHttpClient
import tj.khujand.solana.trading.bot.bot.telegram.callback.CallbackDataCodec

class CallbackRouter(
    private val service: TradingBotService,
    private val telegram: TelegramHttpClient
) {
    suspend fun route(rawData: String, ctx: RouterContext) {
        val payload = CallbackDataCodec.decode(rawData)
        if (payload == null) {
            ctx.callbackQueryId?.let { telegram.answerCallbackQuery(it, "Невалидный callback") }
            return
        }
        when (payload.section) {
            "main" -> handleMain(payload.action, ctx)
            "trade" -> handleTrade(payload.action, ctx)
            "mode" -> handleMode(payload.action, payload.param, ctx)
            "balance" -> showBalance(ctx)
            "deals" -> showDeals(ctx)
            "filters" -> handleFilters(payload.action, payload.param, ctx)
            "exit" -> handleExit(payload.action, payload.param, ctx)
            else -> ctx.callbackQueryId?.let { telegram.answerCallbackQuery(it, "Unknown action") }
        }
    }

    private suspend fun handleMain(action: String, ctx: RouterContext) {
        when (action) {
            "home", "refresh" -> showMain(ctx)
            "status" -> showStatus(ctx)
            "balance" -> showBalance(ctx)
            "deals" -> showDeals(ctx)
            "filters" -> showFilters(ctx)
            "exit" -> showExitStrategy(ctx)
            "mode" -> showMode(ctx)
        }
    }

    private suspend fun handleTrade(action: String, ctx: RouterContext) {
        val result = when (action) {
            "start" -> service.startTrading()
            "stop" -> service.stopTrading()
            else -> null
        }
        if (result != null) {
            showMain(ctx, notice = result.message)
        }
    }

    private suspend fun handleMode(action: String, param: String?, ctx: RouterContext) {
        when (action) {
            "set" -> {
                if (param == "real") {
                    editOrSend(
                        ctx,
                        text = "⚠️ Подтвердите переход в *REAL* режим",
                        keyboard = TelegramMenuBuilder.confirmRealModeMenu()
                    )
                } else {
                    val result = service.switchMode(TradingMode.DEMO)
                    showMode(ctx, result.message)
                }
            }
            "confirm" -> {
                if (param == "real") {
                    val result = service.switchMode(TradingMode.REAL)
                    showMode(ctx, result.message)
                }
            }
            "cancel" -> showMode(ctx, "Смена режима отменена")
        }
    }

    private suspend fun handleFilters(action: String, param: String?, ctx: RouterContext) {
        when (action) {
            "inc", "dec" -> {
                if (param != null) {
                    service.updateFilterValue(param, action)
                }
                showFilters(ctx)
            }
            "toggle" -> {
                if (param != null) {
                    service.toggleFilterFlag(param)
                }
                showFilters(ctx)
            }
            "set_risk" -> {
                if (param != null) {
                    service.setMaxAiRugRisk(param)
                }
                showFilters(ctx)
            }
            "refresh" -> showFilters(ctx)
        }
    }

    private suspend fun handleExit(action: String, param: String?, ctx: RouterContext) {
        when (action) {
            "inc", "dec" -> {
                if (param != null) {
                    service.updateExitStrategyValue(param, action)
                }
                showExitStrategy(ctx)
            }
            "mode" -> {
                if (param != null) {
                    service.setExitStrategy(param)
                }
                showExitStrategy(ctx)
            }
            "refresh" -> showExitStrategy(ctx)
        }
    }

    private suspend fun showMain(ctx: RouterContext, notice: String? = null) {
        val text = buildString {
            if (!notice.isNullOrBlank()) {
                appendLine("`$notice`")
                appendLine()
            }
            append(TelegramMessageFormatter.mainMenuMessage(service.getSystemSnapshot()))
        }
        editOrSend(ctx, text, TelegramMenuBuilder.mainMenu())
    }

    private suspend fun showStatus(ctx: RouterContext) {
        val text = TelegramMessageFormatter.statusMessage(service.getSystemSnapshot())
        editOrSend(ctx, text, TelegramMenuBuilder.mainMenu())
    }

    private suspend fun showMode(ctx: RouterContext, notice: String? = null) {
        val text = buildString {
            if (!notice.isNullOrBlank()) {
                appendLine("`$notice`")
                appendLine()
            }
            append(TelegramMessageFormatter.modeMessage(service.getMode()))
        }
        editOrSend(ctx, text, TelegramMenuBuilder.modeMenu())
    }

    private suspend fun showBalance(ctx: RouterContext) {
        val text = TelegramMessageFormatter.balanceMessage(
            balanceUsd = service.getBalanceUsd(),
            mode = service.getMode()
        )
        editOrSend(ctx, text, TelegramMenuBuilder.balanceMenu())
    }

    private suspend fun showDeals(ctx: RouterContext) {
        val text = TelegramMessageFormatter.dealsSummaryMessage(service.getDealsSummary())
        editOrSend(ctx, text, TelegramMenuBuilder.dealsMenu())
    }

    private suspend fun showFilters(ctx: RouterContext) {
        val view = service.getFilterSettingsView()
        val text = TelegramMessageFormatter.filtersMessage(view)
        editOrSend(ctx, text, TelegramMenuBuilder.filtersMenu(view))
    }

    private suspend fun showExitStrategy(ctx: RouterContext) {
        val view = service.getExitStrategyView()
        val text = TelegramMessageFormatter.exitStrategyMessage(view)
        editOrSend(ctx, text, TelegramMenuBuilder.exitStrategyMenu(view))
    }

    private suspend fun editOrSend(
        ctx: RouterContext,
        text: String,
        keyboard: tj.khujand.solana.trading.bot.bot.telegram.api.TelegramInlineKeyboard
    ) {
        if (ctx.messageId != null) {
            telegram.editMessageText(
                chatId = ctx.chatId,
                messageId = ctx.messageId,
                text = text,
                replyMarkup = keyboard
            )
        } else {
            telegram.sendMessage(
                chatId = ctx.chatId,
                text = text,
                replyMarkup = keyboard
            )
        }
        ctx.callbackQueryId?.let { telegram.answerCallbackQuery(it) }
    }
}
