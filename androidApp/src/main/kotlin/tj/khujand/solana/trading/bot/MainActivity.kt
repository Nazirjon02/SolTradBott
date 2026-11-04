package tj.khujand.solana.trading.bot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tj.khujand.solana.trading.bot.service.ServiceHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        requestBatteryOptimizationExemption()
        // Рантайм уже поднят в DrxApp.onCreate (init идемпотентен — вернёт тот же экземпляр).
        val runtime = DrxRuntimeHolder.get() ?: DrxRuntimeHolder.init("soltradbot.db")
        // Тумблер «Возобновлять торговлю при запуске»: процесс поднялся заново (после перезагрузки или
        // выгрузки системой) — возвращаем бота в то состояние, в котором его оставили. Activity на экране —
        // foreground-сервис запускать можно.
        if (runtime.takeResumeOnLaunch()) runCatching { ServiceHelper.restoreTrading(this) }
        setContent { App(runtime) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }
}
