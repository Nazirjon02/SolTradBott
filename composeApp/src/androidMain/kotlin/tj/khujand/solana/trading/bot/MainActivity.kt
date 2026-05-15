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
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import tj.khujand.solana.trading.bot.bot.application.TradingRuntime
import tj.khujand.solana.trading.bot.ui.MainScreen
import tj.khujand.solana.trading.bot.util.AppSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initServiceController(this)
        requestNotificationPermission()
        requestBatteryOptimizationExemption()
        // Синхронизируем StateFlow с реальным состоянием при старте
        syncMonitoringStateFlow()
        setContent { App() }
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

    // При горячем старте/возврате синхронизируем StateFlow из AppSettings
    private fun syncMonitoringStateFlow() {
        val active = AppSettings.getBooleanSafe(AppSettings.KEY_MONITORING_ACTIVE, false)
        TradingRuntime.setMonitoringActive(active)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    MainScreen()
}