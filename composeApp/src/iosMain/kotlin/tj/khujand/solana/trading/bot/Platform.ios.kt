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

actual fun writeSettingsFile(json: String): String {
    return try {
        val docs = platform.Foundation.NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSDocumentDirectory, platform.Foundation.NSUserDomainMask, true
        ).firstOrNull() as? String ?: return "ERROR:no documents dir"
        val path = "$docs/bot_settings.json"
        platform.Foundation.NSString.create(string = json)
            .writeToFile(path, atomically = true, encoding = platform.Foundation.NSUTF8StringEncoding, error = null)
        path
    } catch (e: Exception) {
        "ERROR:${e.message}"
    }
}

actual fun readSettingsFile(): String? {
    return try {
        val docs = platform.Foundation.NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSDocumentDirectory, platform.Foundation.NSUserDomainMask, true
        ).firstOrNull() as? String ?: return null
        val path = "$docs/bot_settings.json"
        platform.Foundation.NSString.stringWithContentsOfFile(path, encoding = platform.Foundation.NSUTF8StringEncoding, error = null) as? String
    } catch (e: Exception) {
        null
    }
}
