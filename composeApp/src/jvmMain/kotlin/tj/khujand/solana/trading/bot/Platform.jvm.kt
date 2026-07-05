package tj.khujand.solana.trading.bot

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

actual fun writeSettingsFile(json: String): String {
    return try {
        val dir  = java.io.File(System.getProperty("user.home"), ".soltradbot").also { it.mkdirs() }
        val file = java.io.File(dir, "bot_settings.json")
        file.writeText(json, Charsets.UTF_8)
        file.absolutePath
    } catch (e: Exception) {
        "ERROR:${e.message}"
    }
}

actual fun readSettingsFile(): String? {
    return try {
        val file = java.io.File(System.getProperty("user.home"), ".soltradbot/bot_settings.json")
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }
}
