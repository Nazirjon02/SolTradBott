package tj.khujand.solana.trading.bot.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotRunner
import tj.khujand.solana.trading.bot.bot.telegram.TelegramBotSettings
import tj.khujand.solana.trading.bot.MainActivity
import tj.khujand.solana.trading.bot.data.FilterSettingsManager
import tj.khujand.solana.trading.bot.domain.TokenMonitor
import tj.khujand.solana.trading.bot.R
import tj.khujand.solana.trading.bot.util.AppSettings

class TokenMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var tokenMonitor: TokenMonitor
    private var telegramBotRunner: TelegramBotRunner? = null

    override fun onCreate() {
        super.onCreate()
        tokenMonitor = TradingRuntime.tokenMonitor()
        createNotificationChannel()
        // Сразу показываем foreground — иначе Android выбросит ForegroundServiceDidNotStartInTimeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val botConfig = TelegramBotSettings.loadFromAppSettings()
        if (botConfig != null) {
            telegramBotRunner = TelegramBotRunner(
                config = botConfig,
                service = TradingRuntime.tradingBotService()
            ).also { it.start() }
            println("Telegram bot started in Android service")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_STICKY
            }
            else -> {
                // ACTION_START или null (перезапуск системой после убийства)
                AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, true)
                serviceScope.launch {
                    tokenMonitor.filterSettings = FilterSettingsManager.loadSettings()
                    tokenMonitor.restoreFromCache()
                    tokenMonitor.startMonitoring(
                        intervalSeconds = 10,
                        onRequestStateChanged = { inProgress ->
                            AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, inProgress)
                        },
                        onError = { println(it) }
                    )
                }
            }
        }
        return START_STICKY
    }

    private fun stopMonitoring() {
        AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, false)
        AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
        tokenMonitor.stopMonitoring()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, false)
        AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
        tokenMonitor.stopMonitoring()
        runBlocking {
            telegramBotRunner?.stop()
        }
        telegramBotRunner = null
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