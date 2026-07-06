package tj.khujand.solana.trading.bot

actual class ServiceController {
    actual fun startMonitoring() { /* JVM: сервис не нужен, движок живёт в процессе */ }
    actual fun stopMonitoring() { /* no-op */ }
}

actual fun createServiceController(): ServiceController = ServiceController()

actual fun isAndroid(): Boolean = false
