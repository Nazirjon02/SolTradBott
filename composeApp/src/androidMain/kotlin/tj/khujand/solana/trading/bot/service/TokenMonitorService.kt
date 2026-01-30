package tj.khujand.solana.trading.bot.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import tj.khujand.solana.trading.bot.MainActivity
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.R

class TokenMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var tokenMonitor: TokenMonitor

    override fun onCreate() {
        super.onCreate()
        tokenMonitor = TokenMonitor()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                tokenMonitor.restoreFromCache()
                tokenMonitor.startMonitoring(
                    intervalSeconds = 3,
                    onError = { println(it) }
                )
            }
            ACTION_STOP -> {
                stopMonitoring()
            }
        }

        return START_STICKY
    }

    private fun stopMonitoring() {
        tokenMonitor.stopMonitoring()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        tokenMonitor.stopMonitoring()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 Dex Monitor")
            .setContentText("Monitoring active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Token Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Token monitoring service"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "ACTION_START_MONITORING"
        const val ACTION_STOP = "ACTION_STOP_MONITORING"
        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1
    }
}