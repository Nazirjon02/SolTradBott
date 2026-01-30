package tj.khujand.solana.trading.bot

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual class ServiceController {
    actual fun startMonitoring() {
        println("iOS: Background monitoring not supported")
    }

    actual fun stopMonitoring() {
        println("iOS: Background monitoring not supported")
    }
}

actual fun createServiceController(): ServiceController {
    return ServiceController()
}

actual fun isAndroid(): Boolean = false
