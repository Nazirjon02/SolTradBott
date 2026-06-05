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
import tj.khujand.solana.trading.bot.R
import tj.khujand.solana.trading.bot.util.AppSettings

class TokenMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var telegramBotRunner: TelegramBotRunner? = null

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
                // ACTION_START или null (перезапуск системой после убийства):
                // восстанавливаем все ранее запущенные стратегии параллельно.
                serviceScope.launch {
                    TradingRuntime.tradingBotService().syncRunningStrategies()
                    ensureTelegramBotRunning()
                }
            }
        }
        return START_STICKY
    }

    private fun ensureTelegramBotRunning() {
        if (telegramBotRunner != null) return
        val botConfig = TelegramBotSettings.loadFromAppSettings() ?: return
        telegramBotRunner = TelegramBotRunner(
            config = botConfig,
            service = TradingRuntime.tradingBotService()
        ).also { it.start() }
        println("Telegram bot started in Android service")
    }

    private fun stopMonitoring() {
        // Останавливаем ВСЕ стратегии — foreground гасим только когда никого не осталось.
        TradingRuntime.tradingBotService().stopAllStrategies()
        AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, false)
        AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        AppSettings.putBoolean(AppSettings.KEY_MONITORING_ACTIVE, false)
        AppSettings.putBoolean(AppSettings.KEY_REQUEST_IN_PROGRESS, false)
        TradingRuntime.tradingBotService().stopAllStrategies()
        runBlocking(Dispatchers.IO) {
            telegramBotRunner?.stop()
        }
        telegramBotRunner = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("🤖 Dex Monitor")
            .setContentText("Monitoring active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // Канал для фонового сервиса (тихий, не беспокоить)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE_ID, "Token Monitor Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Token monitoring service"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val ACTION_START = "ACTION_START_MONITORING"
        const val ACTION_STOP  = "ACTION_STOP_MONITORING"
        private const val CHANNEL_SERVICE_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1
    }
}