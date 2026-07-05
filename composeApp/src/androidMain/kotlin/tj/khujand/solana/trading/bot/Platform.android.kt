package tj.khujand.solana.trading.bot

import android.content.Context
import tj.khujand.solana.trading.bot.service.ServiceHelper
import java.io.File

actual class ServiceController(private val context: Context) {
    actual fun startMonitoring() {
        ServiceHelper.startMonitoringService(context)
    }
    actual fun stopMonitoring() {
        ServiceHelper.stopMonitoringService(context)
    }
}

private lateinit var appContext: Context

fun initServiceController(context: Context) {
    appContext = context.applicationContext
}

fun getAppContext(): Context = appContext

actual fun createServiceController(): ServiceController = ServiceController(appContext)

actual fun isAndroid(): Boolean = true

actual fun writeSettingsFile(json: String): String {
    return try {
        val dir  = File(appContext.filesDir, "SolTradBot").also { it.mkdirs() }
        val file = File(dir, "bot_settings.json")
        file.writeText(json, Charsets.UTF_8)
        file.absolutePath
    } catch (e: Exception) {
        "ERROR:${e.message}"
    }
}

actual fun readSettingsFile(): String? {
    return try {
        val file = File(appContext.filesDir, "SolTradBot/bot_settings.json")
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }
}
