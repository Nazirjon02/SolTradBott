package tj.khujand.solana.trading.bot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.DrxRuntimeHolder

/**
 * Foreground-сервис DRX: держит процесс живым, пока движок торгует.
 * Сам движок живёт в DrxRuntimeHolder (singleton) — сервис лишь показывает
 * уведомление и гасит движок при своём уничтожении системой/пользователем.
 */
class TokenMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Сразу показываем foreground — иначе ForegroundServiceDidNotStartInTimeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START или null (перезапуск системой): движок уже запущен из UI,
                // сервис просто удерживает процесс.
            }
        }
        return START_STICKY
    }

    private fun stopMonitoring() {
        serviceScope.launch { DrxRuntimeHolder.get()?.engine?.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.launch { DrxRuntimeHolder.get()?.engine?.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // Модуль shared не знает про MainActivity приложения — открываем через launch-intent пакета.
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync
        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("🤖 DRX Bot")
            .setContentText("Торговый движок работает")
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE_ID, "DRX Bot Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "DRX trading engine"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val ACTION_START = "ACTION_START_MONITORING"
        const val ACTION_STOP = "ACTION_STOP_MONITORING"
        private const val CHANNEL_SERVICE_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1
    }
}
