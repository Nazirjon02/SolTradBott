package tj.khujand.solana.trading.bot.bot.telegram.routing

import tj.khujand.solana.trading.bot.bot.application.TradingBotService
import tj.khujand.solana.trading.bot.bot.domain.security.AdminAccessPolicy
import tj.khujand.solana.trading.bot.bot.telegram.api.TelegramHttpClient
import tj.khujand.solana.trading.bot.bot.telegram.model.TelegramUpdate

class UpdateRouter(
    service: TradingBotService,
    private val telegram: TelegramHttpClient,
    private val accessPolicy: AdminAccessPolicy
) {
    private val commandRouter = CommandRouter(service, telegram)
    private val callbackRouter = CallbackRouter(service, telegram)

    suspend fun route(update: TelegramUpdate) {
        val message = update.message
        if (message != null) {
            val ctx = RouterContext(
                chatId = message.chat.id,
                userId = message.from?.id,
                messageId = message.messageId
            )
            if (!authorize(ctx)) return
            val text = message.text?.trim().orEmpty()
            if (text.startsWith("/")) {
                commandRouter.route(command = text.substringBefore(" "), ctx = ctx)
            }
            return
        }

        val callback = update.callbackQuery
        if (callback != null) {
            val messageRef = callback.message
            val ctx = RouterContext(
                chatId = messageRef?.chat?.id ?: 0L,
                userId = callback.from.id,
                messageId = messageRef?.messageId,
                callbackQueryId = callback.id
            )
            if (!authorize(ctx)) {
                callback.id.let { telegram.answerCallbackQuery(it, "Доступ запрещен") }
                return
            }
            val data = callback.data.orEmpty()
            callbackRouter.route(data, ctx)
        }
    }

    private suspend fun authorize(ctx: RouterContext): Boolean {
        val allowed = accessPolicy.isAllowed(chatId = ctx.chatId, userId = ctx.userId)
        if (!allowed && ctx.chatId != 0L) {
            telegram.sendMessage(ctx.chatId, "⛔ Доступ запрещен")
        }
        return allowed
    }
}
