package tj.khujand.solana.trading.bot

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect class ServiceController {
    fun startMonitoring()
    fun stopMonitoring()
}

expect fun createServiceController(): ServiceController

expect fun isAndroid(): Boolean

/** Записывает JSON-строку в файл настроек. Возвращает путь к файлу или сообщение об ошибке. */
expect fun writeSettingsFile(json: String): String

/** Читает содержимое файла настроек. Возвращает JSON-строку или null если файл не найден. */
expect fun readSettingsFile(): String?
