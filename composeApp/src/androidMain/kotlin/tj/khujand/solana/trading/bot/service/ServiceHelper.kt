package tj.khujand.solana.trading.bot.service

import android.content.Context
import android.content.Intent
import android.os.Build

object ServiceHelper {

    fun startMonitoringService(context: Context) {
        val intent = Intent(context, TokenMonitorService::class.java).apply {
            action = TokenMonitorService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopMonitoringService(context: Context) {
        val intent = Intent(context, TokenMonitorService::class.java).apply {
            action = TokenMonitorService.ACTION_STOP
        }
        context.startService(intent)
    }
}