package tj.khujand.solana.trading.bot.bot.telegram.routing

import tj.khujand.solana.trading.bot.bot.application.TradingBotService
import tj.khujand.solana.trading.bot.bot.domain.model.TradingMode
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMenuBuilder
import tj.khujand.solana.trading.bot.bot.presentation.TelegramMessageFormatter
import tj.khujand.solana.trading.bot.bot.presentation.TelegramUiPages
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
            "monitoring" -> showMonitoring(ctx)
            "filters" -> handleFilters(payload.action, payload.param, ctx)
            "exit" -> handleExit(payload.action, payload.param, ctx)
            else -> ctx.callbackQueryId?.let { telegram.answerCallbackQuery(it, "Неизвестное действие") }
        }
    }

    private suspend fun handleMain(action: String, ctx: RouterContext) {
        when (action) {
            "home", "refresh" -> showMain(ctx)
            "status" -> showStatus(ctx)
            "balance" -> showBalance(ctx)
            "deals" -> showDeals(ctx)
            "monitoring" -> showMonitoring(ctx)
            "filters" -> showFilters(ctx, 0)
            "exit" -> showExitStrategy(ctx, 0)
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
                        text = TelegramMessageFormatter.confirmRealModeHtml(),
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
            "page", "refresh" -> {
                val page = param?.toIntOrNull()
                    ?.coerceIn(0, TelegramUiPages.FILTERS_PAGE_COUNT - 1)
                    ?: 0
                showFilters(ctx, page)
            }
            "inc", "dec" -> {
                val (page, field) = parsePageAndKey(param)
                field?.let { service.updateFilterValue(it, action) }
                showFilters(ctx, page)
            }
            "toggle" -> {
                val (page, key) = parsePageAndKey(param)
                key?.let { service.toggleFilterFlag(it) }
                showFilters(ctx, page)
            }
            "set_risk" -> {
                val (page, level) = parsePageAndKey(param)
                level?.let { service.setMaxAiRugRisk(it) }
                showFilters(ctx, page)
            }
        }
    }

    private suspend fun handleExit(action: String, param: String?, ctx: RouterContext) {
        when (action) {
            "page", "refresh" -> {
                val page = param?.toIntOrNull()
                    ?.coerceIn(0, TelegramUiPages.EXIT_PAGE_COUNT - 1)
                    ?: 0
                showExitStrategy(ctx, page)
            }
            "inc", "dec" -> {
                val (page, field) = parsePageAndKey(param)
                field?.let { service.updateExitStrategyValue(it, action) }
                showExitStrategy(ctx, page)
            }
            "mode" -> {
                val (page, strategy) = parsePageAndKey(param)
                strategy?.let { service.setExitStrategy(it) }
                showExitStrategy(ctx, page)
            }
            "toggle" -> {
                val (page, key) = parsePageAndKey(param)
                key?.let { service.toggleFilterFlag(it) }
                showExitStrategy(ctx, page)
            }
        }
    }

    /**
     * Формат {@code page~key}; без {@code ~} — совместимость со старыми callback (страница 0).
     */
    private fun parsePageAndKey(param: String?): Pair<Int, String?> {
        val raw = param?.trim().orEmpty()
        if (raw.isEmpty()) return 0 to null
        val idx = raw.indexOf('~')
        if (idx < 0) return 0 to raw
        val left = raw.substring(0, idx)
        val right = raw.substring(idx + 1)
        val page = left.toIntOrNull() ?: return 0 to raw
        return page to right.takeIf { it.isNotBlank() }
    }

    private suspend fun showMain(ctx: RouterContext, notice: String? = null) {
        val text = buildString {
            if (!notice.isNullOrBlank()) {
                append(TelegramMessageFormatter.actionNotice(notice))
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
                append(TelegramMessageFormatter.actionNotice(notice))
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

    private suspend fun showMonitoring(ctx: RouterContext) {
        val text = TelegramMessageFormatter.monitoringMessage(service.getMonitoredTokensView())
        editOrSend(ctx, text, TelegramMenuBuilder.monitoringMenu())
    }

    private suspend fun showFilters(ctx: RouterContext, page: Int = 0) {
        val view = service.getFilterSettingsView()
        val p = page.coerceIn(0, TelegramUiPages.FILTERS_PAGE_COUNT - 1)
        val text = TelegramMessageFormatter.filtersMessage(view, p)
        editOrSend(ctx, text, TelegramMenuBuilder.filtersMenu(view, p))
    }

    private suspend fun showExitStrategy(ctx: RouterContext, page: Int = 0) {
        val view = service.getExitStrategyView()
        val p = page.coerceIn(0, TelegramUiPages.EXIT_PAGE_COUNT - 1)
        val text = TelegramMessageFormatter.exitStrategyMessage(view, p)
        editOrSend(ctx, text, TelegramMenuBuilder.exitStrategyMenu(view, p))
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
