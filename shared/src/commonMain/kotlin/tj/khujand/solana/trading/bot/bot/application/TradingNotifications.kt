package tj.khujand.solana.trading.bot.bot.application

import tj.khujand.solana.trading.bot.domain.MonitoredToken

/**
 * Общая шина уведомлений о найденных/закрытых токенах.
 *
 * При параллельной работе у каждой стратегии свой [TradingEngineController], но Telegram-бот
 * подписывается один раз. Каждый контроллер шлёт события сюда, поэтому подписчик получает
 * события всех стратегий, а не только "default".
 */
object TradingNotifications {
    private val foundListeners  = mutableMapOf<Long, (MonitoredToken) -> Unit>()
    private val closedListeners = mutableMapOf<Long, (MonitoredToken) -> Unit>()
    private var nextId: Long = 1L

    fun subscribeFound(listener: (MonitoredToken) -> Unit): Long = synchronized(this) {
        val id = nextId++; foundListeners[id] = listener; id
    }

    fun subscribeClosed(listener: (MonitoredToken) -> Unit): Long = synchronized(this) {
        val id = nextId++; closedListeners[id] = listener; id
    }

    fun unsubscribe(id: Long) = synchronized(this) {
        foundListeners.remove(id)
        closedListeners.remove(id)
    }

    fun emitFound(token: MonitoredToken) {
        val listeners = synchronized(this) { foundListeners.values.toList() }
        listeners.forEach { runCatching { it(token) } }
    }

    fun emitClosed(token: MonitoredToken) {
        val listeners = synchronized(this) { closedListeners.values.toList() }
        listeners.forEach { runCatching { it(token) } }
    }
}
