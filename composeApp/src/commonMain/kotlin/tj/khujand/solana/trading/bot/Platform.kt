package tj.khujand.solana.trading.bot

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect class ServiceController {
    fun startMonitoring()
    fun stopMonitoring()
}

// Фабричная функция для создания ServiceController
expect fun createServiceController(): ServiceController

expect fun isAndroid(): Boolean
