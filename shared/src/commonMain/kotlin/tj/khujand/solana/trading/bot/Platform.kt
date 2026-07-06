package tj.khujand.solana.trading.bot

/** Управление фоновым сервисом (Android — foreground service, JVM — no-op). */
expect class ServiceController {
    fun startMonitoring()
    fun stopMonitoring()
}

expect fun createServiceController(): ServiceController

expect fun isAndroid(): Boolean
