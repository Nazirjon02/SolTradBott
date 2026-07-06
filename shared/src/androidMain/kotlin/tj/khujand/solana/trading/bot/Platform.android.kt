package tj.khujand.solana.trading.bot

import android.content.Context
import tj.khujand.solana.trading.bot.service.ServiceHelper

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
