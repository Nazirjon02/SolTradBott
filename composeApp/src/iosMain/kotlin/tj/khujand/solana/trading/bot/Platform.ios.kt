package tj.khujand.solana.trading.bot

import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual class ServiceController {
    actual fun startMonitoring() {
        println("Desktop: Monitoring started")
    }

    actual fun stopMonitoring() {
        println("Desktop: Monitoring stopped")
    }
}

actual fun createServiceController(): ServiceController {
    return ServiceController()
}

actual fun isAndroid(): Boolean = false
