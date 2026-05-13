package tj.khujand.solana.trading.bot

import android.content.Context
import android.os.Build
import tj.khujand.solana.trading.bot.service.ServiceHelper

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual class ServiceController(private val context: Context) {
    actual fun startMonitoring() {
        // Android - запускаем ФОНОВЫЙ сервис
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

actual fun createServiceController(): ServiceController {
    return ServiceController(appContext)
}
actual fun isAndroid(): Boolean = true
